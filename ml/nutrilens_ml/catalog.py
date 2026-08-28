"""Food reference catalog: densities, nutrition and colour signatures.

The catalog is the single source of truth for food metadata inside the ML
package. It is loaded once from a JSON dataset so that the table can be
replaced (or backed by a database) without touching the estimation code.
"""

from __future__ import annotations

import json
import threading
from dataclasses import dataclass
from functools import lru_cache
from importlib import resources
from typing import Any

from .domain import FoodCategory

_DATASET_PACKAGE = "nutrilens_ml.datasets"
_DATASET_FILE = "food_catalog.json"


@dataclass(frozen=True, slots=True)
class NutritionPer100g:
    energy_kcal: float
    protein_g: float
    carbohydrate_g: float
    fat_g: float


@dataclass(frozen=True, slots=True)
class ColorSignature:
    """Rough HSV envelope of a food, used by the heuristic recognizer.

    ``hue_range_deg`` may wrap around 360 degrees (e.g. ``[348, 12]`` for red).
    """

    hue_range_deg: tuple[float, float]
    saturation_range: tuple[float, float]
    value_range: tuple[float, float]

    def contains_hue(self, hue_deg: float) -> bool:
        low, high = self.hue_range_deg
        if low <= high:
            return low <= hue_deg <= high
        return hue_deg >= low or hue_deg <= high

    def hue_distance(self, hue_deg: float) -> float:
        """Circular distance in degrees from ``hue_deg`` to the envelope."""
        if self.contains_hue(hue_deg):
            return 0.0
        low, high = self.hue_range_deg
        return min(_circular_delta(hue_deg, low), _circular_delta(hue_deg, high))

    @staticmethod
    def _range_distance(value: float, bounds: tuple[float, float]) -> float:
        low, high = bounds
        if value < low:
            return low - value
        if value > high:
            return value - high
        return 0.0

    def saturation_distance(self, saturation: float) -> float:
        return self._range_distance(saturation, self.saturation_range)

    def value_distance(self, value: float) -> float:
        return self._range_distance(value, self.value_range)


def _circular_delta(a_deg: float, b_deg: float) -> float:
    diff = abs(a_deg - b_deg) % 360.0
    return min(diff, 360.0 - diff)


@dataclass(frozen=True, slots=True)
class FoodRecord:
    key: str
    display_name: str
    category: FoodCategory
    density_g_per_ml: float
    density_confidence: float
    nutrition_per_100g: NutritionPer100g
    aliases: tuple[str, ...]
    color: ColorSignature

    def matches(self, term: str) -> bool:
        normalized = term.strip().lower()
        return normalized == self.key or normalized in self.aliases or (
            normalized == self.display_name.lower()
        )


class FoodCatalog:
    """In-memory, immutable view over the food reference dataset."""

    def __init__(self, payload: dict[str, Any]) -> None:
        self._dataset_version: str = payload["dataset_version"]
        self._notice: str = payload["notice"]
        self._default_density: dict[FoodCategory, float] = {
            FoodCategory.parse(k): float(v) for k, v in payload["default_density"].items()
        }
        records: dict[str, FoodRecord] = {}
        alias_index: dict[str, str] = {}
        for raw in payload["foods"]:
            record = self._build_record(raw)
            if record.key in records:
                raise ValueError(f"Duplicate food key in catalog: {record.key}")
            records[record.key] = record
            alias_index[record.key] = record.key
            alias_index[record.display_name.lower()] = record.key
            for alias in record.aliases:
                alias_index.setdefault(alias, record.key)
        self._records = records
        self._alias_index = alias_index

    @staticmethod
    def _build_record(raw: dict[str, Any]) -> FoodRecord:
        nutrition = raw["nutrition_per_100g"]
        density = float(raw["density_g_per_ml"])
        if density <= 0:
            raise ValueError(f"Non-positive density for {raw['key']!r}")
        return FoodRecord(
            key=raw["key"],
            display_name=raw["display_name"],
            category=FoodCategory.parse(raw["category"]),
            density_g_per_ml=density,
            density_confidence=float(raw["density_confidence"]),
            nutrition_per_100g=NutritionPer100g(
                energy_kcal=float(nutrition["energy_kcal"]),
                protein_g=float(nutrition["protein_g"]),
                carbohydrate_g=float(nutrition["carbohydrate_g"]),
                fat_g=float(nutrition["fat_g"]),
            ),
            aliases=tuple(a.strip().lower() for a in raw.get("aliases", ())),
            color=ColorSignature(
                hue_range_deg=(float(raw["hue_range_deg"][0]), float(raw["hue_range_deg"][1])),
                saturation_range=(
                    float(raw["saturation_range"][0]),
                    float(raw["saturation_range"][1]),
                ),
                value_range=(float(raw["value_range"][0]), float(raw["value_range"][1])),
            ),
        )

    @property
    def dataset_version(self) -> str:
        return self._dataset_version

    @property
    def notice(self) -> str:
        return self._notice

    def all(self) -> tuple[FoodRecord, ...]:
        return tuple(self._records.values())

    def get(self, key: str) -> FoodRecord | None:
        return self._records.get(key.strip().lower())

    def resolve(self, term: str) -> FoodRecord | None:
        """Resolve a key, display name or alias to a record."""
        normalized = term.strip().lower()
        if not normalized:
            return None
        key = self._alias_index.get(normalized)
        return self._records.get(key) if key else None

    def default_density(self, category: FoodCategory) -> float:
        return self._default_density[category]


_lock = threading.Lock()


@lru_cache(maxsize=1)
def load_catalog() -> FoodCatalog:
    """Load (and memoise) the bundled food catalog."""
    with _lock:
        source = resources.files(_DATASET_PACKAGE).joinpath(_DATASET_FILE)
        payload = json.loads(source.read_text(encoding="utf-8"))
    return FoodCatalog(payload)
