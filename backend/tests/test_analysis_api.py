"""Meal image analysis endpoint: contract, validation and uncertainty."""

from __future__ import annotations

import io

import numpy as np
from PIL import Image


def _upload(content: bytes, filename: str = "meal.jpg", content_type: str = "image/jpeg"):
    return {"image": (filename, content, content_type)}


class TestAnalysisHappyPath:
    def test_detects_foods_and_returns_estimates(self, client, auth_headers, meal_photo):
        response = client.post(
            "/api/v1/analysis/meal-image", files=_upload(meal_photo), headers=auth_headers
        )
        assert response.status_code == 200, response.text
        body = response.json()
        assert len(body["items"]) >= 1
        assert body["engine"]
        assert body["processing_ms"] >= 0

    def test_every_item_exposes_its_uncertainty(self, client, auth_headers, meal_photo):
        body = client.post(
            "/api/v1/analysis/meal-image", files=_upload(meal_photo), headers=auth_headers
        ).json()
        for item in body["items"]:
            assert 0.0 <= item["confidence"] <= 1.0
            assert item["confidence_band"] in {"low", "medium", "high"}
            assert 0.0 <= item["portion_confidence"] <= 1.0
            assert 0.0 <= item["overall_confidence"] <= 1.0
            assert isinstance(item["is_fallback_density"], bool)

    def test_marks_the_response_as_approximate(self, client, auth_headers, meal_photo):
        # The contract must never let a client mistake an estimate for a measurement.
        body = client.post(
            "/api/v1/analysis/meal-image", files=_upload(meal_photo), headers=auth_headers
        ).json()
        assert body["estimates_are_approximate"] is True

    def test_mass_is_consistent_with_volume_and_density(self, client, auth_headers, meal_photo):
        body = client.post(
            "/api/v1/analysis/meal-image", files=_upload(meal_photo), headers=auth_headers
        ).json()
        for item in body["items"]:
            expected = item["estimated_volume_ml"] * item["density_g_per_ml"]
            assert abs(item["estimated_mass_g"] - expected) < 0.15

    def test_is_deterministic_for_the_same_photograph(self, client, auth_headers, meal_photo):
        first = client.post(
            "/api/v1/analysis/meal-image", files=_upload(meal_photo), headers=auth_headers
        ).json()
        second = client.post(
            "/api/v1/analysis/meal-image", files=_upload(meal_photo), headers=auth_headers
        ).json()
        assert first["items"] == second["items"]

    def test_a_prediction_can_be_linked_to_a_meal(
        self, client, auth_headers, meal_photo, make_meal
    ):

        analysis = client.post(
            "/api/v1/analysis/meal-image", files=_upload(meal_photo), headers=auth_headers
        ).json()
        payload = make_meal(prediction_id=analysis["prediction_id"])
        assert client.post("/api/v1/meals", json=payload, headers=auth_headers).status_code == 201

    def test_a_featureless_image_reports_no_food_rather_than_inventing_it(
        self, client, auth_headers
    ):
        buffer = io.BytesIO()
        Image.fromarray(np.full((320, 320, 3), 250, dtype=np.uint8)).save(
            buffer, format="JPEG", quality=95
        )
        body = client.post(
            "/api/v1/analysis/meal-image",
            files=_upload(buffer.getvalue()),
            headers=auth_headers,
        ).json()
        assert body["items"] == []
        assert "no_food_detected" in body["warnings"]


class TestAnalysisValidation:
    def test_requires_authentication(self, client, meal_photo):
        assert (
            client.post("/api/v1/analysis/meal-image", files=_upload(meal_photo)).status_code == 401
        )

    def test_rejects_an_unsupported_content_type(self, client, auth_headers):
        response = client.post(
            "/api/v1/analysis/meal-image",
            files=_upload(b"GIF89a", "meal.gif", "image/gif"),
            headers=auth_headers,
        )
        assert response.status_code == 415
        assert response.json()["error"]["code"] == "UNSUPPORTED_MEDIA_TYPE"

    def test_rejects_bytes_that_are_not_an_image(self, client, auth_headers):
        response = client.post(
            "/api/v1/analysis/meal-image",
            files=_upload(b"this is not an image at all"),
            headers=auth_headers,
        )
        assert response.status_code == 422
        assert response.json()["error"]["code"] == "INVALID_IMAGE"

    def test_rejects_a_jpeg_extension_wrapping_a_non_image(self, client, auth_headers):
        # A truthful content-type header does not make the payload an image.
        response = client.post(
            "/api/v1/analysis/meal-image",
            files=_upload(b"\x00" * 4096, "photo.jpg", "image/jpeg"),
            headers=auth_headers,
        )
        assert response.status_code == 422

    def test_rejects_an_oversized_upload(self, client, auth_headers, app):
        oversized = b"\xff\xd8\xff" + b"\x00" * (app.state.settings.max_upload_bytes + 1024)
        response = client.post(
            "/api/v1/analysis/meal-image", files=_upload(oversized), headers=auth_headers
        )
        assert response.status_code == 413
        assert response.json()["error"]["code"] == "IMAGE_TOO_LARGE"

    def test_rejects_an_incomplete_reference_object(self, client, auth_headers, meal_photo):
        response = client.post(
            "/api/v1/analysis/meal-image",
            files=_upload(meal_photo),
            data={"reference_name": "credit-card"},
            headers=auth_headers,
        )
        assert response.status_code == 422


class TestReferenceObject:
    def test_a_reference_object_changes_the_scale(self, client, auth_headers, meal_photo):
        default = client.post(
            "/api/v1/analysis/meal-image", files=_upload(meal_photo), headers=auth_headers
        ).json()
        with_card = client.post(
            "/api/v1/analysis/meal-image",
            files=_upload(meal_photo),
            data={
                "reference_name": "credit-card",
                "reference_real_area_cm2": "46.0",
                "reference_image_area_ratio": "0.01",
            },
            headers=auth_headers,
        ).json()
        assert (
            default["items"][0]["estimated_volume_ml"]
            != with_card["items"][0]["estimated_volume_ml"]
        )
