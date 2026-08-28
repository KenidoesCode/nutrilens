"""Image intake: validation, sanitisation and normalisation."""

from __future__ import annotations

import io

import numpy as np
import pytest
from PIL import Image

from nutrilens_ml.preprocessing.image import (
    MAX_IMAGE_BYTES,
    ImageValidationError,
    decode_image,
    prepare_image,
    strip_metadata,
    validate_image_bytes,
)


class TestValidation:
    def test_rejects_empty_payload(self, encode_image):
        with pytest.raises(ImageValidationError) as excinfo:
            validate_image_bytes(b"")
        assert excinfo.value.code == "EMPTY_IMAGE"

    def test_rejects_oversized_payload(self, encode_image):
        with pytest.raises(ImageValidationError) as excinfo:
            validate_image_bytes(b"x" * (MAX_IMAGE_BYTES + 1))
        assert excinfo.value.code == "IMAGE_TOO_LARGE"

    def test_rejects_disallowed_declared_mime(self, encode_image):
        with pytest.raises(ImageValidationError) as excinfo:
            validate_image_bytes(b"abc", declared_mime="image/gif")
        assert excinfo.value.code == "UNSUPPORTED_MEDIA_TYPE"

    def test_rejects_non_image_bytes(self, encode_image):
        with pytest.raises(ImageValidationError) as excinfo:
            decode_image(b"this is definitely not a JPEG")
        assert excinfo.value.code == "INVALID_IMAGE"

    def test_rejects_truncated_image(self, encode_image):
        payload = encode_image(np.full((200, 200, 3), 120, dtype=np.uint8))
        with pytest.raises(ImageValidationError):
            decode_image(payload[: len(payload) // 3])

    def test_rejects_undersized_image(self, encode_image):
        with pytest.raises(ImageValidationError) as excinfo:
            decode_image(encode_image(np.zeros((16, 16, 3), dtype=np.uint8), fmt="PNG"))
        assert excinfo.value.code == "IMAGE_TOO_SMALL"

    def test_declared_mime_cannot_launder_an_unsupported_format(self, encode_image):
        buffer = io.BytesIO()
        Image.fromarray(np.zeros((128, 128, 3), dtype=np.uint8)).save(buffer, format="BMP")
        # The client claims JPEG; the sniffed format decides.
        with pytest.raises(ImageValidationError) as excinfo:
            decode_image(buffer.getvalue(), declared_mime="image/jpeg")
        assert excinfo.value.code == "UNSUPPORTED_MEDIA_TYPE"

    def test_accepts_png_and_webp(self, encode_image):
        array = np.full((200, 200, 3), 180, dtype=np.uint8)
        assert decode_image(encode_image(array, fmt="PNG")).format == "PNG"
        assert decode_image(encode_image(array, fmt="WEBP")).format == "WEBP"


class TestMetadataStripping:
    def test_removes_exif(self, encode_image):
        source = Image.fromarray(np.full((128, 128, 3), 90, dtype=np.uint8))
        buffer = io.BytesIO()
        exif = source.getexif()
        exif[271] = "SecretPhoneVendor"
        source.save(buffer, format="JPEG", exif=exif)

        decoded = decode_image(buffer.getvalue())
        assert decoded.getexif()

        cleaned = strip_metadata(decoded)
        assert not dict(cleaned.getexif())
        assert cleaned.size == decoded.size

    def test_preserves_pixels(self, encode_image):
        array = np.full((128, 128, 3), 77, dtype=np.uint8)
        cleaned = strip_metadata(decode_image(encode_image(array, fmt="PNG")))
        assert np.array_equal(np.asarray(cleaned), array)


class TestPrepareImage:
    def test_produces_a_square_canvas(self, encode_image):
        prepared = prepare_image(encode_image(np.full((480, 640, 3), 100, dtype=np.uint8)))
        assert prepared.rgb.shape == (512, 512, 3)

    def test_records_original_dimensions(self, encode_image):
        prepared = prepare_image(encode_image(np.full((480, 640, 3), 100, dtype=np.uint8)))
        assert (prepared.original_width, prepared.original_height) == (640, 480)

    def test_preserves_aspect_ratio_when_letterboxing(self, encode_image):
        # A wide image keeps its shape: the padded rows must remain neutral grey.
        prepared = prepare_image(
            encode_image(np.full((200, 800, 3), 10, dtype=np.uint8), fmt="PNG")
        )
        top_row = prepared.rgb[0]
        assert np.allclose(top_row, 128, atol=2)

    def test_float_view_is_normalised(self, encode_image):
        prepared = prepare_image(
            encode_image(np.full((256, 256, 3), 255, dtype=np.uint8), fmt="PNG")
        )
        floats = prepared.as_float01()
        assert floats.dtype == np.float32
        assert 0.0 <= float(floats.min()) and float(floats.max()) <= 1.0

    def test_rejects_target_size_below_minimum(self, encode_image):
        with pytest.raises(ValueError):
            prepare_image(encode_image(np.full((256, 256, 3), 20, dtype=np.uint8)), target_size=16)
