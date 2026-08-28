"""Exception handlers.

Every error leaves the API in the same shape. Unexpected exceptions are logged
in full server-side and reduced to a generic message for the client -- no
stack traces, no internal identifiers, no database messages.
"""

from __future__ import annotations

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.responses import JSONResponse

from ..core.errors import AppError, ErrorCode, RateLimitedError, error_payload
from ..core.logging import get_logger
from ..core.request_context import get_request_id

logger = get_logger("nutrilens.errors")

# Starlette raises bare HTTPExceptions for routing-level failures; map the
# common ones onto our vocabulary so clients see one set of codes.
_STATUS_TO_CODE = {
    401: ErrorCode.NOT_AUTHENTICATED,
    403: ErrorCode.FORBIDDEN,
    404: ErrorCode.NOT_FOUND,
    405: ErrorCode.NOT_FOUND,
    409: ErrorCode.CONFLICT,
    413: ErrorCode.IMAGE_TOO_LARGE,
    415: ErrorCode.UNSUPPORTED_MEDIA_TYPE,
    422: ErrorCode.VALIDATION_FAILED,
    429: ErrorCode.RATE_LIMITED,
}


def register_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(AppError)
    async def handle_app_error(_request: Request, exc: AppError) -> JSONResponse:
        headers = {}
        if isinstance(exc, RateLimitedError):
            headers["Retry-After"] = str(exc.retry_after_seconds)
        return JSONResponse(
            status_code=exc.status_code,
            content=error_payload(exc.code, exc.message, get_request_id(), exc.details),
            headers=headers,
        )

    @app.exception_handler(RequestValidationError)
    async def handle_validation_error(
        _request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        return JSONResponse(
            status_code=422,
            content=error_payload(
                ErrorCode.VALIDATION_FAILED,
                "The request could not be validated.",
                get_request_id(),
                {"fields": _summarise_validation_errors(exc)},
            ),
        )

    @app.exception_handler(StarletteHTTPException)
    async def handle_http_exception(
        _request: Request, exc: StarletteHTTPException
    ) -> JSONResponse:
        code = _STATUS_TO_CODE.get(exc.status_code, ErrorCode.INTERNAL_ERROR)
        detail = exc.detail if isinstance(exc.detail, str) else "Request failed."
        return JSONResponse(
            status_code=exc.status_code,
            content=error_payload(code, detail, get_request_id()),
            headers=getattr(exc, "headers", None),
        )

    @app.exception_handler(Exception)
    async def handle_unexpected(_request: Request, exc: Exception) -> JSONResponse:
        logger.exception("unhandled_exception", error_type=type(exc).__name__)
        return JSONResponse(
            status_code=500,
            content=error_payload(
                ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred. Please try again.",
                get_request_id(),
            ),
        )


def _summarise_validation_errors(exc: RequestValidationError) -> list[dict[str, str]]:
    """Report which fields failed and why, without echoing the values back.

    Echoing the input would put passwords and image bytes into error responses
    and, from there, into client logs.
    """
    summary: list[dict[str, str]] = []
    for error in exc.errors()[:20]:
        location = ".".join(str(part) for part in error.get("loc", ()) if part != "body")
        summary.append({"field": location or "body", "reason": str(error.get("type", ""))})
    return summary
