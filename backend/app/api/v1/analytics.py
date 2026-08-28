"""Chrononutrition analytics endpoints."""

from __future__ import annotations

from datetime import date, timedelta

from fastapi import APIRouter, Depends, Query

from ...core.errors import ValidationError
from ...models.user import User
from ...schemas.analytics import (
    DailyPatternResponse,
    NutritionTotalsResponse,
    RangePatternResponse,
)
from ...schemas.common import ErrorResponse
from ...services.analytics_service import MAX_ANALYTICS_DAYS, AnalyticsService
from ..dependencies import get_analytics_service, get_current_user

router = APIRouter(
    prefix="/analytics",
    tags=["analytics"],
    responses={401: {"model": ErrorResponse}, 422: {"model": ErrorResponse}},
)

DEFAULT_RANGE_DAYS = 7


@router.get(
    "/today",
    response_model=DailyPatternResponse,
    summary="Today's eating window",
    description=(
        "Statistics for the user's current logical day, which starts at 04:00 "
        "local time so a late-night meal counts towards the evening it belongs to."
    ),
)
def today(
    user: User = Depends(get_current_user),
    analytics: AnalyticsService = Depends(get_analytics_service),
) -> DailyPatternResponse:
    return DailyPatternResponse.from_domain(analytics.today(user))


@router.get(
    "/range",
    response_model=RangePatternResponse,
    summary="Eating patterns across a date range",
)
def range_patterns(
    user: User = Depends(get_current_user),
    analytics: AnalyticsService = Depends(get_analytics_service),
    start_day: date | None = Query(default=None),
    end_day: date | None = Query(default=None),
) -> RangePatternResponse:
    start, end = _resolve_range(start_day, end_day)
    try:
        return RangePatternResponse.from_domain(analytics.range(user, start, end))
    except ValueError as exc:
        raise ValidationError(str(exc)) from exc


@router.get(
    "/nutrition",
    response_model=NutritionTotalsResponse,
    summary="Estimated nutrition totals across a date range",
)
def nutrition_totals(
    user: User = Depends(get_current_user),
    analytics: AnalyticsService = Depends(get_analytics_service),
    start_day: date | None = Query(default=None),
    end_day: date | None = Query(default=None),
) -> NutritionTotalsResponse:
    start, end = _resolve_range(start_day, end_day)
    totals = analytics.nutrition_totals(user, start, end)
    return NutritionTotalsResponse(
        start_day=start,
        end_day=end,
        energy_kcal=totals.energy_kcal,
        protein_g=totals.protein_g,
        carbohydrate_g=totals.carbohydrate_g,
        fat_g=totals.fat_g,
        mass_g=totals.mass_g,
    )


def _resolve_range(start_day: date | None, end_day: date | None) -> tuple[date, date]:
    resolved_end = end_day or date.today()
    resolved_start = start_day or (resolved_end - timedelta(days=DEFAULT_RANGE_DAYS - 1))
    if resolved_end < resolved_start:
        raise ValidationError("end_day must not precede start_day.")
    if (resolved_end - resolved_start).days + 1 > MAX_ANALYTICS_DAYS:
        raise ValidationError(f"The range must not exceed {MAX_ANALYTICS_DAYS} days.")
    return resolved_start, resolved_end
