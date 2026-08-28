"""The chrononutrition domain engine. Pure logic, no database."""

from __future__ import annotations

from datetime import UTC, date, datetime, timedelta

import pytest

from app.domain.chrononutrition import (
    ChrononutritionEngine,
    InvalidTimezoneError,
    MealTimestamp,
    resolve_timezone,
)

KOLKATA = "Asia/Kolkata"
NEW_YORK = "America/New_York"


def meal(hour: int, minute: int = 0, tz: str = KOLKATA, day: int = 1) -> MealTimestamp:
    """A meal at a given *local* time on 2026-05-<day>."""
    zone = resolve_timezone(tz)
    local = datetime(2026, 5, day, hour, minute, tzinfo=zone)
    return MealTimestamp(consumed_at=local.astimezone(UTC), timezone=tz, meal_type="other")


@pytest.fixture
def engine() -> ChrononutritionEngine:
    return ChrononutritionEngine()


class TestMealTimestamp:
    def test_rejects_a_naive_timestamp(self):
        with pytest.raises(ValueError):
            MealTimestamp(datetime(2026, 5, 1, 8, 0), KOLKATA, "breakfast")

    def test_converts_utc_back_to_local(self):
        stamp = MealTimestamp(datetime(2026, 5, 1, 3, 12, tzinfo=UTC), KOLKATA, "breakfast")
        assert stamp.local().strftime("%H:%M") == "08:42"

    def test_rejects_an_unknown_timezone(self):
        with pytest.raises(InvalidTimezoneError):
            MealTimestamp(datetime(2026, 5, 1, tzinfo=UTC), "Mars/Olympus", "other").local()


class TestLogicalDay:
    def test_a_normal_meal_belongs_to_its_own_date(self):
        assert meal(13, 0).logical_date() == date(2026, 5, 1)

    def test_a_late_night_meal_belongs_to_the_previous_evening(self):
        # 01:30 is the tail of the night before, not the start of a new day.
        assert meal(1, 30, day=2).logical_date() == date(2026, 5, 1)

    def test_the_boundary_hour_starts_a_new_day(self):
        assert meal(4, 0, day=2).logical_date() == date(2026, 5, 2)

    def test_the_boundary_is_configurable(self):
        assert meal(3, 0, day=2).logical_date(day_boundary_hour=2) == date(2026, 5, 2)


class TestDailyPattern:
    def test_reproduces_the_documented_example(self, engine):
        """The worked example from the product spec: 08:42 to 19:16."""
        meals = [meal(8, 42), meal(14, 0), meal(19, 16)]
        pattern = engine.daily_pattern(date(2026, 5, 1), meals, KOLKATA)

        assert pattern.meal_count == 3
        assert pattern.first_meal_local.strftime("%H:%M") == "08:42"
        assert pattern.last_meal_local.strftime("%H:%M") == "19:16"
        assert pattern.eating_window_minutes == 634  # 10h 34m
        assert pattern.fasting_minutes == 806  # 13h 26m

    def test_window_and_fast_together_span_a_day(self, engine):
        pattern = engine.daily_pattern(date(2026, 5, 1), [meal(8), meal(20)], KOLKATA)
        assert pattern.eating_window_minutes + pattern.fasting_minutes == 24 * 60

    def test_a_single_meal_has_no_window(self, engine):
        # Zero would imply a measured instant-long window; None says "unknown".
        pattern = engine.daily_pattern(date(2026, 5, 1), [meal(12)], KOLKATA)
        assert pattern.eating_window_minutes is None
        assert pattern.fasting_minutes is None
        assert pattern.is_single_meal_day is True

    def test_an_empty_day_reports_zero_meals(self, engine):
        pattern = engine.daily_pattern(date(2026, 5, 1), [], KOLKATA)
        assert pattern.meal_count == 0
        assert pattern.first_meal_local is None

    def test_meals_out_of_order_are_sorted(self, engine):
        pattern = engine.daily_pattern(
            date(2026, 5, 1), [meal(19, 16), meal(8, 42), meal(14)], KOLKATA
        )
        assert pattern.first_meal_local.strftime("%H:%M") == "08:42"
        assert pattern.last_meal_local.strftime("%H:%M") == "19:16"

    def test_hours_are_rounded_for_display(self, engine):
        pattern = engine.daily_pattern(date(2026, 5, 1), [meal(8, 42), meal(19, 16)], KOLKATA)
        assert pattern.eating_window_hours == 10.57


