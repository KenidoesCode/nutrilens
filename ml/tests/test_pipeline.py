"""End-to-end pipeline behaviour, including its failure modes."""

from __future__ import annotations

import numpy as np
import pytest

from nutrilens_ml.catalog import load_catalog
from nutrilens_ml.domain import BoundingBox, Detection, FoodCategory
from nutrilens_ml.inference.base import FoodRecognizer
from nutrilens_ml.inference.factory import EngineConfig
from nutrilens_ml.nutrition import NutritionEstimator
from nutrilens_ml.pipeline import AnalysisOptions, MealAnalysisPipeline
from nutrilens_ml.portion.estimator import ReferenceObject


@pytest.fixture
def pipeline():
    return MealAnalysisPipeline.from_config(EngineConfig())


class StubRecognizer(FoodRecognizer):
    """Emits a fixed detection so pipeline wiring can be tested in isolation."""

    def __init__(self, detections: list[Detection]) -> None:
        self._detections = detections

    @property
    def name(self) -> str:
        return "stub"

    @property
    def model_version(self) -> str:
        return "stub-1"

    def recognize(self, image):
        return self._detections


class TestHappyPath:
    def test_produces_one_item_per_dish(self, pipeline, plate_bytes):
        result = pipeline.analyze_bytes(plate_bytes, declared_mime="image/jpeg")
        assert len(result.items) == 3
        assert result.warnings == []

    def test_every_item_has_a_plausible_mass(self, pipeline, plate_bytes):
        for item in pipeline.analyze_bytes(plate_bytes).items:
            assert 1.0 < item.mass.mass_g < 1500.0

    def test_mass_equals_volume_times_density(self, pipeline, plate_bytes):
        for item in pipeline.analyze_bytes(plate_bytes).items:
            expected = item.portion.volume_ml * item.mass.density_g_per_ml
            assert item.mass.mass_g == pytest.approx(expected, rel=1e-9)

    def test_records_the_engine_and_model_version(self, pipeline, plate_bytes):
        result = pipeline.analyze_bytes(plate_bytes)
        assert result.engine == "heuristic-color-texture"
        assert load_catalog().dataset_version in result.model_version

    def test_reports_processing_time(self, pipeline, plate_bytes):
        assert pipeline.analyze_bytes(plate_bytes).processing_ms >= 0

    def test_is_deterministic(self, pipeline, plate_bytes):
        first = pipeline.analyze_bytes(plate_bytes).to_dict()["items"]
        second = pipeline.analyze_bytes(plate_bytes).to_dict()["items"]
        assert first == second

    def test_total_mass_is_the_sum_of_items(self, pipeline, plate_bytes):
        result = pipeline.analyze_bytes(plate_bytes)
        assert result.total_mass_g == pytest.approx(sum(item.mass.mass_g for item in result.items))


class TestSerialisedContract:
    def test_matches_the_documented_shape(self, pipeline, plate_bytes):
        payload = pipeline.analyze_bytes(plate_bytes).to_dict()
        assert set(payload) == {
            "items",
            "engine",
            "model_version",
            "processing_ms",
            "total_estimated_mass_g",
            "warnings",
        }
        item = payload["items"][0]
        for key in (
            "name",
            "category",
            "confidence",
            "estimated_volume_ml",
            "estimated_mass_g",
        ):
            assert key in item

    def test_category_is_one_of_the_three_states(self, pipeline, plate_bytes):
        for item in pipeline.analyze_bytes(plate_bytes).to_dict()["items"]:
            assert item["category"] in {"solid", "semisolid", "liquid"}

    def test_confidences_are_probabilities(self, pipeline, plate_bytes):
        for item in pipeline.analyze_bytes(plate_bytes).to_dict()["items"]:
            for key in ("confidence", "portion_confidence", "overall_confidence"):
                assert 0.0 <= item[key] <= 1.0


class TestDegradedPaths:
    def test_empty_frame_yields_a_warning_not_an_error(self, pipeline, encode_image):
        blank = encode_image(np.full((320, 320, 3), 250, dtype=np.uint8))
        result = pipeline.analyze_bytes(blank)
        assert result.items == []
        assert "no_food_detected" in result.warnings

    def test_unknown_food_falls_back_to_a_category_density(self, plate_image):
        detection = Detection(
            label="unheard-of dish",
            category=FoodCategory.SOLID,
            confidence=0.6,
            bbox=BoundingBox(0.2, 0.25, 0.3, 0.3),
            pixel_area_ratio=0.09,
            engine="stub",
        )
        pipeline = MealAnalysisPipeline(StubRecognizer([detection]))
        result = pipeline.analyze(plate_image)
        assert len(result.items) == 1
        assert result.items[0].mass.is_fallback_density is True
        assert "nutrition_unavailable:unheard-of dish" in result.warnings

    def test_max_items_is_enforced(self, plate_image):
        detections = [
            Detection(
                label="Rice",
                category=FoodCategory.SOLID,
                confidence=0.6,
                bbox=BoundingBox(0.2, 0.25, 0.3, 0.3),
                pixel_area_ratio=0.09,
                engine="stub",
            )
            for _ in range(5)
        ]
        pipeline = MealAnalysisPipeline(StubRecognizer(detections))
        result = pipeline.analyze(plate_image, options=AnalysisOptions(max_items=2))
        assert len(result.items) <= 2

    def test_nutrition_can_be_switched_off(self, pipeline, plate_image):
        result = pipeline.analyze(plate_image, options=AnalysisOptions(include_nutrition=False))
        assert all(item.nutrition is None for item in result.items)


class TestReferenceObjectPath:
    def test_an_explicit_reference_changes_the_estimate(self, pipeline, plate_image):
        default = pipeline.analyze(plate_image)
        # A credit card is far smaller than a plate, so the implied scale and
        # therefore the volumes must come out very different.
        card = ReferenceObject(
            "credit-card", real_area_cm2=46.0, image_area_ratio=0.01, confidence=0.9
        )
        with_card = pipeline.analyze(plate_image, options=AnalysisOptions(reference=card))
        assert default.items[0].portion.volume_ml != with_card.items[0].portion.volume_ml
        assert with_card.items[0].portion.assumptions["reference_object"] == "credit-card"


class TestNutritionEstimator:
    def test_scales_linearly_with_mass(self):
        estimator = NutritionEstimator()
        hundred = estimator.estimate("rice", 100.0)
        two_hundred = estimator.estimate("rice", 200.0)
        assert two_hundred.energy_kcal == pytest.approx(hundred.energy_kcal * 2)

    def test_returns_none_for_unknown_foods(self):
        # None and zero must not be confusable downstream.
        assert NutritionEstimator().estimate("unheard-of dish", 100.0) is None

    def test_rejects_negative_mass(self):
        with pytest.raises(ValueError):
            NutritionEstimator().estimate("rice", -1.0)
