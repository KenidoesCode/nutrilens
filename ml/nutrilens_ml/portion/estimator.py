"""Volume estimation from a single uncalibrated photograph.

Monocular volume estimation is fundamentally ill-posed: one image does not
determine depth. Everything here is therefore an *estimate* built on stated
geometric assumptions, and every result carries a confidence that degrades as
those assumptions get weaker. Nothing in this module should ever be presented
to a user as a measurement.

Two modes are supported:

``reference``
    A reference object of known real-world size is present (a standard dinner
    plate by default, or a user-supplied object such as a credit card). Pixel
    area converts to real area through the reference scale, and real area
    converts to volume through a per-category height model.

``prior``
    No usable reference. The estimate falls back to typical serving sizes for
    the food category, modulated by the region's share of the plate. Confidence
    is capped low to reflect that this is barely more than a prior.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, replace

from ..domain import FoodCategory, PortionEstimate
from ..preprocessing.segmentation import Region

METHOD_REFERENCE = "reference-object"
METHOD_PRIOR = "serving-prior"

# Depth cannot be recovered from one image, so each category gets an effective
# mean height: the height of a uniform slab with the same volume as the real,
# mounded serving. Values are typical plating heights, not measurements.
CATEGORY_MEAN_HEIGHT_CM: dict[FoodCategory, float] = {
    FoodCategory.SOLID: 2.2,
    FoodCategory.SEMISOLID: 2.6,
    FoodCategory.LIQUID: 4.0,
}

# Typical single servings, used only when no reference scale is available.
CATEGORY_TYPICAL_SERVING_ML: dict[FoodCategory, float] = {
    FoodCategory.SOLID: 200.0,
    FoodCategory.SEMISOLID: 150.0,
    FoodCategory.LIQUID: 200.0,
}

MIN_VOLUME_ML = 5.0
MAX_VOLUME_ML = 3000.0

# Ceiling on how confident a monocular volume estimate is ever allowed to be.
# Depth is not recoverable from one image, so even a perfect reference object
# leaves real uncertainty in the height model.
REFERENCE_CONFIDENCE_CEILING = 0.85
PRIOR_BASE_CONFIDENCE = 0.42


@dataclass(frozen=True, slots=True)
class ReferenceObject:
    """A real-world object of known size visible in the frame.

    ``image_area_ratio`` is the share of the frame the object occupies.
    """

    name: str
    real_area_cm2: float
    image_area_ratio: float
    confidence: float

    def __post_init__(self) -> None:
        if self.real_area_cm2 <= 0:
            raise ValueError("real_area_cm2 must be positive")
        if not 0.0 < self.image_area_ratio <= 1.0:
            raise ValueError("image_area_ratio must be in (0, 1]")
        if not 0.0 <= self.confidence <= 1.0:
            raise ValueError("confidence must be in [0, 1]")

    @property
    def cm2_per_image_fraction(self) -> float:
        """Real square centimetres represented by one unit of image area ratio."""
        return self.real_area_cm2 / self.image_area_ratio

    @classmethod
    def standard_plate(cls, image_area_ratio: float) -> ReferenceObject:
        """A 26 cm dinner plate -- the default assumption when none is given."""
        diameter_cm = 26.0
        area = math.pi * (diameter_cm / 2.0) ** 2
        return cls(
            name="standard-dinner-plate-26cm",
            real_area_cm2=area,
            image_area_ratio=image_area_ratio,
            confidence=0.55,
        )


@dataclass(frozen=True, slots=True)
class ServingAssumptions:
    """Tunable assumptions behind an estimate, surfaced for auditability."""

    mean_height_cm: dict[FoodCategory, float]
    typical_serving_ml: dict[FoodCategory, float]
    plate_diameter_cm: float = 26.0

    def with_plate_diameter(self, diameter_cm: float) -> ServingAssumptions:
        if diameter_cm <= 0:
            raise ValueError("plate_diameter_cm must be positive")
        return replace(self, plate_diameter_cm=diameter_cm)


DEFAULT_SERVING_ASSUMPTIONS = ServingAssumptions(
    mean_height_cm=dict(CATEGORY_MEAN_HEIGHT_CM),
    typical_serving_ml=dict(CATEGORY_TYPICAL_SERVING_ML),
)


class PortionEstimator:
    """Turns a segmented region into a volume estimate with a confidence."""

    def __init__(self, assumptions: ServingAssumptions = DEFAULT_SERVING_ASSUMPTIONS) -> None:
        self._assumptions = assumptions

    def estimate(
        self,
        region: Region,
        category: FoodCategory,
        *,
        reference: ReferenceObject | None = None,
    ) -> PortionEstimate:
        if reference is not None:
            return self._estimate_with_reference(region, category, reference)

        plate_reference = self._plate_reference(region)
        if plate_reference is not None:
            return self._estimate_with_reference(region, category, plate_reference)

        return self._estimate_from_prior(region, category)

    def _plate_reference(self, region: Region) -> ReferenceObject | None:
        """Use the detected plate as an implicit reference when it looks sane.

        A plate filling almost the whole frame, or a sliver of one, gives a
        scale that is worse than the serving prior, so those are rejected.
        """
        plate_ratio = region.plate_area_ratio
        if not 0.12 <= plate_ratio <= 0.92:
            return None
        diameter = self._assumptions.plate_diameter_cm
        area = math.pi * (diameter / 2.0) ** 2
        return ReferenceObject(
            name=f"detected-plate-{diameter:g}cm",
            real_area_cm2=area,
            image_area_ratio=plate_ratio,
            confidence=0.5,
        )

    def _estimate_with_reference(
        self, region: Region, category: FoodCategory, reference: ReferenceObject
    ) -> PortionEstimate:
        real_area_cm2 = region.area_ratio * reference.cm2_per_image_fraction
        height_cm = self._assumptions.mean_height_cm[category]
        raw_volume_ml = real_area_cm2 * height_cm  # 1 cm^3 == 1 ml

        volume_ml = _clamp_volume(raw_volume_ml)
        confidence = self._reference_confidence(region, reference, raw_volume_ml, volume_ml)

        return PortionEstimate(
            volume_ml=round(volume_ml, 1),
            confidence=round(confidence, 4),
            method=METHOD_REFERENCE,
            assumptions={
                "reference_object": reference.name,
                "reference_area_cm2": round(reference.real_area_cm2, 2),
                "estimated_food_area_cm2": round(real_area_cm2, 2),
                "assumed_mean_height_cm": height_cm,
                "region_area_ratio": round(region.area_ratio, 4),
                "clamped": abs(raw_volume_ml - volume_ml) > 1e-6,
            },
        )

    def _reference_confidence(
        self,
        region: Region,
        reference: ReferenceObject,
        raw_volume_ml: float,
        volume_ml: float,
    ) -> float:
        # Maps the reference's own trustworthiness onto [ceiling/2, ceiling] so a
        # better reference always helps, and no reference can push the estimate
        # past the ceiling.
        confidence = REFERENCE_CONFIDENCE_CEILING * (0.5 + 0.5 * reference.confidence)

        # Tiny regions are dominated by segmentation error.
        if region.area_ratio < 0.03:
            confidence *= 0.7

        # A region whose bounding box is far larger than its mask is either
        # scattered or badly segmented; either way the area is less trustworthy.
        fill_ratio = region.area_ratio / max(region.bbox.area, 1e-6)
        if fill_ratio < 0.45:
            confidence *= 0.8

        # Hitting a clamp means the geometry produced something implausible.
        if abs(raw_volume_ml - volume_ml) > 1e-6:
            confidence *= 0.5

        return _clamp_unit(confidence)

    def _estimate_from_prior(self, region: Region, category: FoodCategory) -> PortionEstimate:
        typical_ml = self._assumptions.typical_serving_ml[category]
        # Scale the typical serving by how much of the plate the region covers,
        # relative to a "one dish fills a third of the plate" baseline.
        share = region.area_ratio_of_plate
        scale = _clamp(share / 0.33, 0.35, 2.5)
        raw_volume_ml = typical_ml * scale
        volume_ml = _clamp_volume(raw_volume_ml)

        confidence = PRIOR_BASE_CONFIDENCE
        if region.area_ratio < 0.03:
            confidence *= 0.75

        return PortionEstimate(
            volume_ml=round(volume_ml, 1),
            confidence=round(_clamp_unit(confidence), 4),
            method=METHOD_PRIOR,
            assumptions={
                "typical_serving_ml": typical_ml,
                "plate_share": round(share, 4),
                "scale_applied": round(scale, 3),
                "reason": "no usable reference object in frame",
            },
        )

    def apply_user_correction(
        self, estimate: PortionEstimate, corrected_volume_ml: float
    ) -> PortionEstimate:
        """Replace an estimate with a user-supplied volume.

        A human correction is the most reliable signal available, so it is
        recorded at high confidence -- but not 1.0, because the person is also
        estimating.
        """
        if corrected_volume_ml <= 0:
            raise ValueError("corrected_volume_ml must be positive")
        if corrected_volume_ml > MAX_VOLUME_ML:
            raise ValueError(f"corrected_volume_ml must not exceed {MAX_VOLUME_ML} ml")
        return PortionEstimate(
            volume_ml=round(float(corrected_volume_ml), 1),
            confidence=0.9,
            method="user-corrected",
            assumptions={
                "superseded_method": estimate.method,
                "superseded_volume_ml": estimate.volume_ml,
            },
        )


def _clamp(value: float, low: float, high: float) -> float:
    return max(low, min(high, value))


def _clamp_unit(value: float) -> float:
    return _clamp(value, 0.0, 1.0)


def _clamp_volume(volume_ml: float) -> float:
    if not math.isfinite(volume_ml):
        return MIN_VOLUME_ML
    return _clamp(volume_ml, MIN_VOLUME_ML, MAX_VOLUME_ML)