class TestGrouping:
    def test_splits_meals_across_logical_days(self, engine):
        grouped = engine.group_by_day([meal(9, day=1), meal(1, day=2), meal(9, day=2)])
        assert sorted(grouped) == [date(2026, 5, 1), date(2026, 5, 2)]
        assert len(grouped[date(2026, 5, 1)]) == 2  # includes the 01:00 of the 2nd

    def test_daily_patterns_include_days_with_no_meals(self, engine):
        patterns = engine.daily_patterns(
            [meal(9, day=1)], KOLKATA, date(2026, 5, 1), date(2026, 5, 3)
        )
        assert len(patterns) == 3
        assert [p.meal_count for p in patterns] == [1, 0, 0]

    def test_rejects_an_inverted_range(self, engine):
        with pytest.raises(ValueError):
            engine.daily_patterns([], KOLKATA, date(2026, 5, 3), date(2026, 5, 1))


class TestOvernightFasting:
    def test_measures_the_gap_between_consecutive_days(self, engine):
        patterns = engine.daily_patterns(
            [meal(20, day=1), meal(8, day=2)], KOLKATA, date(2026, 5, 1), date(2026, 5, 2)
        )
        assert engine.fasting_between_days(patterns[0], patterns[1]) == 12 * 60

    def test_is_unknown_when_a_day_has_no_meals(self, engine):
        patterns = engine.daily_patterns(
            [meal(20, day=1)], KOLKATA, date(2026, 5, 1), date(2026, 5, 2)
        )
        assert engine.fasting_between_days(patterns[0], patterns[1]) is None


class TestWeeklyPattern:
    def test_aggregates_across_days(self, engine):
        meals = [meal(8, day=d) for d in (1, 2, 3)] + [meal(20, day=d) for d in (1, 2, 3)]
        weekly = engine.weekly_pattern(meals, KOLKATA, date(2026, 5, 1), date(2026, 5, 3))
        assert weekly.days_with_meals == 3
        assert weekly.total_meals == 6
        assert weekly.mean_eating_window_minutes == 720.0
        assert weekly.mean_meals_per_active_day == 2.0

    def test_identical_days_are_perfectly_consistent(self, engine):
        meals = [meal(8, day=d) for d in (1, 2, 3)] + [meal(20, day=d) for d in (1, 2, 3)]
        weekly = engine.weekly_pattern(meals, KOLKATA, date(2026, 5, 1), date(2026, 5, 3))
        assert weekly.eating_window_consistency == 1.0

    def test_erratic_days_score_lower(self, engine):
        meals = [
            meal(8, day=1),
            meal(20, day=1),  # 12 h
            meal(8, day=2),
            meal(9, day=2),  # 1 h
            meal(7, day=3),
            meal(23, day=3),  # 16 h
        ]
        weekly = engine.weekly_pattern(meals, KOLKATA, date(2026, 5, 1), date(2026, 5, 3))
        assert weekly.eating_window_consistency < 0.6

    def test_consistency_is_unknown_with_fewer_than_two_windows(self, engine):
        weekly = engine.weekly_pattern(
            [meal(8, day=1), meal(20, day=1)], KOLKATA, date(2026, 5, 1), date(2026, 5, 1)
        )
        assert weekly.eating_window_consistency is None

    def test_an_empty_range_reports_nothing_rather_than_zeros(self, engine):
        weekly = engine.weekly_pattern([], KOLKATA, date(2026, 5, 1), date(2026, 5, 7))
        assert weekly.total_meals == 0
        assert weekly.mean_eating_window_minutes is None
        assert weekly.mean_meals_per_active_day is None


