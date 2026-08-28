"""Meal creation, editing and deletion, with idempotent sync semantics."""

from __future__ import annotations

import json
import uuid
from dataclasses import dataclass
from datetime import datetime

from nutrilens_ml import DensityEngine, FoodCategory, NutritionEstimator
from nutrilens_ml.portion.estimator import MAX_VOLUME_ML

from ..core.errors import ConflictError, NotFoundError, ValidationError
from ..core.logging import get_logger
from ..models.audit import AuditAction
from ..models.meal import Meal, MealImage, MealItem, MealType, PortionEstimateRecord
from ..repositories.audit import AuditRepository
from ..repositories.meal import MealRepository
from ..repositories.sync import SyncRepository
from .storage import StoredObject

logger = get_logger(__name__)

MAX_ITEMS_PER_MEAL = 20
MAX_NOTE_LENGTH = 2000

# Estimates carry tens of percent of uncertainty, so storing binary-float tails
# (152.99999999999997 g) records noise and leaks it into the API and the UI.
MASS_DECIMALS = 2
NUTRIENT_DECIMALS = 2


def _round_mass(value: float) -> float:
    return round(value, MASS_DECIMALS)


def _round_nutrient(value: float | None) -> float | None:
    return None if value is None else round(value, NUTRIENT_DECIMALS)


@dataclass(frozen=True, slots=True)
class MealItemInput:
    display_name: str
    category: str
    estimated_volume_ml: float
    recognition_confidence: float
    portion_confidence: float
    portion_method: str
    food_key: str | None = None
    was_user_corrected: bool = False
    original_mass_g: float | None = None
    original_display_name: str | None = None
    portion_assumptions: dict | None = None


@dataclass(frozen=True, slots=True)
class MealInput:
    consumed_at: datetime
    timezone: str
    meal_type: str
    items: list[MealItemInput]
    notes: str | None = None
    idempotency_key: str | None = None
    client_recorded_at: datetime | None = None


@dataclass(frozen=True, slots=True)
class MealCreationResult:
    meal: Meal
    was_replay: bool


