"""Offline synchronisation: idempotency, partial failure and change pulls."""

from __future__ import annotations

from datetime import UTC, datetime


def push_op(make_meal, key: str, **overrides) -> dict:
    operation = {
        "idempotency_key": key,
        "operation": "create_meal",
        "meal": make_meal(),
    }
    operation.update(overrides)
    return operation


class TestPush:
    def test_applies_a_batch(self, client, auth_headers, make_meal):
        response = client.post(
            "/api/v1/sync/push",
            json={
                "operations": [push_op(make_meal, "op-00000001"), push_op(make_meal, "op-00000002")]
            },
            headers=auth_headers,
        )
        assert response.status_code == 200
        body = response.json()
        assert body["applied"] == 2
        assert body["failed"] == 0
        assert all(result["entity_id"] for result in body["results"])

    def test_a_replayed_batch_changes_nothing(self, client, auth_headers, make_meal):
        batch = {
            "operations": [push_op(make_meal, "op-replay-1"), push_op(make_meal, "op-replay-2")]
        }
        client.post("/api/v1/sync/push", json=batch, headers=auth_headers)
        second = client.post("/api/v1/sync/push", json=batch, headers=auth_headers).json()

        assert second["replayed"] == 2
        assert second["applied"] == 0
        assert client.get("/api/v1/meals", headers=auth_headers).json()["meta"]["total"] == 2

    def test_one_bad_operation_does_not_lose_the_others(self, client, auth_headers, make_meal):
        # The whole point of per-operation keys: a device must never have to
        # choose between dropping a meal and resending a batch that fails again.
        broken = push_op(make_meal, "op-broken", meal=make_meal(meal_type="brunch"))
        response = client.post(
            "/api/v1/sync/push",
            json={
                "operations": [
                    push_op(make_meal, "op-good-1"),
                    broken,
                    push_op(make_meal, "op-good-2"),
                ]
            },
            headers=auth_headers,
        ).json()

        assert response["applied"] == 2
        assert response["failed"] == 1
        failure = next(r for r in response["results"] if r["status"] == "failed")
        assert failure["error_code"]
        assert client.get("/api/v1/meals", headers=auth_headers).json()["meta"]["total"] == 2

    def test_the_batch_key_overrides_an_embedded_one(self, client, auth_headers, make_meal):
        # One ledger entry per client operation, whatever the payload carries.
        operation = push_op(make_meal, "batch-key-wins")
        operation["meal"]["idempotency_key"] = "a-different-embedded-key"
        client.post("/api/v1/sync/push", json={"operations": [operation]}, headers=auth_headers)
        replay = client.post(
            "/api/v1/sync/push", json={"operations": [operation]}, headers=auth_headers
        ).json()
        assert replay["replayed"] == 1

    def test_applies_a_delete(self, client, auth_headers, make_meal):
        meal_id = client.post("/api/v1/meals", json=make_meal(), headers=auth_headers).json()["id"]
        response = client.post(
            "/api/v1/sync/push",
            json={
                "operations": [
                    {
                        "idempotency_key": "del-00000001",
                        "operation": "delete_meal",
                        "meal_id": meal_id,
                    }
                ]
            },
            headers=auth_headers,
        ).json()
        assert response["applied"] == 1
        assert client.get("/api/v1/meals", headers=auth_headers).json()["meta"]["total"] == 0

    def test_rejects_an_unknown_operation(self, client, auth_headers, make_meal):
        response = client.post(
            "/api/v1/sync/push",
            json={"operations": [{"idempotency_key": "op-unknown", "operation": "launch_rocket"}]},
            headers=auth_headers,
        )
        assert response.status_code == 422

    def test_rejects_an_empty_batch(self, client, auth_headers, make_meal):
        assert (
            client.post(
                "/api/v1/sync/push", json={"operations": []}, headers=auth_headers
            ).status_code
            == 422
        )

    def test_requires_authentication(self, client, make_meal):
        assert (
            client.post(
                "/api/v1/sync/push", json={"operations": [push_op(make_meal, "op-1")]}
            ).status_code
            == 401
        )


class TestPull:
    def test_returns_everything_from_the_epoch(self, client, auth_headers, make_meal):
        client.post("/api/v1/meals", json=make_meal(), headers=auth_headers)
        body = client.get("/api/v1/sync/pull", headers=auth_headers).json()
        assert len(body["meals"]) == 1
        assert body["next_cursor"] is not None

    def test_a_cursor_excludes_what_was_already_seen(
        self, client, auth_headers, make_meal, new_email, password
    ):
        client.post("/api/v1/meals", json=make_meal(idempotency_key="p1"), headers=auth_headers)
        first = client.get("/api/v1/sync/pull", headers=auth_headers).json()

        second = client.get(
            f"/api/v1/sync/pull?since={first['next_cursor']}", headers=auth_headers
        ).json()
        assert second["meals"] == []
        assert second["has_more"] is False

    def test_reports_deletions_rather_than_omitting_them(
        self, client, auth_headers, make_meal, new_email, password
    ):
        # A vanished row would leave a stale copy on the device forever.
        meal_id = client.post("/api/v1/meals", json=make_meal(), headers=auth_headers).json()["id"]
        client.delete(f"/api/v1/meals/{meal_id}", headers=auth_headers)

        body = client.get("/api/v1/sync/pull", headers=auth_headers).json()
        assert meal_id in body["deleted_meal_ids"]
        assert body["meals"] == []

    def test_signals_more_pages(self, client, auth_headers, make_meal, new_email, password):
        for index in range(3):
            client.post(
                "/api/v1/meals",
                json=make_meal(idempotency_key=f"many-{index}"),
                headers=auth_headers,
            )
        body = client.get("/api/v1/sync/pull?limit=2", headers=auth_headers).json()
        assert body["has_more"] is True
        assert len(body["meals"]) == 2

    def test_does_not_leak_another_users_meals(
        self, client, auth_headers, make_meal, new_email, password
    ):

        client.post("/api/v1/meals", json=make_meal(), headers=auth_headers)
        other = client.post(
            "/api/v1/auth/register",
            json={"email": new_email(), "password": password},
        ).json()
        body = client.get(
            "/api/v1/sync/pull",
            headers={"Authorization": f"Bearer {other['access_token']}"},
        ).json()
        assert body["meals"] == []

    def test_reports_server_time(self, client, auth_headers):
        body = client.get("/api/v1/sync/pull", headers=auth_headers).json()
        # Clients use this to detect device clock skew before trusting their own.
        assert datetime.fromisoformat(body["server_time"]).tzinfo is not None
        assert (
            abs((datetime.fromisoformat(body["server_time"]) - datetime.now(UTC)).total_seconds())
            < 60
        )