class TestConsistencyScore:
    def test_zero_variation_scores_one(self, engine):
        assert engine.consistency_score([600, 600, 600]) == 1.0

    def test_variation_as_large_as_the_mean_scores_zero(self, engine):
        assert engine.consistency_score([0, 1200]) == 0.0

    def test_needs_at_least_two_days(self, engine):
        assert engine.consistency_score([600]) is None
        assert engine.consistency_score([]) is None


class TestCurrentDayBounds:
    def test_spans_exactly_one_day(self, engine):
        start, end = engine.current_day_bounds(KOLKATA, datetime(2026, 5, 1, 12, tzinfo=UTC))
        assert end - start == timedelta(days=1)

    def test_before_the_boundary_returns_the_previous_day(self, engine):
        # 02:00 local on the 2nd still belongs to the 1st.
        moment = datetime(2026, 5, 2, 2, 0, tzinfo=resolve_timezone(KOLKATA))
        start, _ = engine.current_day_bounds(KOLKATA, moment.astimezone(UTC))
        assert start.astimezone(resolve_timezone(KOLKATA)).date() == date(2026, 5, 1)

    def test_bounds_are_returned_in_utc(self, engine):
        start, end = engine.current_day_bounds(KOLKATA, datetime(2026, 5, 1, 12, tzinfo=UTC))
        assert start.tzinfo == UTC and end.tzinfo == UTC


class TestTimezoneCorrectness:
    def test_the_same_instant_falls_on_different_local_days(self):
        """The point of storing a zone: UTC alone cannot place a meal in a day."""
        instant = datetime(2026, 5, 2, 2, 0, tzinfo=UTC)
        in_kolkata = MealTimestamp(instant, KOLKATA, "other")  # 07:30 on the 2nd
        in_new_york = MealTimestamp(instant, NEW_YORK, "other")  # 22:00 on the 1st
        assert in_kolkata.logical_date() == date(2026, 5, 2)
        assert in_new_york.logical_date() == date(2026, 5, 1)

    def test_a_window_spanning_a_dst_shift_measures_elapsed_time(self, engine):
        """US DST springs forward at 02:00 on 2026-03-08.

        A window that straddles the shift is shorter in elapsed time than the
        wall clock suggests: 01:00 to 07:00 reads as six hours on the clock but
        only five hours passed. Elapsed time is the right answer for a fasting
        or eating interval, so that is what the engine reports.
        """
        zone = resolve_timezone(NEW_YORK)
        before = datetime(2026, 3, 8, 1, 0, tzinfo=zone)  # 01:00 EST
        after = datetime(2026, 3, 8, 7, 0, tzinfo=zone)  # 07:00 EDT
        assert before.utcoffset() != after.utcoffset(), "fixture must straddle the shift"

        meals = [
            MealTimestamp(before.astimezone(UTC), NEW_YORK, "snack"),
            MealTimestamp(after.astimezone(UTC), NEW_YORK, "breakfast"),
        ]
        # 01:00 belongs to the previous logical day, so this is deliberately
        # exercised as a raw pattern over both meals rather than via grouping.
        pattern = engine.daily_pattern(date(2026, 3, 8), meals, NEW_YORK)
        assert pattern.first_meal_local.strftime("%H:%M") == "01:00"
        assert pattern.last_meal_local.strftime("%H:%M") == "07:00"
        assert pattern.eating_window_minutes == 300  # five hours actually elapsed

    def test_local_times_are_reported_in_the_offset_in_force(self, engine):
        """Each meal renders at the wall-clock time the user actually saw."""
        zone = resolve_timezone(NEW_YORK)
        winter = datetime(2026, 1, 15, 8, 0, tzinfo=zone)
        summer = datetime(2026, 7, 15, 8, 0, tzinfo=zone)
        for moment in (winter, summer):
            stamp = MealTimestamp(moment.astimezone(UTC), NEW_YORK, "breakfast")
            assert stamp.local().strftime("%H:%M") == "08:00"
