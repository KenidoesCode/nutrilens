"""Shared fixtures. Synthetic images stand in for photographs so the suite is
deterministic and needs no binary assets in source control."""

from __future__ import annotations

import io

import numpy as np
import pytest
from PIL import Image

from nutrilens_ml.catalog import load_catalog
from nutrilens_ml.preprocessing.image import prepare_image


def encode(array: np.ndarray, fmt: str = "JPEG", quality: int = 95) -> bytes:
    buffer = io.BytesIO()
    kwargs = {"quality": quality} if fmt == "JPEG" else {}
    Image.fromarray(array).save(buffer, format=fmt, **kwargs)
    return buffer.getvalue()


def synthetic_plate() -> np.ndarray:
    """A pale plate carrying three visually distinct dishes."""
    image = np.full((640, 640, 3), 236, dtype=np.uint8)
    image[180:340, 140:300] = (246, 242, 228)  # pale, granular-free: rice/yogurt family
    image[180:340, 330:490] = (198, 148, 42)  # amber: dal family
    image[380:480, 220:420] = (62, 122, 52)  # green: leafy greens
    return image


@pytest.fixture(scope="session")
def catalog():
    return load_catalog()


@pytest.fixture
def plate_bytes() -> bytes:
    return encode(synthetic_plate())


@pytest.fixture
def plate_image():
    return prepare_image(encode(synthetic_plate()), declared_mime="image/jpeg")


@pytest.fixture
def encode_image():
    """Encode an array to image bytes. A fixture so no test imports conftest."""
    return encode


@pytest.fixture
def plate_array():
    return synthetic_plate()


@pytest.fixture
def blank_image():
    return prepare_image(
        encode(np.full((320, 320, 3), 250, dtype=np.uint8)), declared_mime="image/jpeg"
    )
