"""Synchronisation endpoints for the offline-first client.

Push applies a batch of client operations; pull returns everything that
changed server-side since a cursor. Both are safe to retry: push because every
operation carries an idempotency key, pull because it is a read.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime

from fastapi import APIRouter, Depends, Query
from pydantic import ValidationError as PydanticValidationError

from ...core.errors import AppError, ValidationError
from ...core.logging import get_logger
from ...core.request_context import get_request_id
from ...models.user import User
from ...repositories.meal import MAX_PAGE_SIZE
from ...repositories.sync import SyncRepository
from ...schemas.common import ErrorResponse
from ...schemas.meal import MealCreateRequest, MealResponse
from ...schemas.sync import (
    SyncOperationResult,
    SyncPullResponse,
    SyncPushOperation,
    SyncPushRequest,
    SyncPushResponse,
)
from ...services.meal_service import MealService
from ..dependencies import get_current_user, get_meal_service, get_sync_repository
from .meals import _to_domain_input

logger = get_logger(__name__)

router = APIRouter(
    prefix="/sync", tags=["sync"], responses={401: {"model": ErrorResponse}}
)


@router.post(
    "/push",
    response_model=SyncPushResponse,
    summary="Apply a batch of queued client operations",
    description=(
        "Each operation carries its own idempotency key and is applied "
        "independently: one bad operation reports `failed` while the rest still "
        "apply, so a client never has to choose between losing a meal and "
        "retrying the whole batch. Replaying an already-applied key returns "
        "`replayed` and changes nothing."
    ),
)
def push(
    payload: SyncPushRequest,
    user: User = Depends(get_current_user),
    meals: MealService = Depends(get_meal_service),
    sync: SyncRepository = Depends(get_sync_repository),
) -> SyncPushResponse:
    results: list[SyncOperationResult] = []

    for operation in payload.operations:
        try:
            results.append(_apply(operation, user, meals))
        except AppError as exc:
            # Recorded, not raised: the rest of the batch must still apply.
            sync.record_failure(
                user_id=user.id,
                idempotency_key=operation.idempotency_key,
                operation=operation.operation,
                entity_type="meal",
                error=exc.message,
            )
            logger.warning(
                "sync_operation_failed",
                operation=operation.operation,
                error_code=exc.code,
            )
            results.append(
                SyncOperationResult(
                    idempotency_key=operation.idempotency_key,
                    status="failed",
                    error_code=exc.code,
                    error_message=exc.message,
                )
            )

    return SyncPushResponse(
        results=results,
        applied=sum(1 for r in results if r.status == "applied"),
        replayed=sum(1 for r in results if r.status == "replayed"),
        failed=sum(1 for r in results if r.status == "failed"),
        server_time=datetime.now(UTC),
    )


@router.get(
    "/pull",
    response_model=SyncPullResponse,
    summary="Fetch server-side changes since a cursor",
    description=(
        "Returns meals whose `updated_at` is newer than `since`, oldest first. "
        "Deletions are reported in `deleted_meal_ids` rather than silently "
        "omitted, so a device can remove its local copy. Continue by passing "
        "`next_cursor` back as `since` until `has_more` is false."
    ),
)
def pull(
    user: User = Depends(get_current_user),
    meals: MealService = Depends(get_meal_service),
    since: datetime | None = Query(
        default=None, description="Exclusive lower bound on updated_at (UTC)."
    ),
    limit: int = Query(default=MAX_PAGE_SIZE, ge=1, le=MAX_PAGE_SIZE),
) -> SyncPullResponse:
    cursor = since or datetime.fromtimestamp(0, tz=UTC)
    if cursor.tzinfo is None:
        raise ValidationError("'since' must include a UTC offset.")

    changed = meals.changed_since(user.id, cursor, limit)
    live = [meal for meal in changed if not meal.is_deleted]
    deleted = [str(meal.id) for meal in changed if meal.is_deleted]

    next_cursor = changed[-1].updated_at if changed else None
    return SyncPullResponse(
        meals=[MealResponse.from_model(meal) for meal in live],
        deleted_meal_ids=deleted,
        next_cursor=next_cursor,
        has_more=len(changed) == limit,
        server_time=datetime.now(UTC),
    )


def _apply(
    operation: SyncPushOperation, user: User, meals: MealService
) -> SyncOperationResult:
    if operation.operation == "create_meal":
        if operation.meal is None:
            raise ValidationError("create_meal requires a 'meal' payload.")
        # Validated here rather than at the batch schema so a single malformed
        # meal fails alone instead of rejecting every other queued operation.
        try:
            meal_request = MealCreateRequest.model_validate(operation.meal)
        except PydanticValidationError as exc:
            raise ValidationError(_first_validation_message(exc)) from exc

        # The batch-level key wins so the ledger has exactly one entry per
        # client operation, whatever the embedded payload happens to carry.
        domain_input = _to_domain_input(meal_request)
        result = meals.create(
            user_id=user.id,
            payload=_with_key(domain_input, operation.idempotency_key),
            request_id=get_request_id(),
        )
        return SyncOperationResult(
            idempotency_key=operation.idempotency_key,
            status="replayed" if result.was_replay else "applied",
            entity_id=str(result.meal.id),
            # The client needs the server's item ids to address an item in a
            # later correction, so the stored meal comes back rather than just
            # its id.
            meal=MealResponse.from_model(result.meal),
        )

    if operation.operation == "delete_meal":
        if not operation.meal_id:
            raise ValidationError("delete_meal requires 'meal_id'.")
        try:
            meal_id = uuid.UUID(operation.meal_id)
        except ValueError as exc:
            raise ValidationError("'meal_id' must be a UUID.") from exc
        meals.delete(user.id, meal_id, request_id=get_request_id())
        return SyncOperationResult(
            idempotency_key=operation.idempotency_key,
            status="applied",
            entity_id=operation.meal_id,
        )

    raise ValidationError(f"Unsupported operation {operation.operation!r}.")


def _first_validation_message(exc: PydanticValidationError) -> str:
    """Name the offending field without echoing its value back to the client."""
    errors = exc.errors()
    if not errors:
        return "The meal payload could not be validated."
    first = errors[0]
    field = ".".join(str(part) for part in first.get("loc", ()) if part != "body")
    return f"Invalid meal payload at {field or 'body'}: {first.get('type', 'invalid')}."


def _with_key(domain_input, key: str):
    from dataclasses import replace

    return replace(domain_input, idempotency_key=key)
