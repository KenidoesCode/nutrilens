"""Food catalog queries and dataset seeding."""

from __future__ import annotations

from nutrilens_ml import FoodCatalog, load_catalog

from ..models.food import FoodCatalogEntry, FoodDensity
from ..repositories.food import FoodRepository

# Telugu display names for the seeded catalog. Kept beside the seeder because
# they belong to the reference data, not to the UI layer.
TELUGU_NAMES = {
    "rice": "అన్నం",
    "dal": "పప్పు",
    "vegetable_curry": "కూర",
    "leafy_greens": "ఆకు కూర",
    "chapati": "చపాతీ",
    "yogurt": "పెరుగు",
    "water": "నీరు",
    "tea": "టీ",
    "tomato_dish": "టమాటా వంటకం",
    "egg": "కోడిగుడ్డు",
    "chicken": "చికెన్",
    "fruit": "పండు",
}


class FoodService:
    def __init__(self, foods: FoodRepository, catalog: FoodCatalog | None = None) -> None:
        self._foods = foods
        self._catalog = catalog or load_catalog()

    def search(self, term: str | None, limit: int = 50) -> list[FoodCatalogEntry]:
        return self._foods.search(term, limit)

    def seed_from_dataset(self) -> int:
        """Load the bundled reference dataset into the database.

        Idempotent: safe to run on every deploy, so a fresh environment and an
        upgraded one converge on the same catalog.
        """
        seeded = 0
        for record in self._catalog.all():
            entry = self._foods.upsert_entry(
                FoodCatalogEntry(
                    food_key=record.key,
                    display_name=record.display_name,
                    display_name_te=TELUGU_NAMES.get(record.key),
                    category=record.category.value,
                    energy_kcal_per_100g=record.nutrition_per_100g.energy_kcal,
                    protein_g_per_100g=record.nutrition_per_100g.protein_g,
                    carbohydrate_g_per_100g=record.nutrition_per_100g.carbohydrate_g,
                    fat_g_per_100g=record.nutrition_per_100g.fat_g,
                )
            )
            self._foods.upsert_density(
                FoodDensity(
                    food_id=entry.id,
                    category=record.category.value,
                    density_g_per_ml=record.density_g_per_ml,
                    source="nutrilens-food-catalog",
                    source_version=self._catalog.dataset_version,
                    confidence=record.density_confidence,
                )
            )
            seeded += 1
        return seeded
