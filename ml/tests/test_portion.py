"""Portion estimation: geometry, fallbacks, clamping and user correction."""

from __future__ import annotations

import numpy as np
import pytest

from nutrilens_ml.domain import BoundingBox, FoodCategory
from nutrilens_ml.portion.estimator import (
    MAX_VOLUME_ML,
    METHOD_PRIOR,
    METHOD_REFERENCE,
    MIN_VOLUME_ML,
    PortionEstimator,
    ReferenceObject,
)
from nutrilens_ml.preprocessing.segmentation import Region


def make_region(
    area_ratio: float = 0.10,
    plate_area_ratio: float = 0.45,
    bbox: BoundingBox | None = None,
) -> Region:
    return Region(
        mask=np.ones((4, 4), dtype=bool),
        area_ratio=area_ratio,
        bbox=bbox or BoundingBox(x=0.3, y=0.3, width=0.34, height=0.34),
        mean_hue_deg=45.0,
        mean_saturation=0.3,
        mean_value=0.7,
        hue_std_deg=8.0,
        texture_score=0.03,
        plate_area_ratio=plate_area_ratio,
    )


@pytest.fixture
def estimator():
    return PortionEstimator()


class TestReferenceObject:
    def test_standard_plate_area_matches_a_26cm_circle(self):
        reference = ReferenceObject.standard_plate(0.5)
        assert reference.real_area_cm2 == pytest.approx(530.929, rel=1e-3)

    def test_scale_factor_inverts_the_image_share(self):
        reference = ReferenceObject(
            "card", real_area_cm2=46.0, image_area_ratio=0.02, confidence=0.9
        )
        assert reference.cm2_per_image_fraction == pytest.approx(2300.0)

    @pytest.mark.parametrize(
        "kwargs",
        [
            {"real_area_cm2": 0.0, "image_area_ratio": 0.1, "confidence": 0.5},
            {"real_area_cm2": 10.0, "image_area_ratio": 0.0, "confidence": 0.5},
            {"real_area_cm2": 10.0, "image_area_ratio": 1.5, "confidence": 0.5},
            {"real_area_cm2": 10.0, "image_area_ratio": 0.1, "confidence": 1.4},
        ],
    )
    def test_rejects_invalid_construction(self, kwargs):
        with pytest.raises(ValueError):
            ReferenceObject(name="x", **kwargs)


class TestReferenceMode:
    def test_uses_the_reference_when_one_is_supplied(self, estimator):
        reference = ReferenceObject.standard_plate(0.5)
        estimate = estimator.estimate(make_region(), FoodCategory.SOLID, reference=reference)
        assert estimate.method == METHOD_REFERENCE
        assert estimate.assumptions["reference_object"] == "standard-dinner-plate-26cm"

    def test_volume_equals_area_times_assumed_height(self, estimator):
        # 0.10 of the frame, where 0.5 of the frame is a 530.93 cm2 plate
        # => 106.19 cm2 of food; x 2.2 cm assumed height => 233.6 ml.
        reference = ReferenceObject.standard_plate(0.5)
        estimate = estimator.estimate(
            make_region(area_ratio=0.10), FoodCategory.SOLID, reference=reference
        )
        assert estimate.volume_ml == pytest.approx(233.6, rel=1e-2)

    def test_liquids_get_a_taller_height_model(self, estimator):
        reference = ReferenceObject.standard_plate(0.5)
        region = make_region()
        solid = estimator.estimate(region, FoodCategory.SOLID, reference=reference)
        liquid = estimator.estimate(region, FoodCategory.LIQUID, reference=reference)
        assert liquid.volume_ml > solid.volume_ml

    def test_falls_back_to_the_detected_plate_without_an_explicit_reference(self, estimator):
        estimate = estimator.estimate(make_region(), FoodCategory.SOLID)
        assert estimate.method == METHOD_REFERENCE
        assert estimate.assumptions["reference_object"].startswith("detected-plate")

    def test_confidence_drops_for_a_scattered_region(self, estimator):
        reference = ReferenceObject.standard_plate(0.5)
        compact = estimator.estimate(
            make_region(area_ratio=0.10, bbox=BoundingBox(0.3, 0.3, 0.34, 0.34)),
            FoodCategory.SOLID,
            reference=reference,
        )
        scattered = estimator.estimate(
            make_region(area_ratio=0.10, bbox=BoundingBox(0.05, 0.05, 0.9, 0.9)),
            FoodCategory.SOLID,
            reference=reference,
        )
        assert scattered.confidence < compact.confidence

    def test_confidence_drops_for_a_tiny_region(self, estimator):
        reference = ReferenceObject.standard_plate(0.5)
        big = estimator.estimate(
            make_region(area_ratio=0.10), FoodCategory.SOLID, reference=reference
        )
        tiny = estimator.estimate(
            make_region(area_ratio=0.02, bbox=BoundingBox(0.3, 0.3, 0.16, 0.16)),
            FoodCategory.SOLID,
            reference=reference,
        )
        assert tiny.confidence < big.confidence


