"""Food catalog and density persistence."""

from __future__ import annotations

from sqlalchemy import func, select

from ..models.food import FoodCatalogEntry, FoodDensity
from .base import Repository


class FoodRepository(Repository):
    def get_by_key(self, food_key: str) -> FoodCatalogEntry | None:
        statement = select(FoodCatalogEntry).where(
            FoodCatalogEntry.food_key == food_key.strip().lower()
        )
        return self._session.execute(statement).scalar_one_or_none()

    def search(self, term: str | None, limit: int = 50) -> list[FoodCatalogEntry]:
        statement = select(FoodCatalogEntry).order_by(FoodCatalogEntry.display_name.asc())
        if term:
            pattern = f"%{term.strip().lower()}%"
            # Bound parameters throughout: the term is never concatenated into SQL.
            statement = statement.where(
                func.lower(FoodCatalogEntry.display_name).like(pattern)
                | func.lower(FoodCatalogEntry.food_key).like(pattern)
            )
        return list(self._session.execute(statement.limit(max(1, min(limit, 200)))).scalars())

    def upsert_entry(self, entry: FoodCatalogEntry) -> FoodCatalogEntry:
        existing = self.get_by_key(entry.food_key)
        if existing is None:
            self._session.add(entry)
            self.flush()
            return entry
        existing.display_name = entry.display_name
        existing.display_name_te = entry.display_name_te
        existing.category = entry.category
        existing.energy_kcal_per_100g = entry.energy_kcal_per_100g
        existing.protein_g_per_100g = entry.protein_g_per_100g
        existing.carbohydrate_g_per_100g = entry.carbohydrate_g_per_100g
        existing.fat_g_per_100g = entry.fat_g_per_100g
        self._session.add(existing)
        self.flush()
        return existing

    def upsert_density(self, density: FoodDensity) -> FoodDensity:
        statement = select(FoodDensity).where(
            FoodDensity.food_id == density.food_id,
            FoodDensity.source == density.source,
            FoodDensity.source_version == density.source_version,
        )
        existing = self._session.execute(statement).scalar_one_or_none()
        if existing is None:
            self._session.add(density)
            self.flush()
            return density
        existing.density_g_per_ml = density.density_g_per_ml
        existing.confidence = density.confidence
        existing.category = density.category
        self._session.add(existing)
        self.flush()
        return existing

    def count(self) -> int:
        return int(
            self._session.execute(select(func.count()).select_from(FoodCatalogEntry)).scalar_one()
        )
