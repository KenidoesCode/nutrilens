"""Meal logging, corrections and ownership isolation."""

from __future__ import annotations


class TestCreateMeal:
    def test_logs_a_meal_and_derives_mass(self, client, auth_headers, make_meal):
        response = client.post("/api/v1/meals", json=make_meal(), headers=auth_headers)
        assert response.status_code == 201
        body = response.json()
        item = body["items"][0]
        # 180 ml of rice at the catalog density of 0.85 g/ml.
        assert item["estimated_mass_g"] == 153.0
        assert item["density_g_per_ml"] == 0.85
        assert body["total_mass_g"] == 153.0

    def test_attaches_estimated_nutrition(self, client, auth_headers, make_meal):
        body = client.post("/api/v1/meals", json=make_meal(), headers=auth_headers).json()
        # 153 g of rice at 130 kcal/100 g.
        assert body["items"][0]["energy_kcal"] == 198.9
        assert body["total_energy_kcal"] == 198.9

    def test_ignores_a_client_supplied_mass_and_recomputes(self, client, auth_headers, make_meal):
        # The client cannot dictate mass; the server derives it from volume and
        # the density it holds, so an outdated device cannot corrupt the record.
        payload = make_meal()
        payload["items"][0]["estimated_mass_g"] = 9999.0
        body = client.post("/api/v1/meals", json=payload, headers=auth_headers).json()
        assert body["items"][0]["estimated_mass_g"] == 153.0

    def test_unknown_food_falls_back_to_a_category_density(self, client, auth_headers, make_meal):
        payload = make_meal()
        payload["items"][0]["display_name"] = "Grandmother's special stew"
        body = client.post("/api/v1/meals", json=payload, headers=auth_headers).json()
        item = body["items"][0]
        assert item["density_source"].startswith("category-default@")
        assert item["energy_kcal"] is None

    def test_rejects_a_naive_timestamp(self, client, auth_headers, make_meal):
        response = client.post(
            "/api/v1/meals",
            json=make_meal(consumed_at="2026-05-01T08:42:00"),
            headers=auth_headers,
        )
        assert response.status_code == 422

    def test_rejects_an_unknown_meal_type(self, client, auth_headers, make_meal):
        response = client.post(
            "/api/v1/meals", json=make_meal(meal_type="brunch"), headers=auth_headers
        )
        assert response.status_code == 422

    def test_rejects_a_meal_with_no_items(self, client, auth_headers, make_meal):
        response = client.post("/api/v1/meals", json=make_meal(items=[]), headers=auth_headers)
        assert response.status_code == 422

    def test_rejects_a_non_positive_volume(self, client, auth_headers, make_meal):
        payload = make_meal()
        payload["items"][0]["estimated_volume_ml"] = 0
        assert client.post("/api/v1/meals", json=payload, headers=auth_headers).status_code == 422


class TestIdempotency:
    def test_a_replayed_key_does_not_create_a_second_meal(self, client, auth_headers, make_meal):
        payload = make_meal(idempotency_key="device-a-0001")
        first = client.post("/api/v1/meals", json=payload, headers=auth_headers)
        second = client.post("/api/v1/meals", json=payload, headers=auth_headers)

        assert first.status_code == 201
        assert second.status_code == 200  # replay, not a new resource
        assert first.json()["id"] == second.json()["id"]

        listing = client.get("/api/v1/meals", headers=auth_headers).json()
        assert listing["meta"]["total"] == 1

    def test_different_keys_create_different_meals(self, client, auth_headers, make_meal):
        client.post("/api/v1/meals", json=make_meal(idempotency_key="k1"), headers=auth_headers)
        client.post("/api/v1/meals", json=make_meal(idempotency_key="k2"), headers=auth_headers)
        assert client.get("/api/v1/meals", headers=auth_headers).json()["meta"]["total"] == 2

    def test_a_replay_does_not_resurrect_a_deleted_meal(self, client, auth_headers, make_meal):
        payload = make_meal(idempotency_key="device-a-0002")
        meal_id = client.post("/api/v1/meals", json=payload, headers=auth_headers).json()["id"]
        client.delete(f"/api/v1/meals/{meal_id}", headers=auth_headers)

        replay = client.post("/api/v1/meals", json=payload, headers=auth_headers)
        assert replay.status_code == 200
        assert client.get("/api/v1/meals", headers=auth_headers).json()["meta"]["total"] == 0


class TestListAndFetch:
    def test_lists_newest_first(self, client, auth_headers, make_meal):
        client.post(
            "/api/v1/meals",
            json=make_meal(consumed_at="2026-05-01T08:00:00+05:30", idempotency_key="a"),
            headers=auth_headers,
        )
        client.post(
            "/api/v1/meals",
            json=make_meal(consumed_at="2026-05-01T19:00:00+05:30", idempotency_key="b"),
            headers=auth_headers,
        )
        items = client.get("/api/v1/meals", headers=auth_headers).json()["items"]
        assert items[0]["consumed_at"] > items[1]["consumed_at"]

    def test_paginates(self, client, auth_headers, make_meal):
        for index in range(5):
            client.post(
                "/api/v1/meals",
                json=make_meal(idempotency_key=f"page-{index}"),
                headers=auth_headers,
            )
        page = client.get("/api/v1/meals?limit=2&offset=0", headers=auth_headers).json()
        assert len(page["items"]) == 2
        assert page["meta"]["total"] == 5
        assert page["meta"]["has_more"] is True

        last = client.get("/api/v1/meals?limit=2&offset=4", headers=auth_headers).json()
        assert last["meta"]["has_more"] is False

    def test_unknown_meal_is_a_404(self, client, auth_headers):
        response = client.get(
            "/api/v1/meals/00000000-0000-4000-8000-000000000000", headers=auth_headers
        )
        assert response.status_code == 404
        assert response.json()["error"]["code"] == "NOT_FOUND"

    def test_deleted_meals_disappear_from_the_listing(self, client, auth_headers, make_meal):
        meal_id = client.post("/api/v1/meals", json=make_meal(), headers=auth_headers).json()["id"]
        assert client.delete(f"/api/v1/meals/{meal_id}", headers=auth_headers).status_code == 204
        assert client.get(f"/api/v1/meals/{meal_id}", headers=auth_headers).status_code == 404
        assert client.get("/api/v1/meals", headers=auth_headers).json()["meta"]["total"] == 0


