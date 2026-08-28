"""The meal analysis pipeline.

Composes the stages -- preprocess, recognise, estimate portion, convert to
mass, estimate nutrition -- into a single call. Every stage is injected, so
each can be replaced or tested in isolation.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass

from .catalog import FoodCatalog, load_catalog
from .density.engine import DensityEngine, DensityUnavailableError
from .domain import AnalysisResult, AnalyzedFoodItem, Detection
from .inference.base import FoodRecognizer, InferenceError
from .inference.factory import EngineConfig, build_recognizer
from .nutrition import NutritionEstimator
from .portion.estimator import PortionEstimator, ReferenceObject
from .preprocessing.image import PreparedImage, prepare_image
from .preprocessing.segmentation import Region, segment_plate

logger = logging.getLogger(__name__)

MAX_ITEMS_PER_MEAL = 8


@dataclass(frozen=True, slots=True)
class AnalysisOptions:
    """Per-request knobs. Defaults are the ordinary path."""

    reference: ReferenceObject | None = None
    include_nutrition: bool = True
    max_items: int = MAX_ITEMS_PER_MEAL


class MealAnalysisPipeline:
    def __init__(
        self,
        recognizer: FoodRecognizer,
        *,
        portion_estimator: PortionEstimator | None = None,
        density_engine: DensityEngine | None = None,
        nutrition_estimator: NutritionEstimator | None = None,
        catalog: FoodCatalog | None = None,
    ) -> None:
        resolved_catalog = catalog or load_catalog()
        self._recognizer = recognizer
        self._portion = portion_estimator or PortionEstimator()
        self._density = density_engine or DensityEngine(resolved_catalog)
        self._nutrition = nutrition_estimator or NutritionEstimator(resolved_catalog)

    @classmethod
    def from_config(cls, config: EngineConfig) -> MealAnalysisPipeline:
        return cls(build_recognizer(config))

    @property
    def engine_name(self) -> str:
        return self._recognizer.name

    @property
    def model_version(self) -> str:
        return self._recognizer.model_version

    def warmup(self) -> None:
        self._recognizer.warmup()

    def analyze_bytes(
        self,
        data: bytes,
        *,
        declared_mime: str | None = None,
        options: AnalysisOptions | None = None,
    ) -> AnalysisResult:
        image = prepare_image(data, declared_mime=declared_mime)
        return self.analyze(image, options=options)

    def analyze(
        self, image: PreparedImage, *, options: AnalysisOptions | None = None
    ) -> AnalysisResult:
        opts = options or AnalysisOptions()
        started = time.perf_counter()
        warnings: list[str] = []

        detections = self._recognizer.recognize(image)
        if not detections:
            warnings.append("no_food_detected")
            return AnalysisResult(
                items=[],
                engine=self._recognizer.name,
                model_version=self._recognizer.model_version,
                processing_ms=_elapsed_ms(started),
                warnings=warnings,
            )

        # Portion estimation needs the segmentation geometry, and the region
        # list is regenerated deterministically from the same prepared image.
        regions = segment_plate(image.rgb)
        items: list[AnalyzedFoodItem] = []

        for detection in detections[: opts.max_items]:
            region = _match_region(detection, regions)
            if region is None:
                warnings.append(f"region_unavailable:{detection.label}")
                continue
            item = self._build_item(detection, region, opts, warnings)
            if item is not None:
                items.append(item)

        if not items and not warnings:
            warnings.append("no_items_resolved")

        return AnalysisResult(
            items=items,
            engine=self._recognizer.name,
            model_version=self._recognizer.model_version,
            processing_ms=_elapsed_ms(started),
            warnings=warnings,
        )

    def _build_item(
        self,
        detection: Detection,
        region: Region,
        options: AnalysisOptions,
        warnings: list[str],
    ) -> AnalyzedFoodItem | None:
        portion = self._portion.estimate(
            region, detection.category, reference=options.reference
        )
        try:
            mass = self._density.to_mass(
                detection.label, portion.volume_ml, detection.category
            )
        except DensityUnavailableError:
            warnings.append(f"density_unavailable:{detection.label}")
            return None

        nutrition = None
        if options.include_nutrition:
            nutrition = self._nutrition.estimate(detection.label, mass.mass_g)
            if nutrition is None:
                warnings.append(f"nutrition_unavailable:{detection.label}")

        return AnalyzedFoodItem(
            label=detection.label,
            category=detection.category,
            recognition_confidence=detection.confidence,
            portion=portion,
            mass=mass,
            nutrition=nutrition,
            bbox=detection.bbox,
            engine=detection.engine,
        )


def _match_region(detection: Detection, regions: list[Region]) -> Region | None:
    """Find the region a detection came from, by bounding-box agreement."""
    best: tuple[Region, float] | None = None
    for region in regions:
        score = _iou(detection.bbox, region.bbox)
        if best is None or score > best[1]:
            best = (region, score)
    if best is None or best[1] <= 0.0:
        return None
    return best[0]


def _iou(a, b) -> float:
    x0 = max(a.x, b.x)
    y0 = max(a.y, b.y)
    x1 = min(a.x + a.width, b.x + b.width)
    y1 = min(a.y + a.height, b.y + b.height)
    if x1 <= x0 or y1 <= y0:
        return 0.0
    intersection = (x1 - x0) * (y1 - y0)
    union = a.area + b.area - intersection
    return intersection / union if union > 0 else 0.0


def _elapsed_ms(started: float) -> int:
    return max(0, int((time.perf_counter() - started) * 1000))


__all__ = [
    "AnalysisOptions",
    "AnalysisResult",
    "InferenceError",
    "MealAnalysisPipeline",
]
