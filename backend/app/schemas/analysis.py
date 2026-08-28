"""Analysis contracts.

Every numeric estimate travels with its confidence. A client that renders a
mass without also rendering the uncertainty is misusing this contract.
"""

from __future__ import annotations

import uuid

from pydantic import BaseModel, ConfigDict, Field


class ReferenceObjectRequest(BaseModel):
    """An object of known size in frame, used to calibrate the volume estimate."""

    name: str = Field(max_length=80)
    real_area_cm2: float = Field(gt=0, le=10_000)
    image_area_ratio: float = Field(gt=0, le=1)
    confidence: float = Field(ge=0, le=1, default=0.8)


class BoundingBoxResponse(BaseModel):
    x: float
    y: float
    width: float
    height: float


class NutritionResponse(BaseModel):
    energy_kcal: float
    protein_g: float
    carbohydrate_g: float
    fat_g: float
    source: str


class AnalysisItemResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    name: str
    category: str
    confidence: float = Field(ge=0, le=1)
    confidence_band: str = Field(description="low | medium | high")
    estimated_volume_ml: float
    estimated_mass_g: float
    portion_confidence: float = Field(ge=0, le=1)
    portion_method: str
    overall_confidence: float = Field(
        ge=0, le=1, description="Joint confidence over recognition, portion and density."
    )
    density_g_per_ml: float
    density_source: str
    is_fallback_density: bool = Field(
        description="True when the density came from a category default, not the food itself."
    )
    bbox: BoundingBoxResponse
    engine: str
    nutrition: NutritionResponse | None


class AnalysisResponse(BaseModel):
    model_config = ConfigDict(
        json_schema_extra={
            "example": {
                "prediction_id": "6f0f3f6a-6d29-4a1c-90a3-1a5f2a4bd6c1",
                "items": [
                    {
                        "name": "Rice",
                        "category": "solid",
                        "confidence": 0.62,
                        "confidence_band": "medium",
                        "estimated_volume_ml": 180.0,
                        "estimated_mass_g": 153.0,
                    }
                ],
                "engine": "heuristic-color-texture",
                "estimates_are_approximate": True,
            }
        }
    )

    prediction_id: uuid.UUID
    items: list[AnalysisItemResponse]
    engine: str
    model_version: str
    processing_ms: int
    total_estimated_mass_g: float
    warnings: list[str]
    estimates_are_approximate: bool = Field(
        default=True,
        description=(
            "Always true. Volumes and masses are geometric estimates from a single "
            "uncalibrated photograph, not measurements."
        ),
    )