class TestOwnershipIsolation:
    @staticmethod
    def _second_account(client, new_email, password) -> dict[str, str]:
        """Auth headers for an unrelated account."""
        response = client.post(
            "/api/v1/auth/register",
            json={"email": new_email(), "password": password},
        )
        assert response.status_code == 201, response.text
        return {"Authorization": f"Bearer {response.json()['access_token']}"}

    def test_one_user_cannot_read_another_users_meal(
        self, client, auth_headers, make_meal, new_email, password
    ):
        meal_id = client.post("/api/v1/meals", json=make_meal(), headers=auth_headers).json()["id"]
        other = self._second_account(client, new_email, password)
        # 404 rather than 403: confirming the id exists would leak information.
        assert client.get(f"/api/v1/meals/{meal_id}", headers=other).status_code == 404

    def test_one_user_cannot_delete_another_users_meal(
        self, client, auth_headers, make_meal, new_email, password
    ):
        meal_id = client.post("/api/v1/meals", json=make_meal(), headers=auth_headers).json()["id"]
        other = self._second_account(client, new_email, password)
        assert client.delete(f"/api/v1/meals/{meal_id}", headers=other).status_code == 404
        assert client.get(f"/api/v1/meals/{meal_id}", headers=auth_headers).status_code == 200

    def test_one_user_cannot_correct_another_users_item(
        self, client, auth_headers, make_meal, new_email, password
    ):
        item_id = client.post("/api/v1/meals", json=make_meal(), headers=auth_headers).json()[
            "items"
        ][0]["id"]
        other = self._second_account(client, new_email, password)
        response = client.patch(
            f"/api/v1/meals/items/{item_id}/portion",
            json={"corrected_volume_ml": 50},
            headers=other,
        )
        assert response.status_code == 404


class TestCorrections:
    def test_portion_correction_recomputes_mass_and_nutrition(
        self, client, auth_headers, make_meal
    ):
        item_id = client.post("/api/v1/meals", json=make_meal(), headers=auth_headers).json()[
            "items"
        ][0]["id"]

        response = client.patch(
            f"/api/v1/meals/items/{item_id}/portion",
            json={"corrected_volume_ml": 100.0},
            headers=auth_headers,
        )
        assert response.status_code == 200
        item = response.json()
        assert item["estimated_volume_ml"] == 100.0
        assert item["estimated_mass_g"] == 85.0
        assert item["energy_kcal"] == 110.5
        assert item["was_user_corrected"] is True

    def test_a_correction_preserves_the_original_estimate(self, client, auth_headers, make_meal):
        item_id = client.post("/api/v1/meals", json=make_meal(), headers=auth_headers).json()[
            "items"
        ][0]["id"]
        item = client.patch(
            f"/api/v1/meals/items/{item_id}/portion",
            json={"corrected_volume_ml": 100.0},
            headers=auth_headers,
        ).json()
        # The model's original figure survives the edit, so accuracy stays measurable.
        assert item["original_mass_g"] == 153.0

    def test_a_correction_updates_the_meal_total(self, client, auth_headers, make_meal):
        meal = client.post("/api/v1/meals", json=make_meal(), headers=auth_headers).json()
        client.patch(
            f"/api/v1/meals/items/{meal['items'][0]['id']}/portion",
            json={"corrected_volume_ml": 100.0},
            headers=auth_headers,
        )
        refreshed = client.get(f"/api/v1/meals/{meal['id']}", headers=auth_headers).json()
        assert refreshed["total_mass_g"] == 85.0

    def test_renaming_a_food_re_resolves_its_density(self, client, auth_headers, make_meal):
        item_id = client.post("/api/v1/meals", json=make_meal(), headers=auth_headers).json()[
            "items"
        ][0]["id"]

        response = client.patch(
            f"/api/v1/meals/items/{item_id}/name",
            json={"display_name": "Yogurt"},
            headers=auth_headers,
        )
        item = response.json()
        assert item["display_name"] == "Yogurt"
        assert item["density_g_per_ml"] == 1.04
        assert item["estimated_mass_g"] == 187.2  # 180 ml x 1.04
        assert item["original_display_name"] == "Rice"

    def test_rejects_an_implausible_correction(self, client, auth_headers, make_meal):
        item_id = client.post("/api/v1/meals", json=make_meal(), headers=auth_headers).json()[
            "items"
        ][0]["id"]
        assert (
            client.patch(
                f"/api/v1/meals/items/{item_id}/portion",
                json={"corrected_volume_ml": 99999},
                headers=auth_headers,
            ).status_code
            == 422
        )

    def test_removing_an_item_updates_the_total(self, client, auth_headers, make_meal):
        meal = client.post("/api/v1/meals", json=make_meal(), headers=auth_headers).json()
        assert (
            client.delete(
                f"/api/v1/meals/items/{meal['items'][0]['id']}", headers=auth_headers
            ).status_code
            == 204
        )

        refreshed = client.get(f"/api/v1/meals/{meal['id']}", headers=auth_headers).json()
        assert refreshed["items"] == []
        assert refreshed["total_mass_g"] is None
