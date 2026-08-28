"""Authentication endpoint behaviour, including its security properties."""

from __future__ import annotations

import pytest


class TestRegistration:
    def test_creates_an_account_and_returns_tokens(self, client, new_email, password):
        response = client.post(
            "/api/v1/auth/register",
            json={"email": new_email(), "password": password},
        )
        assert response.status_code == 201
        body = response.json()
        assert body["access_token"] and body["refresh_token"]
        assert body["token_type"] == "bearer"

    def test_rejects_a_duplicate_email(self, client, new_email, password):
        email = new_email()
        payload = {"email": email, "password": password}
        assert client.post("/api/v1/auth/register", json=payload).status_code == 201

        response = client.post("/api/v1/auth/register", json=payload)
        assert response.status_code == 409
        assert response.json()["error"]["code"] == "EMAIL_ALREADY_REGISTERED"

    def test_email_case_does_not_create_a_second_account(self, client, new_email, password):
        local = new_email()
        client.post("/api/v1/auth/register", json={"email": local, "password": password})
        response = client.post(
            "/api/v1/auth/register",
            json={"email": local.upper(), "password": password},
        )
        assert response.status_code == 409

    @pytest.mark.parametrize("password", ["short", "password123", "aaaaaaaaaaaaaa", "12345678901"])
    def test_rejects_weak_passwords(self, client, password, new_email):
        response = client.post(
            "/api/v1/auth/register", json={"email": new_email(), "password": password}
        )
        assert response.status_code == 422

    def test_rejects_an_unknown_timezone(self, client, new_email, password):
        response = client.post(
            "/api/v1/auth/register",
            json={
                "email": new_email(),
                "password": password,
                "timezone": "Mars/Olympus_Mons",
            },
        )
        assert response.status_code == 422

    def test_rejects_an_unsupported_locale(self, client, new_email, password):
        response = client.post(
            "/api/v1/auth/register",
            json={"email": new_email(), "password": password, "locale": "fr"},
        )
        assert response.status_code == 422

    def test_never_returns_the_password_hash(self, client, new_email, password):
        response = client.post(
            "/api/v1/auth/register",
            json={"email": new_email(), "password": password},
        )
        assert "password" not in response.text.lower()


class TestLogin:
    def test_succeeds_with_correct_credentials(self, client, registered_user, password):
        response = client.post(
            "/api/v1/auth/login",
            json={"email": registered_user["email"], "password": password},
        )
        assert response.status_code == 200
        assert response.json()["access_token"]

    def test_rejects_a_wrong_password(self, client, registered_user):
        response = client.post(
            "/api/v1/auth/login",
            json={"email": registered_user["email"], "password": "wrong-password-here"},
        )
        assert response.status_code == 401
        assert response.json()["error"]["code"] == "INVALID_CREDENTIALS"

    def test_unknown_and_wrong_password_are_indistinguishable(
        self, client, registered_user, new_email, password
    ):
        # Different responses here would let an attacker enumerate accounts.
        unknown = client.post(
            "/api/v1/auth/login",
            json={"email": new_email(), "password": password},
        )
        wrong = client.post(
            "/api/v1/auth/login",
            json={"email": registered_user["email"], "password": "wrong-password-here"},
        )
        assert unknown.status_code == wrong.status_code == 401
        assert unknown.json()["error"]["code"] == wrong.json()["error"]["code"]
        assert unknown.json()["error"]["message"] == wrong.json()["error"]["message"]


class TestRefreshRotation:
    def test_exchanges_a_refresh_token_for_a_new_pair(self, client, registered_user):
        response = client.post(
            "/api/v1/auth/refresh",
            json={"refresh_token": registered_user["refresh_token"]},
        )
        assert response.status_code == 200
        assert response.json()["refresh_token"] != registered_user["refresh_token"]

    def test_the_old_refresh_token_stops_working(self, client, registered_user):
        original = registered_user["refresh_token"]
        first = client.post("/api/v1/auth/refresh", json={"refresh_token": original})
        assert first.status_code == 200

        replay = client.post("/api/v1/auth/refresh", json={"refresh_token": original})
        assert replay.status_code == 401

    def test_an_access_token_is_rejected_where_a_refresh_token_is_required(
        self, client, registered_user
    ):
        response = client.post(
            "/api/v1/auth/refresh",
            json={"refresh_token": registered_user["access_token"]},
        )
        assert response.status_code == 401

    def test_a_garbage_token_is_rejected(self, client):
        response = client.post("/api/v1/auth/refresh", json={"refresh_token": "not.a.jwt"})
        assert response.status_code == 401


class TestLogout:
    def test_revokes_the_session(self, client, registered_user):
        token = registered_user["refresh_token"]
        assert client.post("/api/v1/auth/logout", json={"refresh_token": token}).status_code == 204
        assert client.post("/api/v1/auth/refresh", json={"refresh_token": token}).status_code == 401

    def test_is_idempotent(self, client, registered_user):
        token = registered_user["refresh_token"]
        client.post("/api/v1/auth/logout", json={"refresh_token": token})
        assert client.post("/api/v1/auth/logout", json={"refresh_token": token}).status_code == 204


class TestProtectedRoutes:
    def test_requires_a_token(self, client):
        response = client.get("/api/v1/users/me")
        assert response.status_code == 401
        assert response.json()["error"]["code"] == "NOT_AUTHENTICATED"

    def test_rejects_a_forged_token(self, client):
        response = client.get(
            "/api/v1/users/me", headers={"Authorization": "Bearer forged.token.value"}
        )
        assert response.status_code == 401

    def test_a_refresh_token_cannot_be_used_as_an_access_token(self, client, registered_user):
        response = client.get(
            "/api/v1/users/me",
            headers={"Authorization": f"Bearer {registered_user['refresh_token']}"},
        )
        assert response.status_code == 401

    def test_accepts_a_valid_token(self, client, auth_headers, registered_user):
        response = client.get("/api/v1/users/me", headers=auth_headers)
        assert response.status_code == 200
        assert response.json()["email"] == registered_user["email"]
