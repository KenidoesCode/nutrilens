"""Profile, data export and account deletion."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Response, status

from ...core.request_context import get_request_id
from ...models.audit import AuditAction
from ...models.user import User
from ...repositories.audit import AuditRepository
from ...repositories.meal import MealRepository
from ...repositories.user import UserRepository
from ...schemas.common import ErrorResponse
from ...schemas.meal import MealResponse
from ...schemas.user import UserResponse, UserUpdateRequest
from ...services.auth_service import validate_locale, validate_timezone
from ..dependencies import (
    get_audit_repository,
    get_current_user,
    get_meal_repository,
    get_user_repository,
)

router = APIRouter(
    prefix="/users",
    tags=["users"],
    responses={401: {"model": ErrorResponse}},
)

EXPORT_PAGE_LIMIT = 100


@router.get("/me", response_model=UserResponse, summary="Current profile")
def read_me(user: User = Depends(get_current_user)) -> UserResponse:
    return UserResponse.model_validate(user)


@router.patch("/me", response_model=UserResponse, summary="Update the profile")
def update_me(
    payload: UserUpdateRequest,
    user: User = Depends(get_current_user),
    users: UserRepository = Depends(get_user_repository),
) -> UserResponse:
    if payload.display_name is not None:
        user.display_name = payload.display_name or None
    if payload.timezone is not None:
        user.timezone = validate_timezone(payload.timezone)
    if payload.locale is not None:
        user.locale = validate_locale(payload.locale)
    users.session.add(user)
    return UserResponse.model_validate(user)


@router.get(
    "/me/export",
    summary="Export every meal record for this account",
    description=(
        "Returns the account's own data as JSON. Images are referenced by id "
        "rather than embedded; fetch them separately."
    ),
)
def export_my_data(
    user: User = Depends(get_current_user),
    meals: MealRepository = Depends(get_meal_repository),
    audit: AuditRepository = Depends(get_audit_repository),
) -> dict[str, Any]:
    exported: list[MealResponse] = []
    offset = 0
    while True:
        page, total = meals.list_page(user.id, limit=EXPORT_PAGE_LIMIT, offset=offset)
        exported.extend(MealResponse.from_model(meal) for meal in page)
        offset += len(page)
        if not page or offset >= total:
            break

    audit.record(
        action=AuditAction.DATA_EXPORTED, user_id=user.id, request_id=get_request_id()
    )
    return {
        "profile": UserResponse.model_validate(user).model_dump(mode="json"),
        "meals": [meal.model_dump(mode="json") for meal in exported],
        "meal_count": len(exported),
    }


@router.delete(
    "/me",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="Delete this account",
    description=(
        "Deactivates the account, revokes every session and marks the account "
        "and its meals as deleted. Records are retained in a soft-deleted state "
        "so an in-flight sync from a device cannot resurrect them; operators "
        "purge them on the retention schedule documented in docs/security.md."
    ),
)
def delete_me(
    user: User = Depends(get_current_user),
    users: UserRepository = Depends(get_user_repository),
    meals: MealRepository = Depends(get_meal_repository),
    audit: AuditRepository = Depends(get_audit_repository),
) -> Response:
    offset = 0
    while True:
        page, total = meals.list_page(user.id, limit=EXPORT_PAGE_LIMIT, offset=offset)
        if not page:
            break
        for meal in page:
            meals.soft_delete(meal)
        # Soft-deleted meals drop out of the query, so the window stays at 0.
        if len(page) >= total:
            break

    users.soft_delete(user)
    audit.record(
        action=AuditAction.ACCOUNT_DELETED, user_id=user.id, request_id=get_request_id()
    )
    return Response(status_code=status.HTTP_204_NO_CONTENT)
