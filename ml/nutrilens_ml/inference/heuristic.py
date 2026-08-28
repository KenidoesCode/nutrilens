"""Classical colour/texture recognizer.

This engine does real work on real pixels: it segments the plate, computes
colour and texture statistics per region, and scores each region against the
colour signatures in the food catalog. It is *not* a neural network and its
accuracy has not been measured against any benchmark -- it exists so the
product has a dependable, dependency-free path that runs everywhere, and so
the pipeline can be exercised end to end without model weights.

The scores it emits are deliberately capped below the high-confidence band:
a rule-based colour match should never be presented to a user with the same
authority as a validated model.
"""

from __future__ import annotations

import math

from ..catalog import ColorSignature, FoodCatalog, FoodRecord, load_catalog
from ..domain import Detection
from ..preprocessing.image import PreparedImage
from ..preprocessing.segmentation import Region, segment_plate
from .base import FoodRecognizer

ENGINE_NAME = "heuristic-color-texture"
ENGINE_VERSION = "1.0.0"

# A rule-based match is never allowed into the "high" confidence band.
MAX_HEURISTIC_CONFIDENCE = 0.72
MIN_REPORTABLE_CONFIDENCE = 0.20

# Foods whose texture is expected to be granular rather than smooth. Used only
# as a tie-breaker between otherwise similarly-scoring candidates.
GRANULAR_FOOD_KEYS = frozenset({"rice", "leafy_greens", "fruit", "chicken"})
SMOOTH_FOOD_KEYS = frozenset({"dal", "yogurt", "water", "tea", "tomato_dish"})
TEXTURE_PIVOT = 0.05


class HeuristicFoodRecognizer(FoodRecognizer):
    def __init__(self, catalog: FoodCatalog | None = None) -> None:
        self._catalog = catalog or load_catalog()

    @property
    def name(self) -> str:
        return ENGINE_NAME

    @property
    def model_version(self) -> str:
        return f"{ENGINE_VERSION}+catalog-{self._catalog.dataset_version}"

    def recognize(self, image: PreparedImage) -> list[Detection]:
        regions = segment_plate(image.rgb)
        detections: list[Detection] = []
        for region in regions:
            scored = self._score_region(region)
            if scored is None:
                continue
            record, confidence = scored
            detections.append(
                Detection(
                    label=record.display_name,
                    category=record.category,
                    confidence=confidence,
                    bbox=region.bbox,
                    pixel_area_ratio=region.area_ratio,
                    engine=self.name,
                    attributes={
                        "food_key": record.key,
                        "mean_hue_deg": round(region.mean_hue_deg, 2),
                        "mean_saturation": round(region.mean_saturation, 3),
                        "mean_value": round(region.mean_value, 3),
                        "texture_score": round(region.texture_score, 4),
                        "area_ratio_of_plate": round(region.area_ratio_of_plate, 4),
                    },
                )
            )
        detections.sort(key=lambda d: d.confidence, reverse=True)
        return detections

    def _score_region(self, region: Region) -> tuple[FoodRecord, float] | None:
        best: tuple[FoodRecord, float] | None = None
        for record in self._catalog.all():
            score = self._similarity(region, record)
            if best is None or score > best[1]:
                best = (record, score)
        if best is None:
            return None
        record, similarity = best
        confidence = similarity * MAX_HEURISTIC_CONFIDENCE
        if confidence < MIN_REPORTABLE_CONFIDENCE:
            return None
        return record, round(confidence, 4)

    def _similarity(self, region: Region, record: FoodRecord) -> float:
        """Similarity in [0, 1] between a region's appearance and a food signature."""
        signature: ColorSignature = record.color

        # Hue only carries information when the region actually has colour;
        # for near-grey regions (rice, yogurt) saturation and value decide.
        hue_weight = min(1.0, region.mean_saturation / 0.25)
        hue_penalty = signature.hue_distance(region.mean_hue_deg) / 180.0
        hue_term = 1.0 - hue_weight * hue_penalty

        saturation_term = math.exp(
            -((signature.saturation_distance(region.mean_saturation) / 0.22) ** 2)
        )
        value_term = math.exp(-((signature.value_distance(region.mean_value) / 0.25) ** 2))

        texture_term = self._texture_agreement(region, record)

        # Weights reflect how discriminative each cue is for plated food:
        # colourfulness separates dishes best, hue next, brightness after that.
        score = (
            0.34 * saturation_term
            + 0.30 * max(0.0, hue_term)
            + 0.22 * value_term
            + 0.14 * texture_term
        )
        return max(0.0, min(1.0, score))

    @staticmethod
    def _texture_agreement(region: Region, record: FoodRecord) -> float:
        granular = region.texture_score >= TEXTURE_PIVOT
        if record.key in GRANULAR_FOOD_KEYS:
            return 1.0 if granular else 0.45
        if record.key in SMOOTH_FOOD_KEYS:
            return 0.45 if granular else 1.0
        return 0.7
