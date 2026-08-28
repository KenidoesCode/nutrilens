"""Request-scoped correlation id.

Every log line and every error response carries the same id, so a user report
("request 6f2a...") maps directly onto the server-side trace.
"""

from __future__ import annotations

import uuid
from contextvars import ContextVar

_request_id: ContextVar[str] = ContextVar("request_id", default="")

REQUEST_ID_HEADER = "X-Request-ID"
MAX_CLIENT_REQUEST_ID_LENGTH = 64


def new_request_id() -> str:
    return uuid.uuid4().hex


def sanitize_client_request_id(value: str | None) -> str | None:
    """Accept a client-supplied id only if it is short and alphanumeric.

    Echoing arbitrary client input into logs and headers invites log injection
    and header splitting, so anything unexpected is discarded silently.
    """
    if not value:
        return None
    candidate = value.strip()
    if not candidate or len(candidate) > MAX_CLIENT_REQUEST_ID_LENGTH:
        return None
    if not all(char.isalnum() or char in "-_" for char in candidate):
        return None
    return candidate


def set_request_id(value: str) -> None:
    _request_id.set(value)


def get_request_id() -> str:
    return _request_id.get()
