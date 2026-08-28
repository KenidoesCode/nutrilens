"""Shared, transport-agnostic value types for the NutriLens ML pipeline.

These types are the contract between the inference engines, the portion
estimator, the density engine and every consumer (backend API, CLI, tests).
They are deliberately free of any framework or I/O concern.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any


class FoodCategory(StrEnum):
    """Physical state of a food item.

    The category drives portion geometry (how volume is derived from a
    segmented region) and density lookup.
    """

    SOLID = "solid"
    SEMISOLID = "semisolid"
    LIQUID = "liquid"

    @classmethod
    def parse(cls, value: str) -> FoodCategory:
        try:
            return cls(value.strip().lower())
        except ValueError as exc:  # pragma: no cover - defensive
            raise ValueError(f"Unknown food category: {value!r}") from exc


class ConfidenceBand(StrEnum):
    """Human-facing bucketing of a confidence score.

    The UI must never present an estimate as a measurement, so every numeric
    confidence is also expressed as a band that can be localised.
    """

    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"

    @classmethod
    def from_score(cls, score: float) -> ConfidenceBand:
        if not 0.0 <= score <= 1.0:
            raise ValueError(f"confidence must be in [0, 1], got {score}")
        if score < 0.55:
            return cls.LOW
        if score < 0.80:
            return cls.MEDIUM
        return cls.HIGH


@dataclass(frozen=True, slots=True)
class BoundingBox:
    """Axis-aligned box in normalised image coordinates ([0, 1])."""

    x: float
    y: float
    width: float
    height: float

    def __post_init__(self) -> None:
        for name, value in (
            ("x", self.x),
            ("y", self.y),
            ("width", self.width),
            ("height", self.height),
        ):
            if not math.isfinite(value):
                raise ValueError(f"BoundingBox.{name} must be finite, got {value}")
        if not 0.0 <= self.x <= 1.0 or not 0.0 <= self.y <= 1.0:
            raise ValueError("BoundingBox origin must lie within [0, 1]")
        if self.width <= 0.0 or self.height <= 0.0:
            raise ValueError("BoundingBox must have positive extent")
        if self.x + self.width > 1.0 + 1e-6 or self.y + self.height > 1.0 + 1e-6:
            raise ValueError("BoundingBox must not extend past the image bounds")

    @property
    def area(self) -> float:
        return self.width * self.height

    def to_dict(self) -> dict[str, float]:
        return {"x": self.x, "y": self.y, "width": self.width, "height": self.height}


@dataclass(frozen=True, slots=True)
class Detection:
    """A single food region produced by a :class:`FoodRecognizer`.

    ``pixel_area_ratio`` is the share of the *image* occupied by the region's
    segmentation mask (not its bounding box); the portion estimator needs the
    mask area, because a bounding box grossly overestimates round foods.
    """

    label: str
    category: FoodCategory
    confidence: float
    bbox: BoundingBox
    pixel_area_ratio: float
    engine: str
    attributes: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not self.label or not self.label.strip():
            raise ValueError("Detection.label must not be blank")
        if not 0.0 <= self.confidence <= 1.0:
            raise ValueError(f"Detection.confidence must be in [0, 1], got {self.confidence}")
        if not 0.0 < self.pixel_area_ratio <= 1.0:
            raise ValueError(
                f"Detection.pixel_area_ratio must be in (0, 1], got {self.pixel_area_ratio}"
            )

    @property
    def confidence_band(self) -> ConfidenceBand:
        return ConfidenceBand.from_score(self.confidence)


@dataclass(frozen=True, slots=True)
class PortionEstimate:
    """Volume estimate for one detection, with its own confidence.

    The portion confidence is *independent* of the recognition confidence: we
    can be sure a food is rice and still be unsure how much of it there is.
    """

    volume_ml: float
    confidence: float
    method: str
    assumptions: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not math.isfinite(self.volume_ml) or self.volume_ml <= 0:
            raise ValueError(f"volume_ml must be positive and finite, got {self.volume_ml}")
        if not 0.0 <= self.confidence <= 1.0:
            raise ValueError(f"portion confidence must be in [0, 1], got {self.confidence}")

    @property
    def confidence_band(self) -> ConfidenceBand:
        return ConfidenceBand.from_score(self.confidence)


@dataclass(frozen=True, slots=True)
class MassEstimate:
    """Mass derived from a volume estimate and a density record."""

    mass_g: float
    density_g_per_ml: float
    density_source: str
    density_confidence: float
    is_fallback_density: bool

    def __post_init__(self) -> None:
        if not math.isfinite(self.mass_g) or self.mass_g <= 0:
            raise ValueError(f"mass_g must be positive and finite, got {self.mass_g}")


@dataclass(frozen=True, slots=True)
class NutritionEstimate:
    """Coarse macro estimate for a mass of food. Explicitly an approximation."""

    energy_kcal: float
    protein_g: float
    carbohydrate_g: float
    fat_g: float
    source: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "energy_kcal": round(self.energy_kcal, 1),
            "protein_g": round(self.protein_g, 2),
            "carbohydrate_g": round(self.carbohydrate_g, 2),
            "fat_g": round(self.fat_g, 2),
            "source": self.source,
        }


@dataclass(frozen=True, slots=True)
class AnalyzedFoodItem:
    """One fully-resolved food item: what it is, how much, and how sure we are."""

    label: str
    category: FoodCategory
    recognition_confidence: float
    portion: PortionEstimate
    mass: MassEstimate
    nutrition: NutritionEstimate | None
    bbox: BoundingBox
    engine: str

    @property
    def overall_confidence(self) -> float:
        """Joint confidence over recognition, portion and density.

        Multiplicative because the three stages are independently fallible and
        a mass estimate is only as good as its weakest input.
        """
        return (
            self.recognition_confidence
            * self.portion.confidence
            * self.mass.density_confidence
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.label,
            "category": self.category.value,
            "confidence": round(self.recognition_confidence, 4),
            "confidence_band": ConfidenceBand.from_score(self.recognition_confidence).value,
            "estimated_volume_ml": round(self.portion.volume_ml, 1),
            "estimated_mass_g": round(self.mass.mass_g, 1),
            "portion_confidence": round(self.portion.confidence, 4),
            "portion_method": self.portion.method,
            "overall_confidence": round(self.overall_confidence, 4),
            "density_g_per_ml": self.mass.density_g_per_ml,
            "density_source": self.mass.density_source,
            "is_fallback_density": self.mass.is_fallback_density,
            "bbox": self.bbox.to_dict(),
            "engine": self.engine,
            "nutrition": self.nutrition.to_dict() if self.nutrition else None,
        }


@dataclass(frozen=True, slots=True)
class AnalysisResult:
    """The complete output of the pipeline for one image."""

    items: list[AnalyzedFoodItem]
    engine: str
    model_version: str
    processing_ms: int
    warnings: list[str] = field(default_factory=list)

    @property
    def total_mass_g(self) -> float:
        return sum(item.mass.mass_g for item in self.items)

    def to_dict(self) -> dict[str, Any]:
        return {
            "items": [item.to_dict() for item in self.items],
            "engine": self.engine,
            "model_version": self.model_version,
            "processing_ms": self.processing_ms,
            "total_estimated_mass_g": round(self.total_mass_g, 1),
            "warnings": list(self.warnings),
        }
