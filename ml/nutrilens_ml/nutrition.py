"""Coarse macronutrient estimation from an estimated mass.

Nutrition here is a linear scaling of catalog values per 100 g. It inherits
every uncertainty of the mass estimate that feeds it, plus the variance of the
recipe itself, and is labelled as an estimate everywhere it surfaces.
"""

from __future__ import annotations

from .catalog import FoodCatalog, load_catalog
from .domain import NutritionEstimate

SOURCE = "nutrilens-food-catalog"


class NutritionEstimator:
    def __init__(self, catalog: FoodCatalog | None = None) -> None:
        self._catalog = catalog or load_catalog()

    def estimate(self, food_name: str, mass_g: float) -> NutritionEstimate | None:
        """Return macros for ``mass_g`` of a food, or ``None`` if unknown.

        Returning ``None`` rather than zeros is deliberate: a missing value and
        a genuine zero must not look the same to the caller.
        """
        if mass_g < 0:
            raise ValueError("mass_g must not be negative")
        record = self._catalog.resolve(food_name)
        if record is None:
            return None
        factor = mass_g / 100.0
        per100 = record.nutrition_per_100g
        return NutritionEstimate(
            energy_kcal=per100.energy_kcal * factor,
            protein_g=per100.protein_g * factor,
            carbohydrate_g=per100.carbohydrate_g * factor,
            fat_g=per100.fat_g * factor,
            source=f"{SOURCE}@{self._catalog.dataset_version}",
        )
