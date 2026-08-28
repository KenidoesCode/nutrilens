"""Image intake: validate, decode, strip metadata, normalise.

Every image entering the pipeline goes through :func:`prepare_image`. Nothing
downstream is allowed to assume anything about size, mode or provenance.
"""

from __future__ import annotations

import io
from dataclasses import dataclass

import numpy as np
from PIL import Image, ImageOps, UnidentifiedImageError

ALLOWED_FORMATS = frozenset({"JPEG", "PNG", "WEBP"})
ALLOWED_MIME_TYPES = frozenset({"image/jpeg", "image/png", "image/webp"})

MAX_IMAGE_BYTES = 12 * 1024 * 1024
MIN_DIMENSION_PX = 64
MAX_DIMENSION_PX = 8192
# Decompression-bomb guard: refuse anything above ~40 megapixels regardless of
# the on-disk byte size.
MAX_PIXELS = 40_000_000

DEFAULT_ANALYSIS_SIZE = 512


class ImageValidationError(ValueError):
    """Raised when an image is unusable. The message is safe to return to a client."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass(frozen=True, slots=True)
class PreparedImage:
    """A validated, normalised RGB image ready for inference."""

    rgb: np.ndarray
    original_width: int
    original_height: int
    source_format: str

    @property
    def width(self) -> int:
        return int(self.rgb.shape[1])

    @property
    def height(self) -> int:
        return int(self.rgb.shape[0])

    def as_float01(self) -> np.ndarray:
        return self.rgb.astype(np.float32) / 255.0


def validate_image_bytes(data: bytes, *, declared_mime: str | None = None) -> None:
    """Cheap structural checks before any decoding happens."""
    if not data:
        raise ImageValidationError("EMPTY_IMAGE", "The uploaded image was empty.")
    if len(data) > MAX_IMAGE_BYTES:
        raise ImageValidationError(
            "IMAGE_TOO_LARGE",
            f"The image exceeds the {MAX_IMAGE_BYTES // (1024 * 1024)} MB upload limit.",
        )
    if declared_mime is not None and declared_mime.lower() not in ALLOWED_MIME_TYPES:
        raise ImageValidationError(
            "UNSUPPORTED_MEDIA_TYPE",
            "Only JPEG, PNG and WebP images are supported.",
        )


def decode_image(data: bytes, *, declared_mime: str | None = None) -> Image.Image:
    """Decode bytes into a PIL image, enforcing format and dimension limits.

    The declared MIME type is never trusted on its own -- the sniffed format
    from the decoder is what gates acceptance.
    """
    validate_image_bytes(data, declared_mime=declared_mime)
    try:
        image = Image.open(io.BytesIO(data))
        image.verify()  # structural check; consumes the file object
        image = Image.open(io.BytesIO(data))
    except UnidentifiedImageError as exc:
        raise ImageValidationError(
            "INVALID_IMAGE", "The uploaded file is not a readable image."
        ) from exc
    except OSError as exc:
        raise ImageValidationError(
            "CORRUPT_IMAGE", "The uploaded image could not be decoded."
        ) from exc

    if image.format not in ALLOWED_FORMATS:
        raise ImageValidationError(
            "UNSUPPORTED_MEDIA_TYPE",
            "Only JPEG, PNG and WebP images are supported.",
        )

    width, height = image.size
    if width < MIN_DIMENSION_PX or height < MIN_DIMENSION_PX:
        raise ImageValidationError(
            "IMAGE_TOO_SMALL",
            f"The image must be at least {MIN_DIMENSION_PX}x{MIN_DIMENSION_PX} pixels.",
        )
    if width > MAX_DIMENSION_PX or height > MAX_DIMENSION_PX:
        raise ImageValidationError(
            "IMAGE_TOO_LARGE", "The image dimensions exceed the supported maximum."
        )
    if width * height > MAX_PIXELS:
        raise ImageValidationError(
            "IMAGE_TOO_LARGE", "The image resolution exceeds the supported maximum."
        )
    return image


def strip_metadata(image: Image.Image) -> Image.Image:
    """Return a copy carrying pixel data only.

    EXIF frequently contains GPS coordinates and device identifiers. Meal
    photos are sensitive, so orientation is applied and everything else is
    dropped before the image is stored or transmitted.
    """
    oriented = ImageOps.exif_transpose(image)
    # Rebuilding from raw pixel bytes drops EXIF, ICC, XMP and any other
    # ancillary chunk in one step, without enumerating what to remove.
    return Image.frombytes(oriented.mode, oriented.size, oriented.tobytes())


def prepare_image(
    data: bytes,
    *,
    declared_mime: str | None = None,
    target_size: int = DEFAULT_ANALYSIS_SIZE,
) -> PreparedImage:
    """Validate, decode, sanitise and letterbox an image for inference.

    The image is resized so its longest edge equals ``target_size`` and then
    centre-padded to a square, preserving aspect ratio. Padding is neutral grey
    so it does not bias colour statistics towards any food signature.
    """
    if target_size < MIN_DIMENSION_PX:
        raise ValueError(f"target_size must be >= {MIN_DIMENSION_PX}")

    decoded = decode_image(data, declared_mime=declared_mime)
    source_format = decoded.format or "UNKNOWN"
    sanitised = strip_metadata(decoded).convert("RGB")
    original_width, original_height = sanitised.size

    scale = target_size / max(original_width, original_height)
    new_size = (
        max(1, round(original_width * scale)),
        max(1, round(original_height * scale)),
    )
    resized = sanitised.resize(new_size, Image.Resampling.BILINEAR)

    canvas = Image.new("RGB", (target_size, target_size), (128, 128, 128))
    canvas.paste(
        resized,
        ((target_size - new_size[0]) // 2, (target_size - new_size[1]) // 2),
    )

    return PreparedImage(
        rgb=np.asarray(canvas, dtype=np.uint8),
        original_width=original_width,
        original_height=original_height,
        source_format=source_format,
    )
