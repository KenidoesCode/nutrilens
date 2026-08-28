"""Chrononutrition: when a person eats, not what.

The whole engine is pure. It takes timestamps and returns statistics, so every
rule here is directly testable and none of it depends on the database.

Two decisions shape everything below.

**Local time is the truth.** An eating window is a lived, local-clock concept.
Meals are stored in UTC with the zone they were recorded in, and every
calculation converts back to local time before asking which day a meal
belongs to. Without this, a traveller's day boundaries move under them.

**A "day" is not midnight-to-midnight.** A meal at 00:30 belongs to the
evening that preceded it, not to a new day that has barely started. The day
boundary is therefore configurable and defaults to 04:00 local time.

This module reports observations. It does not diagnose, advise or prescribe.
"""

from __future__ import annotations

import statistics
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, time, timedelta
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

DEFAULT_DAY_BOUNDARY_HOUR = 4
MAX_REASONABLE_EATING_WINDOW_HOURS = 24.0


class InvalidTimezoneError(ValueError):
    """Raised when a stored timezone name cannot be resolved."""


def resolve_timezone(name: str) -> ZoneInfo:
    try:
        return ZoneInfo(name)
    except (ZoneInfoNotFoundError, ValueError, KeyError) as exc:
        raise InvalidTimezoneError(f"Unknown timezone {name!r}") from exc


@dataclass(frozen=True, slots=True)
class MealTimestamp:
    """The minimum a meal needs to contribute to a timing analysis."""

    consumed_at: datetime
    timezone: str
    meal_type: str

    def __post_init__(self) -> None:
        if self.consumed_at.tzinfo is None:
            raise ValueError("consumed_at must be timezone-aware")

    def local(self) -> datetime:
        return self.consumed_at.astimezone(resolve_timezone(self.timezone))

    def logical_date(self, day_boundary_hour: int = DEFAULT_DAY_BOUNDARY_HOUR) -> date:
        """The day this meal belongs to, given a non-midnight boundary."""
        local = self.local()
        if local.hour < day_boundary_hour:
            return (local - timedelta(days=1)).date()
        return local.date()


@dataclass(frozen=True, slots=True)
class DailyEatingPattern:
    """Timing statistics for one logical day."""

    day: date
    timezone: str
    meal_count: int
    first_meal_local: datetime | None
    last_meal_local: datetime | None
    eating_window_minutes: int | None
    fasting_minutes: int | None
    meal_times_local: tuple[time, ...] = field(default_factory=tuple)

    @property
    def eating_window_hours(self) -> float | None:
        if self.eating_window_minutes is None:
            return None
        return round(self.eating_window_minutes / 60.0, 2)

    @property
    def fasting_hours(self) -> float | None:
        if self.fasting_minutes is None:
            return None
        return round(self.fasting_minutes / 60.0, 2)

    @property
    def is_single_meal_day(self) -> bool:
        """A one-meal day has no window, which is different from a zero window."""
        return self.meal_count == 1


@dataclass(frozen=True, slots=True)
class WeeklyEatingPattern:
    """Aggregate view across a span of days."""

    start_day: date
    end_day: date
    timezone: str
    days: tuple[DailyEatingPattern, ...]
    days_with_meals: int
    total_meals: int
    mean_eating_window_minutes: float | None
    median_eating_window_minutes: float | None
    mean_first_meal_minutes_after_midnight: float | None
    mean_last_meal_minutes_after_midnight: float | None
    eating_window_consistency: float | None

    @property
    def mean_meals_per_active_day(self) -> float | None:
        if self.days_with_meals == 0:
            return None
        return round(self.total_meals / self.days_with_meals, 2)


