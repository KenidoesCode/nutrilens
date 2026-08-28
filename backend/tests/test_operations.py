"""Health, error contract, rate limiting, storage and security primitives."""

from __future__ import annotations

import uuid

import pytest
from fastapi.testclient import TestClient

from app.core.config import Environment, Settings
from app.core.errors import TokenError, WeakPasswordError
from app.core.logging import REDACTION_PLACEHOLDER, redact_sensitive
from app.core.request_context import sanitize_client_request_id
from app.core.security import (
    TokenType,
    create_token_pair,
    decode_token,
    fingerprint_token_id,
    hash_password,
    validate_password_strength,
    verify_password,
)
from app.main import create_app
from app.services.rate_limit import InMemoryRateLimiter
from app.services.storage import LocalObjectStorage


class TestHealthEndpoints:
    def test_liveness_is_always_ok(self, client):
        assert client.get("/health").json() == {"status": "ok"}

    def test_readiness_checks_dependencies(self, client):
        body = client.get("/ready").json()
        assert body["status"] == "ready"
        assert body["checks"]["database"]["status"] == "ok"
        assert body["checks"]["ml_engine"]["status"] == "ok"

    def test_readiness_names_the_active_engine(self, client):
        checks = client.get("/ready").json()["checks"]
        assert checks["ml_engine"]["engine"]
        assert checks["ml_engine"]["model_version"]

    def test_readiness_reports_503_when_the_database_is_gone(self, app):
        from sqlalchemy import create_engine

        healthy_engine = app.state.db_engine
        broken = create_engine("sqlite+pysqlite:////nonexistent/x.db")
        try:
            with TestClient(app) as probe:
                app.state.db_engine = broken
                response = probe.get("/ready")
                assert response.status_code == 503
                assert response.json()["status"] == "not_ready"
        finally:
            # Restore before teardown; the fixture still has to drop the schema.
            app.state.db_engine = healthy_engine
            broken.dispose()


class TestErrorContract:
    def test_every_error_carries_a_request_id(self, client):
        body = client.get("/api/v1/users/me").json()
        assert body["error"]["request_id"]
        assert body["error"]["code"]
        assert body["error"]["message"]

    def test_the_request_id_is_echoed_in_the_header(self, client):
        response = client.get("/health")
        assert response.headers["X-Request-ID"]

    def test_a_client_supplied_request_id_is_honoured(self, client):
        response = client.get("/health", headers={"X-Request-ID": "trace-abc-123"})
        assert response.headers["X-Request-ID"] == "trace-abc-123"

    def test_a_hostile_request_id_is_discarded(self, client):
        # Echoing arbitrary input into headers and logs invites injection.
        response = client.get("/health", headers={"X-Request-ID": "bad\r\nInjected: 1"})
        assert response.headers["X-Request-ID"] != "bad\r\nInjected: 1"

    @pytest.mark.parametrize("value", ["x" * 200, "has spaces", "semi;colon", "", "new\nline"])
    def test_request_id_sanitiser_rejects_bad_input(self, value):
        assert sanitize_client_request_id(value) is None

    def test_request_id_sanitiser_accepts_safe_input(self):
        assert sanitize_client_request_id("trace-1_A") == "trace-1_A"

    def test_unknown_routes_use_the_error_envelope(self, client):
        response = client.get("/api/v1/does-not-exist")
        assert response.status_code == 404
        assert response.json()["error"]["code"] == "NOT_FOUND"

    def test_validation_errors_name_fields_without_echoing_values(self, client):
        response = client.post(
            "/api/v1/auth/register",
            json={"email": "not-an-email", "password": "s3cr3t-value-here"},
        )
        assert response.status_code == 422
        assert "s3cr3t-value-here" not in response.text

    def test_no_stack_trace_reaches_the_client(self, client):
        response = client.get("/api/v1/meals/not-a-uuid")
        assert "Traceback" not in response.text
        assert "sqlalchemy" not in response.text.lower()


class TestSecurityHeaders:
    def test_sets_conservative_defaults(self, client):
        headers = client.get("/health").headers
        assert headers["X-Content-Type-Options"] == "nosniff"
        assert headers["X-Frame-Options"] == "DENY"
        assert headers["Referrer-Policy"] == "no-referrer"
        assert "default-src 'none'" in headers["Content-Security-Policy"]


