"""One error shape for the whole API.

Clients get a stable machine-readable ``code``, a message safe to display, and
the request id for support. Stack traces and internal messages never cross the
boundary.
"""

from __future__ import annotations

from typing import Any


class ErrorCode:
    """Canonical error codes. Clients switch on these, never on messages."""

    VALIDATION_FAILED = "VALIDATION_FAILED"
    INVALID_CREDENTIALS = "INVALID_CREDENTIALS"
    TOKEN_EXPIRED = "TOKEN_EXPIRED"
    TOKEN_INVALID = "TOKEN_INVALID"
    NOT_AUTHENTICATED = "NOT_AUTHENTICATED"
    FORBIDDEN = "FORBIDDEN"
    NOT_FOUND = "NOT_FOUND"
    CONFLICT = "CONFLICT"
    EMAIL_ALREADY_REGISTERED = "EMAIL_ALREADY_REGISTERED"
    WEAK_PASSWORD = "WEAK_PASSWORD"
    INVALID_IMAGE = "INVALID_IMAGE"
    IMAGE_TOO_LARGE = "IMAGE_TOO_LARGE"
    UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE"
    ANALYSIS_FAILED = "ANALYSIS_FAILED"
    RATE_LIMITED = "RATE_LIMITED"
    INTERNAL_ERROR = "INTERNAL_ERROR"
    SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE"


class AppError(Exception):
    """Base class for every error the API deliberately returns.

    Anything that is not an ``AppError`` is a bug, and is reported to the
    client as a generic internal error with nothing leaked.
    """

    status_code: int = 500
    code: str = ErrorCode.INTERNAL_ERROR

    def __init__(
        self,
        message: str,
        *,
        code: str | None = None,
        status_code: int | None = None,
        details: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        if code is not None:
            self.code = code
        if status_code is not None:
            self.status_code = status_code
        self.details = details or {}


class ValidationError(AppError):
    status_code = 422
    code = ErrorCode.VALIDATION_FAILED


class AuthenticationError(AppError):
    status_code = 401
    code = ErrorCode.NOT_AUTHENTICATED


class InvalidCredentialsError(AuthenticationError):
    code = ErrorCode.INVALID_CREDENTIALS

    def __init__(self, message: str = "Incorrect email or password.") -> None:
        super().__init__(message)


class TokenError(AuthenticationError):
    code = ErrorCode.TOKEN_INVALID


class PermissionDeniedError(AppError):
    status_code = 403
    code = ErrorCode.FORBIDDEN


class NotFoundError(AppError):
    status_code = 404
    code = ErrorCode.NOT_FOUND


class ConflictError(AppError):
    status_code = 409
    code = ErrorCode.CONFLICT


class EmailAlreadyRegisteredError(ConflictError):
    code = ErrorCode.EMAIL_ALREADY_REGISTERED

    def __init__(self) -> None:
        super().__init__("An account with that email address already exists.")


class WeakPasswordError(ValidationError):
    code = ErrorCode.WEAK_PASSWORD


class UnsupportedMediaTypeError(AppError):
    status_code = 415
    code = ErrorCode.UNSUPPORTED_MEDIA_TYPE


class PayloadTooLargeError(AppError):
    status_code = 413
    code = ErrorCode.IMAGE_TOO_LARGE


class RateLimitedError(AppError):
    status_code = 429
    code = ErrorCode.RATE_LIMITED

    def __init__(self, retry_after_seconds: int) -> None:
        super().__init__(
            "Too many requests. Please try again shortly.",
            details={"retry_after_seconds": retry_after_seconds},
        )
        self.retry_after_seconds = retry_after_seconds


class AnalysisFailedError(AppError):
    status_code = 422
    code = ErrorCode.ANALYSIS_FAILED


class ServiceUnavailableError(AppError):
    status_code = 503
    code = ErrorCode.SERVICE_UNAVAILABLE


def error_payload(
    code: str, message: str, request_id: str, details: dict[str, Any] | None = None
) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "error": {"code": code, "message": message, "request_id": request_id}
    }
    if details:
        payload["error"]["details"] = details
    return payload
