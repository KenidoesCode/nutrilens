"""Chrononutrition analytics contracts."""

from __future__ import annotations

from datetime import date, datetime

from pydantic import BaseModel, Field


class DailyPatternResponse(BaseModel):
    day: date
    timezone: str
    meal_count: int
    first_meal_local: datetime | None
    last_meal_local: datetime | None
    eating_window_minutes: int | None
    eating_window_hours: float | None
    fasting_minutes: int | None
    fasting_hours: float | None

    @classmethod
    def from_domain(cls, pattern) -> DailyPatternResponse:
        return cls(
            day=pattern.day,
            timezone=pattern.timezone,
            meal_count=pattern.meal_count,
            first_meal_local=pattern.first_meal_local,
            last_meal_local=pattern.last_meal_local,
            eating_window_minutes=pattern.eating_window_minutes,
            eating_window_hours=pattern.eating_window_hours,
            fasting_minutes=pattern.fasting_minutes,
            fasting_hours=pattern.fasting_hours,
        )


class RangePatternResponse(BaseModel):
    start_day: date
    end_day: date
    timezone: str
    days: list[DailyPatternResponse]
    days_with_meals: int
    total_meals: int
    mean_eating_window_minutes: float | None
    median_eating_window_minutes: float | None
    mean_meals_per_active_day: float | None
    eating_window_consistency: float | None = Field(
        default=None,
        description=(
            "0 to 1, where 1 means the eating window barely varies across days. "
            "Null when fewer than two days have a measurable window."
        ),
    )

    @classmethod
    def from_domain(cls, pattern) -> RangePatternResponse:
        return cls(
            start_day=pattern.start_day,
            end_day=pattern.end_day,
            timezone=pattern.timezone,
            days=[DailyPatternResponse.from_domain(day) for day in pattern.days],
            days_with_meals=pattern.days_with_meals,
            total_meals=pattern.total_meals,
            mean_eating_window_minutes=pattern.mean_eating_window_minutes,
            median_eating_window_minutes=pattern.median_eating_window_minutes,
            mean_meals_per_active_day=pattern.mean_meals_per_active_day,
            eating_window_consistency=pattern.eating_window_consistency,
        )


class NutritionTotalsResponse(BaseModel):
    start_day: date
    end_day: date
    energy_kcal: float
    protein_g: float
    carbohydrate_g: float
    fat_g: float
    mass_g: float
    estimates_are_approximate: bool = True
