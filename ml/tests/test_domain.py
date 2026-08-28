"""Value-type invariants. These types are the pipeline's guard rails."""

from __future__ import annotations

import pytest

from nutrilens_ml.domain import (
    AnalyzedFoodItem,
    BoundingBox,
    ConfidenceBand,
    Detection,
    FoodCategory,
    MassEstimate,
    NutritionEstimate,
    PortionEstimate,
)


class TestFoodCategory:
    def test_parses_case_insensitively(self):
        assert FoodCategory.parse("  SOLID ") is FoodCategory.SOLID

    def test_rejects_unknown_values(self):
        with pytest.raises(ValueError):
            FoodCategory.parse("plasma")


class TestConfidenceBand:
    @pytest.mark.parametrize(
        ("score", "band"),
        [
            (0.0, ConfidenceBand.LOW),
            (0.54, ConfidenceBand.LOW),
            (0.55, ConfidenceBand.MEDIUM),
            (0.79, ConfidenceBand.MEDIUM),
            (0.80, ConfidenceBand.HIGH),
            (1.0, ConfidenceBand.HIGH),
        ],
    )
    def test_boundaries(self, score, band):
        assert ConfidenceBand.from_score(score) is band

    @pytest.mark.parametrize("score", [-0.01, 1.01])
    def test_rejects_out_of_range(self, score):
        with pytest.raises(ValueError):
            ConfidenceBand.from_score(score)


class TestBoundingBox:
    def test_accepts_a_full_frame_box(self):
        assert BoundingBox(0.0, 0.0, 1.0, 1.0).area == 1.0

    @pytest.mark.parametrize(
        "kwargs",
        [
            {"x": -0.1, "y": 0.0, "width": 0.5, "height": 0.5},
            {"x": 0.0, "y": 0.0, "width": 0.0, "height": 0.5},
            {"x": 0.8, "y": 0.0, "width": 0.5, "height": 0.5},
            {"x": 0.0, "y": 0.0, "width": float("nan"), "height": 0.5},
        ],
    )
    def test_rejects_invalid_geometry(self, kwargs):
        with pytest.raises(ValueError):
            BoundingBox(**kwargs)


class TestDetection:
    def test_rejects_blank_label(self):
        with pytest.raises(ValueError):
            Detection("  ", FoodCategory.SOLID, 0.5, BoundingBox(0, 0, 1, 1), 0.5, "e")

    @pytest.mark.parametrize("confidence", [-0.1, 1.1])
    def test_rejects_out_of_range_confidence(self, confidence):
        with pytest.raises(ValueError):
            Detection("rice", FoodCategory.SOLID, confidence, BoundingBox(0, 0, 1, 1), 0.5, "e")

    def test_rejects_zero_pixel_area(self):
        with pytest.raises(ValueError):
            Detection("rice", FoodCategory.SOLID, 0.5, BoundingBox(0, 0, 1, 1), 0.0, "e")


class TestOverallConfidence:
    def _item(self, recognition: float, portion: float, density: float) -> AnalyzedFoodItem:
        return AnalyzedFoodItem(
            label="Rice",
            category=FoodCategory.SOLID,
            recognition_confidence=recognition,
            portion=PortionEstimate(180.0, portion, "reference-object"),
            mass=MassEstimate(153.0, 0.85, "catalog@1", density, False),
            nutrition=NutritionEstimate(234.0, 4.9, 50.8, 0.5, "catalog@1"),
            bbox=BoundingBox(0.1, 0.1, 0.3, 0.3),
            engine="test",
        )

    def test_is_the_product_of_the_three_stages(self):
        assert self._item(0.9, 0.8, 0.7).overall_confidence == pytest.approx(0.504)

    def test_a_weak_stage_dominates(self):
        # A perfect food match on an unmeasurable portion must not read as confident.
        assert self._item(1.0, 0.1, 1.0).overall_confidence == pytest.approx(0.1)

    def test_serialisation_exposes_uncertainty_fields(self):
        payload = self._item(0.9, 0.8, 0.7).to_dict()
        for key in (
            "confidence",
            "confidence_band",
            "portion_confidence",
            "overall_confidence",
            "is_fallback_density",
        ):
            assert key in payload
