"""Synchronisation contracts.

Push is a batch of client-generated operations, each with its own idempotency
key so partial failure is safe to retry. Pull is a cursor over server changes.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from .meal import MealResponse

MAX_BATCH_SIZE = 50


class SyncPushOperation(BaseModel):
    """One queued client operation.

    ``meal`` is intentionally an unvalidated mapping at this level and is
    validated against :class:`~app.schemas.meal.MealCreateRequest` *per
    operation* inside the handler. Validating it here would make one malformed
    meal reject the entire batch with 422, which is precisely the failure the
    per-operation idempotency keys exist to prevent: a device holding one bad
    record could then never sync any of its good ones.
    """

    model_config = ConfigDict(str_strip_whitespace=True)

    idempotency_key: str = Field(min_length=8, max_length=80)
    operation: str = Field(pattern="^(create_meal|delete_meal)$")
    meal: dict[str, Any] | None = Field(
        default=None,
        description="A MealCreateRequest body. Validated per operation, not batch-wide.",
        json_schema_extra={"$ref": "#/components/schemas/MealCreateRequest"},
    )
    meal_id: str | None = Field(default=None, max_length=36)


class SyncPushRequest(BaseModel):
    operations: list[SyncPushOperation] = Field(min_length=1, max_length=MAX_BATCH_SIZE)


class SyncOperationResult(BaseModel):
    idempotency_key: str
    status: str = Field(description="applied | replayed | failed")
    entity_id: str | None = None
    meal: MealResponse | None = Field(
        default=None,
        description=(
            "The stored meal, for create operations. Returned so a client can "
            "adopt the server's item ids -- without them it cannot address an "
            "individual item to correct it later."
        ),
    )
    error_code: str | None = None
    error_message: str | None = None


class SyncPushResponse(BaseModel):
    results: list[SyncOperationResult]
    applied: int
    replayed: int
    failed: int
    server_time: datetime


class SyncPullResponse(BaseModel):
    meals: list[MealResponse]
    deleted_meal_ids: list[str]
    next_cursor: datetime | None = Field(
        default=None,
        description="Pass back as 'since' to continue. Null when fully caught up.",
    )
    has_more: bool
    server_time: datetime
