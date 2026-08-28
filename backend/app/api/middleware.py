"""Cross-cutting HTTP concerns: correlation ids, access logs, rate limiting.

Order matters and is set in ``main``: the request-id middleware must be
outermost so every later layer, including the exception handlers, can attach
the same id.
"""

from __future__ import annotations

import time

from fastapi import Request, Response
from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.responses import JSONResponse

from ..core.config import Settings
from ..core.errors import ErrorCode, error_payload
from ..core.logging import get_logger
from ..core.request_context import (
    REQUEST_ID_HEADER,
    get_request_id,
    new_request_id,
    sanitize_client_request_id,
    set_request_id,
)
from ..services.rate_limit import RateLimiter

logger = get_logger("nutrilens.access")

# Paths excluded from rate limiting: a health probe must never be throttled
# into reporting the service as down.
UNLIMITED_PATHS = frozenset({"/health", "/ready", "/metrics"})


class RequestContextMiddleware(BaseHTTPMiddleware):
    """Assigns a correlation id and logs one structured line per request."""

    async def dispatch(
        self, request: Request, call_next: RequestResponseEndpoint
    ) -> Response:
        request_id = (
            sanitize_client_request_id(request.headers.get(REQUEST_ID_HEADER))
            or new_request_id()
        )
        set_request_id(request_id)
        started = time.perf_counter()

        try:
            response = await call_next(request)
        except Exception:
            duration_ms = int((time.perf_counter() - started) * 1000)
            logger.exception(
                "request_failed",
                method=request.method,
                path=request.url.path,
                duration_ms=duration_ms,
                request_id=request_id,
            )
            raise

        duration_ms = int((time.perf_counter() - started) * 1000)
        response.headers[REQUEST_ID_HEADER] = request_id
        logger.info(
            "request_completed",
            method=request.method,
            path=request.url.path,
            status_code=response.status_code,
            duration_ms=duration_ms,
            request_id=request_id,
        )
        return response


class RateLimitMiddleware(BaseHTTPMiddleware):
    """Global fixed-window limit, keyed by client address."""

    def __init__(self, app, limiter: RateLimiter, settings: Settings) -> None:
        super().__init__(app)
        self._limiter = limiter
        self._settings = settings

    async def dispatch(
        self, request: Request, call_next: RequestResponseEndpoint
    ) -> Response:
        if not self._settings.rate_limit_enabled or request.url.path in UNLIMITED_PATHS:
            return await call_next(request)

        client = request.client
        key = f"global:{client.host if client else 'unknown'}"
        result = self._limiter.check(
            key,
            limit=self._settings.rate_limit_requests,
            window_seconds=self._settings.rate_limit_window_seconds,
        )
        if not result.allowed:
            return JSONResponse(
                status_code=429,
                content=error_payload(
                    ErrorCode.RATE_LIMITED,
                    "Too many requests. Please try again shortly.",
                    get_request_id(),
                    {"retry_after_seconds": result.retry_after_seconds},
                ),
                headers={"Retry-After": str(result.retry_after_seconds)},
            )

        response = await call_next(request)
        response.headers["X-RateLimit-Remaining"] = str(result.remaining)
        return response


class SecurityHeadersMiddleware(BaseHTTPMiddleware):
    """Conservative defaults for a JSON API.

    The API serves no HTML, so the content-security policy denies everything;
    that turns any accidental HTML response into an inert document.
    """

    HEADERS = {
        "X-Content-Type-Options": "nosniff",
        "X-Frame-Options": "DENY",
        "Referrer-Policy": "no-referrer",
        "Content-Security-Policy": "default-src 'none'; frame-ancestors 'none'",
        "Cross-Origin-Resource-Policy": "same-origin",
    }

    async def dispatch(
        self, request: Request, call_next: RequestResponseEndpoint
    ) -> Response:
        response = await call_next(request)
        for header, value in self.HEADERS.items():
            response.headers.setdefault(header, value)
        return response
