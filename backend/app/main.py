"""Application factory and composition root.

Every singleton the app needs is constructed here, once, and hung off
``app.state``. Nothing else in the codebase reaches for global state.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from nutrilens_ml import DensityEngine, NutritionEstimator, load_catalog
from nutrilens_ml.inference.factory import EngineConfig
from nutrilens_ml.pipeline import MealAnalysisPipeline

from . import health
from .api.error_handlers import register_error_handlers
from .api.middleware import (
    RateLimitMiddleware,
    RequestContextMiddleware,
    SecurityHeadersMiddleware,
)
from .api.v1 import router as v1_router
from .core.config import Settings, get_settings
from .core.database import create_db_engine, create_session_factory
from .core.logging import configure_logging, get_logger
from .domain.chrononutrition import ChrononutritionEngine
from .services.rate_limit import InMemoryRateLimiter, RedisRateLimiter
from .services.storage import LocalObjectStorage

logger = get_logger("nutrilens.startup")

API_DESCRIPTION = """
NutriLens estimates what and when a person eats from a meal photograph.

**Everything this API returns about quantity is an estimate.** Volume is
inferred from a single uncalibrated image, which cannot recover depth, and mass
follows from volume through reference densities. Confidence values accompany
every estimate and clients are expected to display them. Nothing here is a
clinical measurement, and nothing here is medical advice.
"""


def create_app(settings: Settings | None = None) -> FastAPI:
    resolved = settings or get_settings()
    configure_logging(level=resolved.log_level, json_output=resolved.log_json)

    @asynccontextmanager
    async def lifespan(application: FastAPI) -> AsyncIterator[None]:
        logger.info(
            "application_starting",
            environment=str(resolved.environment),
            ml_engine=resolved.ml_engine,
        )
        # Pay the model-loading cost once, at startup, not on a user's request.
        application.state.analysis_pipeline.warmup()
        yield
        application.state.db_engine.dispose()
        logger.info("application_stopped")

    app = FastAPI(
        title=resolved.app_name,
        version="0.1.0",
        description=API_DESCRIPTION,
        docs_url="/docs" if not resolved.environment.is_production_like else None,
        redoc_url="/redoc" if not resolved.environment.is_production_like else None,
        openapi_url="/openapi.json",
        lifespan=lifespan,
    )

    _wire_state(app, resolved)
    _install_middleware(app, resolved)
    register_error_handlers(app)

    app.include_router(health.router)
    app.include_router(v1_router, prefix=resolved.api_v1_prefix)
    return app


def _wire_state(app: FastAPI, settings: Settings) -> None:
    engine = create_db_engine(settings)
    catalog = load_catalog()

    app.state.settings = settings
    app.state.db_engine = engine
    app.state.session_factory = create_session_factory(engine)
    app.state.object_storage = LocalObjectStorage(settings.storage_local_path)
    app.state.chrononutrition_engine = ChrononutritionEngine()
    app.state.density_engine = DensityEngine(catalog)
    app.state.nutrition_estimator = NutritionEstimator(catalog)
    app.state.analysis_pipeline = MealAnalysisPipeline.from_config(
        EngineConfig(
            engine=settings.ml_engine,
            onnx_model_path=settings.ml_onnx_model_path,
            onnx_label_map_path=settings.ml_onnx_label_map_path,
        )
    )
    app.state.redis_client, app.state.rate_limiter = _build_rate_limiter(settings)


def _build_rate_limiter(settings: Settings):
    if not settings.redis_url:
        return None, InMemoryRateLimiter()
    try:
        import redis

        client = redis.Redis.from_url(settings.redis_url, socket_connect_timeout=2)
        client.ping()
        logger.info("rate_limiter_backend_selected", backend="redis")
        return client, RedisRateLimiter(client)
    except Exception as exc:  # noqa: BLE001
        # Starting without rate limiting would be worse than starting with a
        # per-process one, so degrade loudly rather than fail.
        logger.warning(
            "redis_unavailable_using_in_memory_rate_limiter",
            error_type=type(exc).__name__,
        )
        return None, InMemoryRateLimiter()


def _install_middleware(app: FastAPI, settings: Settings) -> None:
    # Starlette applies middleware in reverse registration order, so the
    # request-id layer is added last to end up outermost.
    if settings.cors_allow_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=settings.cors_allow_origins,
            allow_credentials=True,
            allow_methods=["GET", "POST", "PATCH", "DELETE", "OPTIONS"],
            allow_headers=["Authorization", "Content-Type", "X-Request-ID"],
            max_age=600,
        )

    app.add_middleware(SecurityHeadersMiddleware)
    app.add_middleware(
        RateLimitMiddleware, limiter=app.state.rate_limiter, settings=settings
    )
    app.add_middleware(RequestContextMiddleware)


app = create_app()
