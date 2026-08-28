"""Meal CRUD and item corrections."""

from __future__ import annotations

import uuid
from datetime import datetime

from fastapi import APIRouter, Depends, Query, Response, status

from ...core.request_context import get_request_id
from ...models.user import User
from ...schemas.common import ErrorResponse, Page, PageMeta
from ...schemas.meal import (
    MealCreateRequest,
    MealItemResponse,
    MealResponse,
    PortionCorrectionRequest,
    RenameItemRequest,
)
from ...services.analysis_service import AnalysisService
from ...services.meal_service import MealInput, MealItemInput, MealService
from ..dependencies import get_analysis_service, get_current_user, get_meal_service

router = APIRouter(
    prefix="/meals",
    tags=["meals"],
    responses={401: {"model": ErrorResponse}, 404: {"model": ErrorResponse}},
)


@router.post(
    "",
    response_model=MealResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Log a meal",
    description=(
        "Send an `idempotency_key` so a retry after a lost response cannot "
        "create a second meal. A replayed key returns the original meal with "
        "200 rather than 201."
    ),
)
def create_meal(
    payload: MealCreateRequest,
    response: Response,
    user: User = Depends(get_current_user),
    meals: MealService = Depends(get_meal_service),
    analysis: AnalysisService = Depends(get_analysis_service),
) -> MealResponse:
    result = meals.create(
        user_id=user.id,
        payload=_to_domain_input(payload),
        request_id=get_request_id(),
    )
    if result.was_replay:
        response.status_code = status.HTTP_200_OK
    elif payload.prediction_id is not None:
        analysis.attach_prediction_to_meal(payload.prediction_id, result.meal.id)
    return MealResponse.from_model(result.meal)


@router.get(
    "",
    response_model=Page[MealResponse],
    summary="List meals, newest first",
)
def list_meals(
    user: User = Depends(get_current_user),
    meals: MealService = Depends(get_meal_service),
    limit: int = Query(default=20, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
    start: datetime | None = Query(default=None, description="Inclusive UTC lower bound."),
    end: datetime | None = Query(default=None, description="Exclusive UTC upper bound."),
) -> Page[MealResponse]:
    page, total = meals.list(
        user.id, limit=limit, offset=offset, start=start, end=end
    )
    return Page[MealResponse](
        items=[MealResponse.from_model(meal) for meal in page],
        meta=PageMeta(
            total=total,
            limit=limit,
            offset=offset,
            has_more=offset + len(page) < total,
        ),
    )


@router.get("/{meal_id}", response_model=MealResponse, summary="Fetch one meal")
def get_meal(
    meal_id: uuid.UUID,
    user: User = Depends(get_current_user),
    meals: MealService = Depends(get_meal_service),
) -> MealResponse:
    return MealResponse.from_model(meals.get(user.id, meal_id))


@router.delete(
    "/{meal_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="Delete a meal",
)
def delete_meal(
    meal_id: uuid.UUID,
    user: User = Depends(get_current_user),
    meals: MealService = Depends(get_meal_service),
) -> Response:
    meals.delete(user.id, meal_id, request_id=get_request_id())
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.patch(
    "/items/{item_id}/portion",
    response_model=MealItemResponse,
    summary="Correct an estimated portion",
    description="Recomputes mass and nutrition from the corrected volume.",
)
def correct_portion(
    item_id: uuid.UUID,
    payload: PortionCorrectionRequest,
    user: User = Depends(get_current_user),
    meals: MealService = Depends(get_meal_service),
) -> MealItemResponse:
    item = meals.correct_item_portion(
        user_id=user.id,
        item_id=item_id,
        corrected_volume_ml=payload.corrected_volume_ml,
        request_id=get_request_id(),
    )
    return MealItemResponse.model_validate(item)


@router.patch(
    "/items/{item_id}/name",
    response_model=MealItemResponse,
    summary="Correct a misidentified food",
    description="Re-resolves density and nutrition for the corrected food.",
)
def rename_item(
    item_id: uuid.UUID,
    payload: RenameItemRequest,
    user: User = Depends(get_current_user),
    meals: MealService = Depends(get_meal_service),
) -> MealItemResponse:
    item = meals.rename_item(
        user_id=user.id, item_id=item_id, display_name=payload.display_name
    )
    return MealItemResponse.model_validate(item)


@router.delete(
    "/items/{item_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="Remove a food from a meal",
)
def remove_item(
    item_id: uuid.UUID,
    user: User = Depends(get_current_user),
    meals: MealService = Depends(get_meal_service),
) -> Response:
    meals.remove_item(user_id=user.id, item_id=item_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


def _to_domain_input(payload: MealCreateRequest) -> MealInput:
    return MealInput(
        consumed_at=payload.consumed_at,
        timezone=payload.timezone,
        meal_type=payload.meal_type,
        notes=payload.notes,
        idempotency_key=payload.idempotency_key,
        client_recorded_at=payload.client_recorded_at,
        items=[
            MealItemInput(
                display_name=item.display_name,
                category=item.category,
                estimated_volume_ml=item.estimated_volume_ml,
                recognition_confidence=item.recognition_confidence,
                portion_confidence=item.portion_confidence,
                portion_method=item.portion_method,
                food_key=item.food_key,
                was_user_corrected=item.was_user_corrected,
                original_display_name=item.original_display_name,
                portion_assumptions=item.portion_assumptions,
            )
            for item in payload.items
        ],
    )
