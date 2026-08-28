"""Application settings.

Every value is read from the environment. Nothing here has a production-safe
default that could be shipped by accident: the JWT secret has no default at
all outside the test/development environment, so a misconfigured deployment
fails at startup rather than silently running with a known key.
"""

from __future__ import annotations

import secrets
from enum import StrEnum
from functools import lru_cache
from pathlib import Path

from pydantic import Field, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Environment(StrEnum):
    DEVELOPMENT = "development"
    TEST = "test"
    STAGING = "staging"
    PRODUCTION = "production"

    @property
    def is_production_like(self) -> bool:
        return self in {Environment.STAGING, Environment.PRODUCTION}


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        env_prefix="NUTRILENS_",
        extra="ignore",
    )

    environment: Environment = Environment.DEVELOPMENT
    app_name: str = "NutriLens API"
    api_v1_prefix: str = "/api/v1"
    debug: bool = False

    # --- Persistence -----------------------------------------------------
    database_url: str = "sqlite+pysqlite:///./nutrilens.db"
    database_pool_size: int = 10
    database_max_overflow: int = 20
    database_echo: bool = False

    redis_url: str | None = None

    # --- Security --------------------------------------------------------
    jwt_secret: str = ""
    jwt_algorithm: str = "HS256"
    access_token_ttl_minutes: int = 30
    refresh_token_ttl_days: int = 30
    password_min_length: int = 10

    cors_allow_origins: list[str] = Field(default_factory=list)

    rate_limit_enabled: bool = True
    rate_limit_requests: int = 60
    rate_limit_window_seconds: int = 60
    auth_rate_limit_requests: int = 10
    auth_rate_limit_window_seconds: int = 300

    # --- Media -----------------------------------------------------------
    storage_backend: str = "local"
    storage_local_path: Path = Path("./var/meal-images")
    storage_s3_bucket: str | None = None
    storage_s3_region: str | None = None
    max_upload_bytes: int = 12 * 1024 * 1024

    # --- ML --------------------------------------------------------------
    ml_engine: str = "auto"
    ml_onnx_model_path: Path | None = None
    ml_onnx_label_map_path: Path | None = None

    # --- Observability ---------------------------------------------------
    log_level: str = "INFO"
    log_json: bool = True

    @field_validator("cors_allow_origins", mode="before")
    @classmethod
    def _split_origins(cls, value: object) -> object:
        if isinstance(value, str):
            return [origin.strip() for origin in value.split(",") if origin.strip()]
        return value

    @field_validator("log_level")
    @classmethod
    def _upper_log_level(cls, value: str) -> str:
        level = value.upper()
        if level not in {"DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"}:
            raise ValueError(f"Unsupported log level {value!r}")
        return level

    @model_validator(mode="after")
    def _validate_secrets(self) -> Settings:
        if not self.jwt_secret:
            if self.environment.is_production_like:
                raise ValueError(
                    "NUTRILENS_JWT_SECRET must be set outside development and test."
                )
            # Ephemeral per-process key: local tokens stop working on restart,
            # which is the correct nudge to configure a real secret.
            object.__setattr__(self, "jwt_secret", secrets.token_urlsafe(48))
        elif len(self.jwt_secret) < 32:
            raise ValueError("NUTRILENS_JWT_SECRET must be at least 32 characters.")

        if self.environment.is_production_like:
            if self.database_url.startswith("sqlite"):
                raise ValueError("SQLite is not supported outside development and test.")
            if self.debug:
                raise ValueError("Debug mode must be disabled outside development.")
        return self

    @property
    def is_sqlite(self) -> bool:
        return self.database_url.startswith("sqlite")


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