class TestPasswordHandling:
    def test_hashes_are_salted(self):
        # Identical passwords must not produce identical hashes.
        assert hash_password("same-password-1") != hash_password("same-password-1")

    def test_verifies_a_correct_password(self):
        assert verify_password("correct-horse-1", hash_password("correct-horse-1"))

    def test_rejects_an_incorrect_password(self):
        assert not verify_password("wrong-horse-1", hash_password("correct-horse-1"))

    def test_a_malformed_hash_returns_false_rather_than_raising(self):
        assert verify_password("anything", "not-a-bcrypt-hash") is False

    def test_rejects_a_password_longer_than_bcrypt_can_hash(self, settings):
        # bcrypt silently truncates at 72 bytes; accepting more would mean the
        # ignored tail gave a false sense of strength.
        with pytest.raises(WeakPasswordError):
            validate_password_strength("a" * 73 + "Bc1", settings)


class TestTokens:
    def test_round_trips_an_access_token(self, settings):
        subject = uuid.uuid4()
        pair = create_token_pair(subject, settings)
        claims = decode_token(pair.access_token, TokenType.ACCESS, settings)
        assert claims.subject == subject
        assert claims.token_type is TokenType.ACCESS

    def test_a_refresh_token_is_rejected_as_an_access_token(self, settings):
        pair = create_token_pair(uuid.uuid4(), settings)
        with pytest.raises(TokenError):
            decode_token(pair.refresh_token, TokenType.ACCESS, settings)

    def test_a_token_from_another_secret_is_rejected(self, settings):
        pair = create_token_pair(uuid.uuid4(), settings)
        other = settings.model_copy(update={"jwt_secret": "a-completely-different-secret-32+"})
        with pytest.raises(TokenError):
            decode_token(pair.access_token, TokenType.ACCESS, other)

    def test_garbage_is_rejected(self, settings):
        with pytest.raises(TokenError):
            decode_token("nonsense", TokenType.ACCESS, settings)

    def test_token_ids_are_stored_only_as_hashes(self):
        # A database dump must not hand over usable session identifiers.
        jti = "session-identifier"
        fingerprint = fingerprint_token_id(jti)
        assert fingerprint != jti
        assert len(fingerprint) == 64
        assert fingerprint == fingerprint_token_id(jti)


class TestSettingsValidation:
    def test_production_requires_a_jwt_secret(self):
        with pytest.raises(ValueError, match="JWT_SECRET"):
            Settings(
                environment=Environment.PRODUCTION,
                jwt_secret="",
                database_url="postgresql+psycopg://u:p@db/nutrilens",
            )

    def test_production_rejects_a_short_secret(self):
        with pytest.raises(ValueError):
            Settings(
                environment=Environment.PRODUCTION,
                jwt_secret="too-short",
                database_url="postgresql+psycopg://u:p@db/nutrilens",
            )

    def test_production_rejects_sqlite(self):
        with pytest.raises(ValueError, match="SQLite"):
            Settings(
                environment=Environment.PRODUCTION,
                jwt_secret="a" * 40,
                database_url="sqlite+pysqlite:///./nutrilens.db",
            )

    def test_production_rejects_debug_mode(self):
        with pytest.raises(ValueError, match="Debug"):
            Settings(
                environment=Environment.PRODUCTION,
                jwt_secret="a" * 40,
                database_url="postgresql+psycopg://u:p@db/nutrilens",
                debug=True,
            )

    def test_development_generates_an_ephemeral_secret(self):
        settings = Settings(environment=Environment.DEVELOPMENT, jwt_secret="")
        assert len(settings.jwt_secret) >= 32

    def test_docs_are_hidden_in_production(self):
        settings = Settings(
            environment=Environment.PRODUCTION,
            jwt_secret="a" * 40,
            database_url="postgresql+psycopg://u:p@db/nutrilens",
        )
        app = create_app(settings)
        assert app.docs_url is None

    def test_cors_origins_parse_from_a_comma_list(self):
        settings = Settings(cors_allow_origins="https://a.example, https://b.example")
        assert settings.cors_allow_origins == ["https://a.example", "https://b.example"]


