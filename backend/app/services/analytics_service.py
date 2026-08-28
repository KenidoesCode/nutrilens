"""Chrononutrition analytics over stored meals.

Thin by design: it fetches meals and hands them to the pure domain engine, so
all the timing rules stay testable without a database.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta

from ..domain.chrononutrition import (
    ChrononutritionEngine,
    DailyEatingPattern,
    MealTimestamp,
    WeeklyEatingPattern,
)
from ..models.user import User
from ..repositories.meal import MealRepository

MAX_ANALYTICS_DAYS = 90


@dataclass(frozen=True, slots=True)
class NutritionTotals:
    energy_kcal: float
    protein_g: float
    carbohydrate_g: float
    fat_g: float
    mass_g: float


class AnalyticsService:
    def __init__(self, meals: MealRepository, engine: ChrononutritionEngine) -> None:
        self._meals = meals
        self._engine = engine

    def today(self, user: User, now: datetime | None = None) -> DailyEatingPattern:
        start, end = self._engine.current_day_bounds(user.timezone, now)
        meals = self._meals.list_in_range(user.id, start, end)
        logical_day = start.astimezone(
            _zone(user.timezone)
        ).date()
        return self._engine.daily_pattern(
            logical_day, [_to_timestamp(meal) for meal in meals], user.timezone
        )

    def range(
        self, user: User, start_day: date, end_day: date
    ) -> WeeklyEatingPattern:
        if end_day < start_day:
            raise ValueError("end_day must not precede start_day")
        span = (end_day - start_day).days + 1
        if span > MAX_ANALYTICS_DAYS:
            raise ValueError(f"The range must not exceed {MAX_ANALYTICS_DAYS} days")

        start_utc, end_utc = self._utc_bounds(user.timezone, start_day, end_day)
        meals = self._meals.list_in_range(user.id, start_utc, end_utc)
        return self._engine.weekly_pattern(
            [_to_timestamp(meal) for meal in meals], user.timezone, start_day, end_day
        )

    def nutrition_totals(
        self, user: User, start_day: date, end_day: date
    ) -> NutritionTotals:
        """Summed estimates over a range.

        Items with unknown nutrition contribute nothing rather than zero-like
        guesses; the mass total still counts them, so the two figures can
        legitimately disagree.
        """
        start_utc, end_utc = self._utc_bounds(user.timezone, start_day, end_day)
        meals = self._meals.list_in_range(user.id, start_utc, end_utc)

        totals = {"energy_kcal": 0.0, "protein_g": 0.0, "carbohydrate_g": 0.0,
                  "fat_g": 0.0, "mass_g": 0.0}
        for meal in meals:
            for item in meal.items:
                if item.is_deleted:
                    continue
                totals["mass_g"] += item.estimated_mass_g
                totals["energy_kcal"] += item.energy_kcal or 0.0
                totals["protein_g"] += item.protein_g or 0.0
                totals["carbohydrate_g"] += item.carbohydrate_g or 0.0
                totals["fat_g"] += item.fat_g or 0.0

        return NutritionTotals(**{key: round(value, 2) for key, value in totals.items()})

    def _utc_bounds(
        self, timezone: str, start_day: date, end_day: date
    ) -> tuple[datetime, datetime]:
        """UTC bounds covering the logical days ``[start_day, end_day]``.

        The window starts at the day boundary of ``start_day`` and ends at the
        boundary that closes ``end_day``, so late-night meals land on the day
        the user experienced them.
        """
        zone = _zone(timezone)
        boundary = self._engine.day_boundary_hour
        start_local = datetime(
            start_day.year, start_day.month, start_day.day, boundary, tzinfo=zone
        )
        end_local = datetime(
            end_day.year, end_day.month, end_day.day, boundary, tzinfo=zone
        ) + timedelta(days=1)
        return start_local.astimezone(UTC), end_local.astimezone(UTC)


def _zone(timezone: str):
    from ..domain.chrononutrition import resolve_timezone

    return resolve_timezone(timezone)


def _to_timestamp(meal) -> MealTimestamp:
    return MealTimestamp(
        consumed_at=meal.consumed_at,
        timezone=meal.timezone,
        meal_type=str(meal.meal_type),
    )
