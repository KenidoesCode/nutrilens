"""Density lookup with an explicit, auditable fallback chain."""

from __future__ import annotations

from dataclasses import dataclass

from ..catalog import FoodCatalog, load_catalog
from ..domain import FoodCategory, MassEstimate
from .units import ml_to_grams, validate_density


class DensityUnavailableError(LookupError):
    """Raised when no density can be resolved and no fallback is permitted."""


@dataclass(frozen=True, slots=True)
class DensityRecord:
    food_key: str
    category: FoodCategory
    density_g_per_ml: float
    source: str
    source_version: str
    confidence: float
    is_fallback: bool


class DensityEngine:
    """Resolves a density for a food and converts volume to mass.

    Resolution order:

    1. exact catalog entry (key, display name or alias),
    2. category default from the same dataset,
    3. failure -- unless the caller opts into the category default.

    Every result carries its own confidence and a ``is_fallback`` flag so the
    UI can tell the user when a mass figure rests on a generic assumption.
    """

    CATEGORY_FALLBACK_CONFIDENCE = 0.35

    def __init__(self, catalog: FoodCatalog | None = None) -> None:
        self._catalog = catalog or load_catalog()

    @property
    def dataset_version(self) -> str:
        return self._catalog.dataset_version

    def lookup(
        self,
        food_name: str,
        category: FoodCategory | None = None,
        *,
        allow_category_fallback: bool = True,
    ) -> DensityRecord:
        record = self._catalog.resolve(food_name)
        if record is not None:
            return DensityRecord(
                food_key=record.key,
                category=record.category,
                density_g_per_ml=record.density_g_per_ml,
                source="nutrilens-food-catalog",
                source_version=self._catalog.dataset_version,
                confidence=record.density_confidence,
                is_fallback=False,
            )

        if category is None or not allow_category_fallback:
            raise DensityUnavailableError(
                f"No density entry for {food_name!r} and no category fallback permitted"
            )

        return DensityRecord(
            food_key=food_name.strip().lower(),
            category=category,
            density_g_per_ml=self._catalog.default_density(category),
            source="category-default",
            source_version=self._catalog.dataset_version,
            confidence=self.CATEGORY_FALLBACK_CONFIDENCE,
            is_fallback=True,
        )

    def to_mass(
        self,
        food_name: str,
        volume_ml: float,
        category: FoodCategory | None = None,
        *,
        allow_category_fallback: bool = True,
    ) -> MassEstimate:
        record = self.lookup(
            food_name, category, allow_category_fallback=allow_category_fallback
        )
        density = validate_density(record.density_g_per_ml)
        return MassEstimate(
            mass_g=ml_to_grams(volume_ml, density),
            density_g_per_ml=density,
            density_source=f"{record.source}@{record.source_version}",
            density_confidence=record.confidence,
            is_fallback_density=record.is_fallback,
        )
