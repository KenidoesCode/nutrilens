"""Meal contracts."""

from __future__ import annotations

import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field, field_validator

MAX_ITEMS_PER_MEAL = 20
MAX_VOLUME_ML = 3000.0


class MealItemCreateRequest(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)

    display_name: str = Field(min_length=1, max_length=160)
    category: str = Field(pattern="^(solid|semisolid|liquid)$")
    estimated_volume_ml: float = Field(gt=0, le=MAX_VOLUME_ML)
    recognition_confidence: float = Field(ge=0.0, le=1.0)
    portion_confidence: float = Field(ge=0.0, le=1.0)
    portion_method: str = Field(default="reference-object", max_length=48)
    food_key: str | None = Field(default=None, max_length=80)
    was_user_corrected: bool = False
    original_display_name: str | None = Field(default=None, max_length=160)
    portion_assumptions: dict | None = None


class MealCreateRequest(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)

    consumed_at: datetime = Field(
        description="Instant the meal was eaten. Must carry a UTC offset."
    )
    timezone: str = Field(max_length=64, description="IANA zone the user was in.")
    meal_type: str = Field(pattern="^(breakfast|lunch|dinner|snack|beverage|other)$")
    items: list[MealItemCreateRequest] = Field(min_length=1, max_length=MAX_ITEMS_PER_MEAL)
    notes: str | None = Field(default=None, max_length=2000)
    idempotency_key: str | None = Field(
        default=None,
        max_length=80,
        description="Client-generated key that makes a retried create a no-op.",
    )
    client_recorded_at: datetime | None = None
    prediction_id: uuid.UUID | None = Field(
        default=None, description="Links this meal to the analysis it came from."
    )

    @field_validator("consumed_at", "client_recorded_at")
    @classmethod
    def _require_timezone(cls, value: datetime | None) -> datetime | None:
        # A naive timestamp cannot be placed on a user's day, which is the
        # entire point of the chrononutrition features.
        if value is not None and value.tzinfo is None:
            raise ValueError("timestamp must include a UTC offset")
        return value


class MealItemResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    display_name: str
    food_key: str | None
    category: str
    estimated_volume_ml: float
    estimated_mass_g: float
    density_g_per_ml: float
    density_source: str
    recognition_confidence: float
    portion_confidence: float
    energy_kcal: float | None
    protein_g: float | None
    carbohydrate_g: float | None
    fat_g: float | None
    was_user_corrected: bool
    original_mass_g: float | None
    original_display_name: str | None


class MealImageResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    content_type: str
    byte_size: int
    width_px: int | None
    height_px: int | None


class MealResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    consumed_at: datetime
    timezone: str
    meal_type: str
    notes: str | None
    total_mass_g: float | None
    total_energy_kcal: float | None
    created_at: datetime
    updated_at: datetime
    items: list[MealItemResponse]
    images: list[MealImageResponse]

    @classmethod
    def from_model(cls, meal) -> MealResponse:
        """Build a response, hiding soft-deleted children.

        A deleted item must not reappear in a meal's payload, and filtering
        here means no endpoint can forget to do it.
        """
        return cls(
            id=meal.id,
            consumed_at=meal.consumed_at,
            timezone=meal.timezone,
            meal_type=str(meal.meal_type),
            notes=meal.notes,
            total_mass_g=meal.total_mass_g,
            total_energy_kcal=meal.total_energy_kcal,
            created_at=meal.created_at,
            updated_at=meal.updated_at,
            items=[
                MealItemResponse.model_validate(item)
                for item in meal.items
                if not item.is_deleted
            ],
            images=[
                MealImageResponse.model_validate(image)
                for image in meal.images
                if not image.is_deleted
            ],
        )


class PortionCorrectionRequest(BaseModel):
    corrected_volume_ml: float = Field(gt=0, le=MAX_VOLUME_ML)


class RenameItemRequest(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)

    display_name: str = Field(min_length=1, max_length=160)
