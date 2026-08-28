"""Test fixtures.

The suite runs against a real SQLite database created from the ORM metadata,
with foreign keys enforced, so constraint violations surface in tests rather
than in production.
"""

from __future__ import annotations

import io
import os
import uuid
from collections.abc import Iterator

import numpy as np
import pytest
from fastapi.testclient import TestClient
from PIL import Image

os.environ.setdefault("NUTRILENS_ENVIRONMENT", "test")
os.environ.setdefault("NUTRILENS_JWT_SECRET", "test-secret-key-that-is-long-enough-32+")
os.environ.setdefault("NUTRILENS_DATABASE_URL", "sqlite+pysqlite:///:memory:")
os.environ.setdefault("NUTRILENS_LOG_JSON", "false")
os.environ.setdefault("NUTRILENS_LOG_LEVEL", "WARNING")

from app.core.config import Environment, Settings  # noqa: E402
from app.core.database import Base, create_session_factory  # noqa: E402
from app.main import create_app  # noqa: E402
from app.repositories.food import FoodRepository  # noqa: E402
from app.services.food_service import FoodService  # noqa: E402


@pytest.fixture
def settings(tmp_path) -> Settings:
    return Settings(
        environment=Environment.TEST,
        jwt_secret="test-secret-key-that-is-long-enough-32+",
        database_url="sqlite+pysqlite:///:memory:",
        storage_local_path=tmp_path / "images",
        log_json=False,
        log_level="WARNING",
        # Off by default so ordinary tests are not throttled; the rate-limit
        # tests build their own settings with it enabled.
        rate_limit_enabled=False,
    )


@pytest.fixture
def app(settings: Settings):
    application = create_app(settings)
    Base.metadata.create_all(application.state.db_engine)
    _seed_catalog(application)
    yield application
    Base.metadata.drop_all(application.state.db_engine)
    application.state.db_engine.dispose()


def _seed_catalog(application) -> None:
    factory = create_session_factory(application.state.db_engine)
    session = factory()
    try:
        FoodService(FoodRepository(session)).seed_from_dataset()
        session.commit()
    finally:
        session.close()


@pytest.fixture
def client(app) -> Iterator[TestClient]:
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture
def session_factory(app):
    return create_session_factory(app.state.db_engine)


@pytest.fixture
def db_session(session_factory) -> Iterator:
    session = session_factory()
    try:
        yield session
    finally:
        session.rollback()
        session.close()


# --- helpers -------------------------------------------------------------


def unique_email() -> str:
    return f"user-{uuid.uuid4().hex[:12]}@example.com"


VALID_PASSWORD = "correct-horse-battery-1"


@pytest.fixture
def registered_user(client: TestClient) -> dict:
    """A registered account plus its live token pair."""
    email = unique_email()
    response = client.post(
        "/api/v1/auth/register",
        json={
            "email": email,
            "password": VALID_PASSWORD,
            "display_name": "Test Person",
            "timezone": "Asia/Kolkata",
            "locale": "en",
        },
    )
    assert response.status_code == 201, response.text
    tokens = response.json()
    return {"email": email, "password": VALID_PASSWORD, **tokens}


@pytest.fixture
def auth_headers(registered_user: dict) -> dict[str, str]:
    return {"Authorization": f"Bearer {registered_user['access_token']}"}


def meal_photo_bytes() -> bytes:
    """A synthetic plate with three visually distinct dishes."""
    image = np.full((640, 640, 3), 236, dtype=np.uint8)
    image[180:340, 140:300] = (246, 242, 228)
    image[180:340, 330:490] = (198, 148, 42)
    image[380:480, 220:420] = (62, 122, 52)
    buffer = io.BytesIO()
    Image.fromarray(image).save(buffer, format="JPEG", quality=92)
    return buffer.getvalue()


@pytest.fixture
def meal_photo() -> bytes:
    return meal_photo_bytes()


@pytest.fixture
def new_email():
    """Factory for a fresh, unused address. A fixture so no test imports conftest."""
    return unique_email


@pytest.fixture
def password() -> str:
    return VALID_PASSWORD


@pytest.fixture
def make_meal():
    """Factory for a valid meal-create body, with per-test overrides."""
    return meal_payload


def meal_payload(**overrides) -> dict:
    payload = {
        "consumed_at": "2026-05-01T08:42:00+05:30",
        "timezone": "Asia/Kolkata",
        "meal_type": "breakfast",
        "items": [
            {
                "display_name": "Rice",
                "category": "solid",
                "estimated_volume_ml": 180.0,
                "recognition_confidence": 0.62,
                "portion_confidence": 0.65,
                "portion_method": "reference-object",
                "food_key": "rice",
            }
        ],
    }
    payload.update(overrides)
    return payload
