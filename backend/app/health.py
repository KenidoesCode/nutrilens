"""Liveness and readiness.

They answer different questions and must not be conflated: ``/health`` says the
process is alive (restart me if not), ``/ready`` says it can serve traffic
(route to me if so). A database outage makes a pod unready, not dead.
"""

from __future__ import annotations

import time
from typing import Any

from fastapi import APIRouter, Request, Response, status
from sqlalchemy import text

from .core.logging import get_logger

logger = get_logger("nutrilens.health")
router = APIRouter(tags=["operations"])

DATABASE_CHECK_TIMEOUT_MS = 2000


@router.get("/health", summary="Liveness probe")
def health() -> dict[str, str]:
    return {"status": "ok"}


@router.get(
    "/ready",
    summary="Readiness probe",
    description="Reports 503 when a dependency the API needs to serve traffic is down.",
)
def ready(request: Request, response: Response) -> dict[str, Any]:
    checks: dict[str, Any] = {
        "database": _check_database(request),
        "ml_engine": _check_ml_engine(request),
    }
    if request.app.state.redis_client is not None:
        checks["redis"] = _check_redis(request)

    # Redis backs rate limiting only, and that limiter fails open, so a Redis
    # outage must not take the API out of rotation.
    blocking = {"database", "ml_engine"}
    healthy = all(checks[name]["status"] == "ok" for name in blocking if name in checks)

    response.status_code = (
        status.HTTP_200_OK if healthy else status.HTTP_503_SERVICE_UNAVAILABLE
    )
    return {"status": "ready" if healthy else "not_ready", "checks": checks}


def _check_database(request: Request) -> dict[str, Any]:
    started = time.perf_counter()
    try:
        with request.app.state.db_engine.connect() as connection:
            connection.execute(text("SELECT 1"))
        return {"status": "ok", "latency_ms": _elapsed_ms(started)}
    except Exception as exc:  # noqa: BLE001 - the reason is logged, not returned
        logger.error("database_health_check_failed", error_type=type(exc).__name__)
        return {"status": "error", "latency_ms": _elapsed_ms(started)}


def _check_redis(request: Request) -> dict[str, Any]:
    started = time.perf_counter()
    try:
        request.app.state.redis_client.ping()
        return {"status": "ok", "latency_ms": _elapsed_ms(started)}
    except Exception as exc:  # noqa: BLE001
        logger.warning("redis_health_check_failed", error_type=type(exc).__name__)
        return {"status": "error", "latency_ms": _elapsed_ms(started)}


def _check_ml_engine(request: Request) -> dict[str, Any]:
    pipeline = getattr(request.app.state, "analysis_pipeline", None)
    if pipeline is None:
        return {"status": "error"}
    return {
        "status": "ok",
        "engine": pipeline.engine_name,
        "model_version": pipeline.model_version,
    }


def _elapsed_ms(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)
