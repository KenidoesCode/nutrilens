"""Profile, export and account deletion."""

from __future__ import annotations


class TestProfile:
    def test_returns_the_current_profile(self, client, auth_headers, registered_user):
        body = client.get("/api/v1/users/me", headers=auth_headers).json()
        assert body["email"] == registered_user["email"]
        assert body["timezone"] == "Asia/Kolkata"
        assert "password_hash" not in body

    def test_updates_the_timezone_and_locale(self, client, auth_headers):
        body = client.patch(
            "/api/v1/users/me",
            json={"timezone": "America/New_York", "locale": "te"},
            headers=auth_headers,
        ).json()
        assert body["timezone"] == "America/New_York"
        assert body["locale"] == "te"

    def test_rejects_an_unknown_timezone(self, client, auth_headers):
        assert (
            client.patch(
                "/api/v1/users/me", json={"timezone": "Mars/Olympus"}, headers=auth_headers
            ).status_code
            == 422
        )

    def test_rejects_an_unsupported_locale(self, client, auth_headers):
        assert (
            client.patch(
                "/api/v1/users/me", json={"locale": "fr"}, headers=auth_headers
            ).status_code
            == 422
        )


class TestDataExport:
    def test_exports_the_profile_and_every_meal(self, client, auth_headers, make_meal):
        for index in range(3):
            client.post(
                "/api/v1/meals",
                json=make_meal(idempotency_key=f"x{index}"),
                headers=auth_headers,
            )
        body = client.get("/api/v1/users/me/export", headers=auth_headers).json()
        assert body["meal_count"] == 3
        assert len(body["meals"]) == 3
        assert body["profile"]["email"]

    def test_export_contains_no_credentials(self, client, auth_headers, password):
        body = client.get("/api/v1/users/me/export", headers=auth_headers).text
        assert "password" not in body.lower()
        assert "token" not in body.lower()

    def test_requires_authentication(self, client):
        assert client.get("/api/v1/users/me/export").status_code == 401


class TestAccountDeletion:
    def test_deletes_the_account_and_its_meals(self, client, auth_headers, make_meal):
        client.post("/api/v1/meals", json=make_meal(), headers=auth_headers)
        assert client.delete("/api/v1/users/me", headers=auth_headers).status_code == 204
        # Every session is revoked, so the token no longer works.
        assert client.get("/api/v1/users/me", headers=auth_headers).status_code == 401

    def test_the_account_can_no_longer_sign_in(
        self, client, auth_headers, registered_user, password
    ):
        client.delete("/api/v1/users/me", headers=auth_headers)
        response = client.post(
            "/api/v1/auth/login",
            json={"email": registered_user["email"], "password": password},
        )
        assert response.status_code == 401

    def test_the_email_can_be_registered_again(
        self, client, auth_headers, registered_user, password
    ):
        client.delete("/api/v1/users/me", headers=auth_headers)
        response = client.post(
            "/api/v1/auth/register",
            json={"email": registered_user["email"], "password": password},
        )
        assert response.status_code == 201