class TestPriorMode:
    def test_used_when_the_plate_is_implausible(self, estimator):
        estimate = estimator.estimate(
            make_region(area_ratio=0.05, plate_area_ratio=0.99), FoodCategory.SEMISOLID
        )
        assert estimate.method == METHOD_PRIOR
        assert "reason" in estimate.assumptions

    def test_is_less_confident_than_reference_mode(self, estimator):
        prior = estimator.estimate(make_region(plate_area_ratio=0.99), FoodCategory.SOLID)
        reference = estimator.estimate(
            make_region(), FoodCategory.SOLID, reference=ReferenceObject.standard_plate(0.5)
        )
        assert prior.confidence < reference.confidence

    def test_scales_with_the_share_of_the_plate(self, estimator):
        small = estimator.estimate(
            make_region(area_ratio=0.05, plate_area_ratio=0.99), FoodCategory.SOLID
        )
        large = estimator.estimate(
            make_region(area_ratio=0.40, plate_area_ratio=0.99), FoodCategory.SOLID
        )
        assert large.volume_ml > small.volume_ml


class TestClamping:
    def test_absurd_geometry_is_clamped_and_penalised(self, estimator):
        # A "reference" claiming the plate is a sliver of the frame implies a
        # gigantic real-world scale; the result must be capped, not reported.
        reference = ReferenceObject(
            "tiny", real_area_cm2=530.9, image_area_ratio=0.001, confidence=0.9
        )
        estimate = estimator.estimate(
            make_region(area_ratio=0.5), FoodCategory.SOLID, reference=reference
        )
        assert estimate.volume_ml == MAX_VOLUME_ML
        assert estimate.assumptions["clamped"] is True
        assert estimate.confidence < 0.5

    def test_never_returns_a_non_positive_volume(self, estimator):
        estimate = estimator.estimate(make_region(area_ratio=1e-6), FoodCategory.SOLID)
        assert estimate.volume_ml >= MIN_VOLUME_ML


class TestUserCorrection:
    def test_replaces_the_volume_and_records_what_it_superseded(self, estimator):
        original = estimator.estimate(make_region(), FoodCategory.SOLID)
        corrected = estimator.apply_user_correction(original, 120.0)
        assert corrected.volume_ml == 120.0
        assert corrected.method == "user-corrected"
        assert corrected.assumptions["superseded_volume_ml"] == original.volume_ml

    def test_is_more_confident_than_any_automatic_estimate(self, estimator):
        original = estimator.estimate(make_region(), FoodCategory.SOLID)
        assert estimator.apply_user_correction(original, 120.0).confidence > original.confidence

    @pytest.mark.parametrize("value", [0.0, -5.0, MAX_VOLUME_ML + 1])
    def test_rejects_implausible_corrections(self, estimator, value):
        original = estimator.estimate(make_region(), FoodCategory.SOLID)
        with pytest.raises(ValueError):
            estimator.apply_user_correction(original, value)