class TestRateLimiting:
    def test_allows_traffic_under_the_limit(self):
        limiter = InMemoryRateLimiter()
        for _ in range(3):
            assert limiter.check("k", limit=3, window_seconds=60).allowed

    def test_blocks_traffic_over_the_limit(self):
        limiter = InMemoryRateLimiter()
        for _ in range(3):
            limiter.check("k", limit=3, window_seconds=60)
        result = limiter.check("k", limit=3, window_seconds=60)
        assert result.allowed is False
        assert result.retry_after_seconds > 0

    def test_keys_are_independent(self):
        limiter = InMemoryRateLimiter()
        for _ in range(3):
            limiter.check("a", limit=3, window_seconds=60)
        assert limiter.check("b", limit=3, window_seconds=60).allowed

    def test_login_attempts_are_throttled(self, tmp_path):
        settings = Settings(
            environment=Environment.TEST,
            jwt_secret="test-secret-key-that-is-long-enough-32+",
            database_url="sqlite+pysqlite:///:memory:",
            storage_local_path=tmp_path / "images",
            rate_limit_enabled=True,
            auth_rate_limit_requests=3,
            auth_rate_limit_window_seconds=60,
            log_json=False,
        )
        app = create_app(settings)
        from app.core.database import Base

        Base.metadata.create_all(app.state.db_engine)

        with TestClient(app) as throttled:
            payload = {"email": "nobody@example.com", "password": "wrong-password-1"}
            statuses = [
                throttled.post("/api/v1/auth/login", json=payload).status_code for _ in range(5)
            ]
        assert 429 in statuses
        assert statuses.count(401) == 3

    def test_health_checks_are_never_throttled(self, tmp_path):
        settings = Settings(
            environment=Environment.TEST,
            jwt_secret="test-secret-key-that-is-long-enough-32+",
            database_url="sqlite+pysqlite:///:memory:",
            storage_local_path=tmp_path / "images",
            rate_limit_enabled=True,
            rate_limit_requests=2,
            log_json=False,
        )
        app = create_app(settings)
        with TestClient(app) as probe:
            # A throttled probe would report a healthy service as down.
            assert all(probe.get("/health").status_code == 200 for _ in range(6))


class TestLogRedaction:
    def test_redacts_sensitive_keys(self):
        event = {
            "event": "login",
            "password": "hunter2",
            "access_token": "eyJ...",
            "email": "a@b.com",
            "meal_id": "keep-me",
        }
        redacted = redact_sensitive(None, "info", dict(event))
        assert redacted["password"] == REDACTION_PLACEHOLDER
        assert redacted["access_token"] == REDACTION_PLACEHOLDER
        assert redacted["email"] == REDACTION_PLACEHOLDER
        assert redacted["meal_id"] == "keep-me"

    def test_is_case_insensitive(self):
        redacted = redact_sensitive(None, "info", {"Authorization": "Bearer x"})
        assert redacted["Authorization"] == REDACTION_PLACEHOLDER


class TestLocalObjectStorage:
    def test_stores_and_retrieves(self, tmp_path):
        storage = LocalObjectStorage(tmp_path)
        stored = storage.put(b"image-bytes", content_type="image/jpeg", owner_id=uuid.uuid4())
        assert storage.get(stored.key) == b"image-bytes"
        assert storage.exists(stored.key)

    def test_identical_bytes_land_on_one_key(self, tmp_path):
        storage = LocalObjectStorage(tmp_path)
        owner = uuid.uuid4()
        first = storage.put(b"same", content_type="image/jpeg", owner_id=owner)
        second = storage.put(b"same", content_type="image/jpeg", owner_id=owner)
        assert first.key == second.key

    def test_different_owners_get_different_keys(self, tmp_path):
        storage = LocalObjectStorage(tmp_path)
        first = storage.put(b"same", content_type="image/jpeg", owner_id=uuid.uuid4())
        second = storage.put(b"same", content_type="image/jpeg", owner_id=uuid.uuid4())
        assert first.key != second.key

    def test_records_the_content_hash(self, tmp_path):
        import hashlib

        storage = LocalObjectStorage(tmp_path)
        stored = storage.put(b"payload", content_type="image/png", owner_id=uuid.uuid4())
        assert stored.content_sha256 == hashlib.sha256(b"payload").hexdigest()

    def test_rejects_a_key_escaping_the_root(self, tmp_path):
        storage = LocalObjectStorage(tmp_path / "root")
        with pytest.raises(ValueError):
            storage.get("../../etc/passwd")

    def test_delete_is_idempotent(self, tmp_path):
        storage = LocalObjectStorage(tmp_path)
        stored = storage.put(b"x", content_type="image/jpeg", owner_id=uuid.uuid4())
        storage.delete(stored.key)
        storage.delete(stored.key)
        assert not storage.exists(stored.key)

    def test_leaves_no_partial_files_behind(self, tmp_path):
        storage = LocalObjectStorage(tmp_path)
        storage.put(b"x" * 1000, content_type="image/jpeg", owner_id=uuid.uuid4())
        assert list(tmp_path.rglob("*.partial")) == []