class ChrononutritionEngine:
    """Derives eating-window and fasting statistics from meal timestamps."""

    def __init__(self, day_boundary_hour: int = DEFAULT_DAY_BOUNDARY_HOUR) -> None:
        if not 0 <= day_boundary_hour <= 12:
            raise ValueError("day_boundary_hour must be between 0 and 12")
        self._day_boundary_hour = day_boundary_hour

    @property
    def day_boundary_hour(self) -> int:
        return self._day_boundary_hour

    def group_by_day(
        self, meals: list[MealTimestamp]
    ) -> dict[date, list[MealTimestamp]]:
        grouped: dict[date, list[MealTimestamp]] = defaultdict(list)
        for meal in meals:
            grouped[meal.logical_date(self._day_boundary_hour)].append(meal)
        for entries in grouped.values():
            entries.sort(key=lambda m: m.consumed_at)
        return dict(grouped)

    def daily_pattern(
        self, day: date, meals: list[MealTimestamp], timezone: str
    ) -> DailyEatingPattern:
        """Statistics for one day.

        ``fasting_minutes`` is the complement of the eating window within 24
        hours -- the overnight fast implied by today's own first and last meal.
        It is not a measured inter-meal gap across days, which would require
        the neighbouring days and is reported separately by
        :meth:`fasting_between_days`.
        """
        if not meals:
            return DailyEatingPattern(
                day=day,
                timezone=timezone,
                meal_count=0,
                first_meal_local=None,
                last_meal_local=None,
                eating_window_minutes=None,
                fasting_minutes=None,
            )

        ordered = sorted(meals, key=lambda m: m.consumed_at)
        first_local = ordered[0].local()
        last_local = ordered[-1].local()

        window_minutes: int | None = None
        fasting_minutes: int | None = None
        if len(ordered) > 1:
            delta = ordered[-1].consumed_at - ordered[0].consumed_at
            window_minutes = int(delta.total_seconds() // 60)
            fasting_minutes = max(0, 24 * 60 - window_minutes)

        return DailyEatingPattern(
            day=day,
            timezone=timezone,
            meal_count=len(ordered),
            first_meal_local=first_local,
            last_meal_local=last_local,
            eating_window_minutes=window_minutes,
            fasting_minutes=fasting_minutes,
            meal_times_local=tuple(meal.local().timetz().replace(tzinfo=None) for meal in ordered),
        )

    def daily_patterns(
        self,
        meals: list[MealTimestamp],
        timezone: str,
        start_day: date,
        end_day: date,
    ) -> list[DailyEatingPattern]:
        """One entry per day in the range, including days with no meals.

        Empty days are represented rather than omitted: a gap in the timeline
        is itself information, and the UI must not have to infer it.
        """
        if end_day < start_day:
            raise ValueError("end_day must not precede start_day")

        grouped = self.group_by_day(meals)
        patterns: list[DailyEatingPattern] = []
        cursor = start_day
        while cursor <= end_day:
            patterns.append(self.daily_pattern(cursor, grouped.get(cursor, []), timezone))
            cursor += timedelta(days=1)
        return patterns

    def fasting_between_days(
        self, earlier: DailyEatingPattern, later: DailyEatingPattern
    ) -> int | None:
        """Minutes between the last meal of one day and the first of the next."""
        if earlier.last_meal_local is None or later.first_meal_local is None:
            return None
        delta = later.first_meal_local - earlier.last_meal_local
        minutes = int(delta.total_seconds() // 60)
        return minutes if minutes >= 0 else None

    def weekly_pattern(
        self,
        meals: list[MealTimestamp],
        timezone: str,
        start_day: date,
        end_day: date,
    ) -> WeeklyEatingPattern:
        days = self.daily_patterns(meals, timezone, start_day, end_day)
        active = [day for day in days if day.meal_count > 0]
        windows = [
            day.eating_window_minutes for day in days if day.eating_window_minutes is not None
        ]

        first_minutes = [
            _minutes_after_midnight(day.first_meal_local)
            for day in active
            if day.first_meal_local is not None
        ]
        last_minutes = [
            _minutes_after_midnight(day.last_meal_local)
            for day in active
            if day.last_meal_local is not None
        ]

        return WeeklyEatingPattern(
            start_day=start_day,
            end_day=end_day,
            timezone=timezone,
            days=tuple(days),
            days_with_meals=len(active),
            total_meals=sum(day.meal_count for day in days),
            mean_eating_window_minutes=_mean(windows),
            median_eating_window_minutes=_median(windows),
            mean_first_meal_minutes_after_midnight=_mean(first_minutes),
            mean_last_meal_minutes_after_midnight=_mean(last_minutes),
            eating_window_consistency=self.consistency_score(windows),
        )

    @staticmethod
    def consistency_score(window_minutes: list[int]) -> float | None:
        """How stable the eating window is across days, in [0, 1].

        Defined as ``1 - min(1, stdev / mean)``: a coefficient of variation of
        zero scores 1.0, and a spread as large as the mean scores 0.0. Fewer
        than two days with a window is not enough to say anything, so the
        answer is ``None`` rather than a flattering default.
        """
        if len(window_minutes) < 2:
            return None
        mean = statistics.fmean(window_minutes)
        if mean <= 0:
            return None
        deviation = statistics.stdev(window_minutes)
        return round(max(0.0, 1.0 - min(1.0, deviation / mean)), 4)

    def current_day_bounds(
        self, timezone: str, now: datetime | None = None
    ) -> tuple[datetime, datetime]:
        """UTC half-open bounds ``[start, end)`` of the user's current logical day.

        Returned in UTC because that is what the database stores; the boundary
        is computed in local time because that is what the user lives in.
        """
        zone = resolve_timezone(timezone)
        moment = (now or datetime.now(UTC)).astimezone(zone)
        logical = moment.date()
        if moment.hour < self._day_boundary_hour:
            logical -= timedelta(days=1)
        start_local = datetime.combine(
            logical, time(hour=self._day_boundary_hour), tzinfo=zone
        )
        return start_local.astimezone(UTC), (start_local + timedelta(days=1)).astimezone(UTC)


def _minutes_after_midnight(moment: datetime) -> int:
    return moment.hour * 60 + moment.minute


def _mean(values: list[int]) -> float | None:
    return round(statistics.fmean(values), 2) if values else None


def _median(values: list[int]) -> float | None:
    return round(statistics.median(values), 2) if values else None
