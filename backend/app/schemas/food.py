"""Food catalog contracts."""

from __future__ import annotations

import uuid

from pydantic import BaseModel, ConfigDict


class FoodDensityResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    density_g_per_ml: float
    source: str
    source_version: str
    confidence: float


class FoodResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    food_key: str
    display_name: str
    display_name_te: str | None
    category: str
    energy_kcal_per_100g: float | None
    protein_g_per_100g: float | None
    carbohydrate_g_per_100g: float | None
    fat_g_per_100g: float | None
    densities: list[FoodDensityResponse]