class MealService:
    """Creates and mutates meals.

    Mass is always recomputed here from volume and density rather than trusted
    from the client: the device may be running an older catalog, and a meal
    record whose mass does not match its own density would be unauditable.
    """

    def __init__(
        self,
        meals: MealRepository,
        sync: SyncRepository,
        audit: AuditRepository,
        density: DensityEngine,
        nutrition: NutritionEstimator,
    ) -> None:
        self._meals = meals
        self._sync = sync
        self._audit = audit
        self._density = density
        self._nutrition = nutrition

    def create(
        self,
        *,
        user_id: uuid.UUID,
        payload: MealInput,
        image: StoredObject | None = None,
        image_dimensions: tuple[int, int] | None = None,
        request_id: str = "",
    ) -> MealCreationResult:
        self._validate(payload)

        if payload.idempotency_key:
            existing = self._meals.get_by_idempotency_key(user_id, payload.idempotency_key)
            if existing is not None:
                # The client retried an operation the server already applied.
                self._sync.record_success(
                    user_id=user_id,
                    idempotency_key=payload.idempotency_key,
                    operation="create",
                    entity_type="meal",
                    entity_id=existing.id,
                )
                logger.info("meal_create_replayed", meal_id=str(existing.id))
                return MealCreationResult(meal=existing, was_replay=True)

        meal = Meal(
            user_id=user_id,
            consumed_at=payload.consumed_at,
            timezone=payload.timezone,
            meal_type=MealType(payload.meal_type),
            notes=payload.notes,
            idempotency_key=payload.idempotency_key,
            client_recorded_at=payload.client_recorded_at,
        )
        self._meals.add(meal)

        for item_input in payload.items:
            self._add_item(meal, item_input)

        if image is not None:
            self._attach_image(meal, image, image_dimensions)

        self._meals.recalculate_totals(meal)

        if payload.idempotency_key:
            self._sync.record_success(
                user_id=user_id,
                idempotency_key=payload.idempotency_key,
                operation="create",
                entity_type="meal",
                entity_id=meal.id,
            )

        self._audit.record(
            action=AuditAction.MEAL_CREATED,
            user_id=user_id,
            request_id=request_id,
            metadata={"item_count": len(payload.items)},
        )
        logger.info("meal_created", meal_id=str(meal.id), item_count=len(payload.items))
        return MealCreationResult(meal=meal, was_replay=False)

    def list(
        self,
        user_id: uuid.UUID,
        *,
        limit: int,
        offset: int,
        start: datetime | None = None,
        end: datetime | None = None,
    ) -> tuple[list[Meal], int]:
        return self._meals.list_page(
            user_id, limit=limit, offset=offset, start=start, end=end
        )

    def changed_since(self, user_id: uuid.UUID, since: datetime, limit: int) -> list[Meal]:
        return self._meals.changed_since(user_id, since, limit)

    def get(self, user_id: uuid.UUID, meal_id: uuid.UUID) -> Meal:
        meal = self._meals.get(user_id, meal_id)
        if meal is None:
            raise NotFoundError("That meal could not be found.")
        return meal

    def delete(self, user_id: uuid.UUID, meal_id: uuid.UUID, request_id: str = "") -> None:
        meal = self.get(user_id, meal_id)
        self._meals.soft_delete(meal)
        self._audit.record(
            action=AuditAction.MEAL_DELETED, user_id=user_id, request_id=request_id
        )
        logger.info("meal_deleted", meal_id=str(meal_id))

    def correct_item_portion(
        self,
        *,
        user_id: uuid.UUID,
        item_id: uuid.UUID,
        corrected_volume_ml: float,
        request_id: str = "",
    ) -> MealItem:
        """Apply a user's portion correction, keeping the original for audit."""
        if corrected_volume_ml <= 0 or corrected_volume_ml > MAX_VOLUME_ML:
            raise ValidationError(
                f"The corrected volume must be between 0 and {MAX_VOLUME_ML} ml."
            )

        item = self._meals.get_item(user_id, item_id)
        if item is None:
            raise NotFoundError("That meal item could not be found.")

        if item.original_mass_g is None:
            item.original_mass_g = item.estimated_mass_g

        item.estimated_volume_ml = corrected_volume_ml
        item.estimated_mass_g = _round_mass(corrected_volume_ml * item.density_g_per_ml)
        item.was_user_corrected = True
        # A human correction supersedes the estimator, but the person is also
        # estimating, so this mirrors the ML layer's ceiling rather than 1.0.
        item.portion_confidence = 0.9
        self._apply_nutrition(item)

        self._meals.add_portion_estimate(
            PortionEstimateRecord(
                meal_item_id=item.id,
                volume_ml=corrected_volume_ml,
                confidence=0.9,
                method="user-corrected",
                assumptions_json=json.dumps({"source": "user"}),
            )
        )

        meal = self._meals.get(user_id, item.meal_id)
        if meal is not None:
            self._meals.recalculate_totals(meal)

        self._audit.record(
            action=AuditAction.MEAL_UPDATED, user_id=user_id, request_id=request_id
        )
        return item

    def rename_item(
        self, *, user_id: uuid.UUID, item_id: uuid.UUID, display_name: str
    ) -> MealItem:
        """Correct a misidentified food.

        Changing what the food *is* changes its density, so mass is recomputed
        rather than carried over from the wrong food.
        """
        name = display_name.strip()
        if not name:
            raise ValidationError("The food name must not be empty.")

        item = self._meals.get_item(user_id, item_id)
        if item is None:
            raise NotFoundError("That meal item could not be found.")

        if item.original_display_name is None:
            item.original_display_name = item.display_name
        if item.original_mass_g is None:
            item.original_mass_g = item.estimated_mass_g

        item.display_name = name
        mass = self._density.to_mass(
            name, item.estimated_volume_ml, FoodCategory.parse(item.category)
        )
        item.estimated_mass_g = _round_mass(mass.mass_g)
        item.density_g_per_ml = mass.density_g_per_ml
        item.density_source = mass.density_source
        item.food_key = name.strip().lower().replace(" ", "_")
        item.was_user_corrected = True
        self._apply_nutrition(item)

        meal = self._meals.get(user_id, item.meal_id)
        if meal is not None:
            self._meals.recalculate_totals(meal)
        return item

    def remove_item(self, *, user_id: uuid.UUID, item_id: uuid.UUID) -> None:
        item = self._meals.get_item(user_id, item_id)
        if item is None:
            raise NotFoundError("That meal item could not be found.")
        self._meals.soft_delete_item(item)
        meal = self._meals.get(user_id, item.meal_id)
        if meal is not None:
            self._meals.recalculate_totals(meal)

    # --- internals -------------------------------------------------------

    def _add_item(self, meal: Meal, payload: MealItemInput) -> MealItem:
        category = FoodCategory.parse(payload.category)
        mass = self._density.to_mass(
            payload.display_name, payload.estimated_volume_ml, category
        )

        item = MealItem(
            food_key=payload.food_key,
            display_name=payload.display_name.strip(),
            category=category.value,
            estimated_volume_ml=payload.estimated_volume_ml,
            estimated_mass_g=_round_mass(mass.mass_g),
            density_g_per_ml=mass.density_g_per_ml,
            density_source=mass.density_source,
            recognition_confidence=payload.recognition_confidence,
            portion_confidence=payload.portion_confidence,
            was_user_corrected=payload.was_user_corrected,
            original_mass_g=payload.original_mass_g,
            original_display_name=payload.original_display_name,
        )
        self._apply_nutrition(item)

        # Appending to the relationship is the single mechanism that both
        # inserts the row and keeps the in-memory collection correct. Adding it
        # to the session as well would double-count it in the meal totals.
        meal.items.append(item)
        self._meals.flush()

        self._meals.add_portion_estimate(
            PortionEstimateRecord(
                meal_item_id=item.id,
                volume_ml=payload.estimated_volume_ml,
                confidence=payload.portion_confidence,
                method=payload.portion_method,
                assumptions_json=(
                    json.dumps(payload.portion_assumptions, sort_keys=True)
                    if payload.portion_assumptions
                    else None
                ),
            )
        )
        return item

    def _apply_nutrition(self, item: MealItem) -> None:
        estimate = self._nutrition.estimate(item.display_name, item.estimated_mass_g)
        if estimate is None:
            item.energy_kcal = None
            item.protein_g = None
            item.carbohydrate_g = None
            item.fat_g = None
            return
        item.energy_kcal = _round_nutrient(estimate.energy_kcal)
        item.protein_g = _round_nutrient(estimate.protein_g)
        item.carbohydrate_g = _round_nutrient(estimate.carbohydrate_g)
        item.fat_g = _round_nutrient(estimate.fat_g)

    def _attach_image(
        self,
        meal: Meal,
        image: StoredObject,
        dimensions: tuple[int, int] | None,
    ) -> None:
        record = MealImage(
            storage_key=image.key,
            storage_backend=image.backend,
            content_type=image.content_type,
            byte_size=image.byte_size,
            content_sha256=image.content_sha256,
            width_px=dimensions[0] if dimensions else None,
            height_px=dimensions[1] if dimensions else None,
        )
        meal.images.append(record)
        self._meals.flush()

    @staticmethod
    def _validate(payload: MealInput) -> None:
        if payload.consumed_at.tzinfo is None:
            raise ValidationError("consumed_at must include a timezone offset.")
        if not payload.items:
            raise ValidationError("A meal must contain at least one food item.")
        if len(payload.items) > MAX_ITEMS_PER_MEAL:
            raise ConflictError(
                f"A meal may contain at most {MAX_ITEMS_PER_MEAL} items."
            )
        if payload.notes and len(payload.notes) > MAX_NOTE_LENGTH:
            raise ValidationError(
                f"Notes must not exceed {MAX_NOTE_LENGTH} characters."
            )
        try:
            MealType(payload.meal_type)
        except ValueError as exc:
            raise ValidationError(f"Unknown meal type {payload.meal_type!r}.") from exc
