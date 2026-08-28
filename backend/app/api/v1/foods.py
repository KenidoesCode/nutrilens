"""Food catalog lookup."""

from __future__ import annotations

from fastapi import APIRouter, Depends, Query

from ...models.user import User
from ...schemas.common import ErrorResponse
from ...schemas.food import FoodResponse
from ...services.food_service import FoodService
from ..dependencies import get_current_user, get_food_service

router = APIRouter(
    prefix="/foods", tags=["foods"], responses={401: {"model": ErrorResponse}}
)


@router.get(
    "",
    response_model=list[FoodResponse],
    summary="Search the food catalog",
    description=(
        "Used by the client when a user corrects a misidentified food. Returns "
        "each food's density records with their provenance and confidence."
    ),
)
def search_foods(
    _user: User = Depends(get_current_user),
    foods: FoodService = Depends(get_food_service),
    q: str | None = Query(default=None, max_length=80, description="Substring filter."),
    limit: int = Query(default=50, ge=1, le=200),
) -> list[FoodResponse]:
    return [FoodResponse.model_validate(entry) for entry in foods.search(q, limit)]
