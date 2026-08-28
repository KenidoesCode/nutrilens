"""Analytics endpoints over stored meals."""

from __future__ import annotations


def log(client, headers, make_meal, iso: str, key: str) -> None:
    """Log one meal at a given local time, failing loudly if the API refuses."""
    response = client.post(
        "/api/v1/meals",
        json=make_meal(consumed_at=iso, idempotency_key=key),
        headers=headers,
    )
    assert response.status_code == 201, response.text


class TestRangeAnalytics:
    def test_computes_the_window_for_a_day(self, client, auth_headers, make_meal):
        log(client, auth_headers, make_meal, "2026-05-01T08:42:00+05:30", "a1")
        log(client, auth_headers, make_meal, "2026-05-01T14:00:00+05:30", "a2")
        log(client, auth_headers, make_meal, "2026-05-01T19:16:00+05:30", "a3")

        body = client.get(
            "/api/v1/analytics/range?start_day=2026-05-01&end_day=2026-05-01",
            headers=auth_headers,
        ).json()
        day = body["days"][0]
        assert day["meal_count"] == 3
        assert day["eating_window_minutes"] == 634
        assert day["fasting_minutes"] == 806

    def test_includes_days_with_no_meals(self, client, auth_headers, make_meal):
        log(client, auth_headers, make_meal, "2026-05-01T08:00:00+05:30", "b1")
        body = client.get(
            "/api/v1/analytics/range?start_day=2026-05-01&end_day=2026-05-03",
            headers=auth_headers,
        ).json()
        assert len(body["days"]) == 3
        assert [d["meal_count"] for d in body["days"]] == [1, 0, 0]

    def test_a_late_night_meal_counts_towards_the_evening(self, client, auth_headers, make_meal):
        # 01:00 on the 2nd belongs to the 1st, so the window must reflect that.
        log(client, auth_headers, make_meal, "2026-05-01T09:00:00+05:30", "c1")
        log(client, auth_headers, make_meal, "2026-05-02T01:00:00+05:30", "c2")

        body = client.get(
            "/api/v1/analytics/range?start_day=2026-05-01&end_day=2026-05-02",
            headers=auth_headers,
        ).json()
        assert body["days"][0]["meal_count"] == 2
        assert body["days"][1]["meal_count"] == 0
        assert body["days"][0]["eating_window_minutes"] == 16 * 60

    def test_reports_consistency_across_days(self, client, auth_headers, make_meal):
        for index, day in enumerate(("01", "02", "03")):
            log(client, auth_headers, make_meal, f"2026-05-{day}T08:00:00+05:30", f"d{index}a")
            log(client, auth_headers, make_meal, f"2026-05-{day}T20:00:00+05:30", f"d{index}b")

        body = client.get(
            "/api/v1/analytics/range?start_day=2026-05-01&end_day=2026-05-03",
            headers=auth_headers,
        ).json()
        assert body["eating_window_consistency"] == 1.0
        assert body["mean_meals_per_active_day"] == 2.0

    def test_rejects_an_inverted_range(self, client, auth_headers):
        response = client.get(
            "/api/v1/analytics/range?start_day=2026-05-05&end_day=2026-05-01",
            headers=auth_headers,
        )
        assert response.status_code == 422

    def test_rejects_an_excessive_range(self, client, auth_headers):
        response = client.get(
            "/api/v1/analytics/range?start_day=2020-01-01&end_day=2026-01-01",
            headers=auth_headers,
        )
        assert response.status_code == 422

    def test_requires_authentication(self, client):
        assert client.get("/api/v1/analytics/range").status_code == 401


class TestTodayEndpoint:
    def test_responds_with_an_empty_day_for_a_new_account(self, client, auth_headers):
        body = client.get("/api/v1/analytics/today", headers=auth_headers).json()
        assert body["meal_count"] == 0
        assert body["eating_window_minutes"] is None
        assert body["timezone"] == "Asia/Kolkata"


class TestNutritionTotals:
    def test_sums_estimates_across_a_range(self, client, auth_headers, make_meal):
        log(client, auth_headers, make_meal, "2026-05-01T08:00:00+05:30", "n1")
        log(client, auth_headers, make_meal, "2026-05-01T20:00:00+05:30", "n2")

        body = client.get(
            "/api/v1/analytics/nutrition?start_day=2026-05-01&end_day=2026-05-01",
            headers=auth_headers,
        ).json()
        assert body["mass_g"] == 306.0  # two servings of 153 g
        assert body["energy_kcal"] == 397.8
        assert body["estimates_are_approximate"] is True

    def test_unknown_nutrition_contributes_nothing_but_still_counts_mass(
        self, client, auth_headers, make_meal
    ):
        payload = make_meal(idempotency_key="unknown-food")
        payload["items"][0]["display_name"] = "Grandmother's special stew"
        client.post("/api/v1/meals", json=payload, headers=auth_headers)

        body = client.get(
            "/api/v1/analytics/nutrition?start_day=2026-05-01&end_day=2026-05-01",
            headers=auth_headers,
        ).json()
        assert body["mass_g"] > 0
        assert body["energy_kcal"] == 0.0

    def test_excludes_other_users(self, client, auth_headers, make_meal, new_email, password):

        log(client, auth_headers, make_meal, "2026-05-01T08:00:00+05:30", "iso1")
        other = client.post(
            "/api/v1/auth/register",
            json={"email": new_email(), "password": password},
        ).json()
        body = client.get(
            "/api/v1/analytics/nutrition?start_day=2026-05-01&end_day=2026-05-01",
            headers={"Authorization": f"Bearer {other['access_token']}"},
        ).json()
        assert body["mass_g"] == 0.0
