"""Meals, their items, images and portion estimates."""

from __future__ import annotations

import uuid
from datetime import datetime
from enum import StrEnum

from sqlalchemy import (
    CheckConstraint,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from ..core.database import GUID, Base, UTCDateTime
from .mixins import SoftDeletable, Timestamped, UUIDPrimaryKey


class MealType(StrEnum):
    BREAKFAST = "breakfast"
    LUNCH = "lunch"
    DINNER = "dinner"
    SNACK = "snack"
    BEVERAGE = "beverage"
    OTHER = "other"


class Meal(UUIDPrimaryKey, Timestamped, SoftDeletable, Base):
    """One eating occasion at one instant.

    ``consumed_at`` is stored in UTC and ``timezone`` records the zone the user
    was in. Both are needed: UTC alone cannot answer "when in your day did you
    eat", and a local timestamp alone breaks across travel and DST.
    """

    __tablename__ = "meals"
    __table_args__ = (
        Index("ix_meals_user_consumed_at", "user_id", "consumed_at"),
        Index("ix_meals_user_active_consumed", "user_id", "is_deleted", "consumed_at"),
        UniqueConstraint("user_id", "idempotency_key", name="uq_meals_user_idempotency_key"),
        CheckConstraint(
            "total_mass_g IS NULL OR total_mass_g >= 0", name="total_mass_non_negative"
        ),
    )

    user_id: Mapped[uuid.UUID] = mapped_column(
        GUID(), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True
    )
    consumed_at: Mapped[datetime] = mapped_column(UTCDateTime(), nullable=False)
    timezone: Mapped[str] = mapped_column(String(64), nullable=False, default="UTC")
    meal_type: Mapped[MealType] = mapped_column(String(16), nullable=False)
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)

    total_mass_g: Mapped[float | None] = mapped_column(Float, nullable=True)
    total_energy_kcal: Mapped[float | None] = mapped_column(Float, nullable=True)

    # Supplied by the client so a retried upload cannot create a second meal.
    idempotency_key: Mapped[str | None] = mapped_column(String(80), nullable=True)

    # Wall-clock timestamp recorded on the device, kept for audit when a client
    # syncs long after the fact with a possibly-wrong clock.
    client_recorded_at: Mapped[datetime | None] = mapped_column(UTCDateTime(), nullable=True)

    user: Mapped[User] = relationship(back_populates="meals")  # noqa: F821
    items: Mapped[list[MealItem]] = relationship(
        back_populates="meal", cascade="all, delete-orphan", lazy="selectin"
    )
    images: Mapped[list[MealImage]] = relationship(
        back_populates="meal", cascade="all, delete-orphan", lazy="selectin"
    )
    predictions: Mapped[list[AiPrediction]] = relationship(  # noqa: F821
        back_populates="meal", cascade="all, delete-orphan"
    )


class MealItem(UUIDPrimaryKey, Timestamped, SoftDeletable, Base):
    """One food within a meal, as finally recorded.

    The AI's original numbers are kept alongside the current ones so a user
    correction is visible as a correction, both to the user and to anyone
    later evaluating how well the model performs.
    """

    __tablename__ = "meal_items"
    __table_args__ = (
        Index("ix_meal_items_meal", "meal_id"),
        CheckConstraint("estimated_mass_g > 0", name="mass_positive"),
        CheckConstraint("estimated_volume_ml > 0", name="volume_positive"),
        CheckConstraint(
            "recognition_confidence >= 0 AND recognition_confidence <= 1",
            name="recognition_confidence_is_probability",
        ),
        CheckConstraint(
            "portion_confidence >= 0 AND portion_confidence <= 1",
            name="portion_confidence_is_probability",
        ),
    )

    meal_id: Mapped[uuid.UUID] = mapped_column(
        GUID(), ForeignKey("meals.id", ondelete="CASCADE"), nullable=False
    )
    food_key: Mapped[str | None] = mapped_column(String(80), nullable=True, index=True)
    display_name: Mapped[str] = mapped_column(String(160), nullable=False)
    category: Mapped[str] = mapped_column(String(16), nullable=False)

    estimated_volume_ml: Mapped[float] = mapped_column(Float, nullable=False)
    estimated_mass_g: Mapped[float] = mapped_column(Float, nullable=False)
    density_g_per_ml: Mapped[float] = mapped_column(Float, nullable=False)
    density_source: Mapped[str] = mapped_column(String(120), nullable=False)

    recognition_confidence: Mapped[float] = mapped_column(Float, nullable=False)
    portion_confidence: Mapped[float] = mapped_column(Float, nullable=False)

    energy_kcal: Mapped[float | None] = mapped_column(Float, nullable=True)
    protein_g: Mapped[float | None] = mapped_column(Float, nullable=True)
    carbohydrate_g: Mapped[float | None] = mapped_column(Float, nullable=True)
    fat_g: Mapped[float | None] = mapped_column(Float, nullable=True)

    was_user_corrected: Mapped[bool] = mapped_column(
        default=False, server_default="0", nullable=False
    )
    original_mass_g: Mapped[float | None] = mapped_column(Float, nullable=True)
    original_display_name: Mapped[str | None] = mapped_column(String(160), nullable=True)

    meal: Mapped[Meal] = relationship(back_populates="items")
    portion_estimates: Mapped[list[PortionEstimateRecord]] = relationship(
        back_populates="meal_item", cascade="all, delete-orphan"
    )


class MealImage(UUIDPrimaryKey, Timestamped, SoftDeletable, Base):
    """A stored meal photograph, referenced by opaque storage key.

    The bytes never live in the database. ``storage_key`` is resolved by the
    object-storage abstraction, so moving from local disk to S3 changes no row.
    """

    __tablename__ = "meal_images"
    __table_args__ = (
        Index("ix_meal_images_meal", "meal_id"),
        UniqueConstraint("content_sha256", "meal_id", name="uq_meal_images_content_meal"),
        CheckConstraint("byte_size > 0", name="byte_size_positive"),
    )

    meal_id: Mapped[uuid.UUID] = mapped_column(
        GUID(), ForeignKey("meals.id", ondelete="CASCADE"), nullable=False
    )
    storage_key: Mapped[str] = mapped_column(String(512), nullable=False)
    storage_backend: Mapped[str] = mapped_column(String(32), nullable=False)
    content_type: Mapped[str] = mapped_column(String(64), nullable=False)
    byte_size: Mapped[int] = mapped_column(Integer, nullable=False)
    width_px: Mapped[int | None] = mapped_column(Integer, nullable=True)
    height_px: Mapped[int | None] = mapped_column(Integer, nullable=True)

    # Content hash: dedupes retried uploads and detects corruption in transit.
    content_sha256: Mapped[str] = mapped_column(String(64), nullable=False, index=True)

    meal: Mapped[Meal] = relationship(back_populates="images")


class PortionEstimateRecord(UUIDPrimaryKey, Timestamped, Base):
    """An append-only history of portion estimates for one item.

    Never updated in place: the sequence of automatic estimate then user
    correction is exactly the signal needed to evaluate the estimator later.
    """

    __tablename__ = "portion_estimates"
    __table_args__ = (
        Index("ix_portion_estimates_item", "meal_item_id"),
        CheckConstraint("volume_ml > 0", name="volume_positive"),
        CheckConstraint("confidence >= 0 AND confidence <= 1", name="confidence_is_probability"),
    )

    meal_item_id: Mapped[uuid.UUID] = mapped_column(
        GUID(), ForeignKey("meal_items.id", ondelete="CASCADE"), nullable=False
    )
    volume_ml: Mapped[float] = mapped_column(Float, nullable=False)
    confidence: Mapped[float] = mapped_column(Float, nullable=False)
    method: Mapped[str] = mapped_column(String(48), nullable=False)
    assumptions_json: Mapped[str | None] = mapped_column(Text, nullable=True)

    meal_item: Mapped[MealItem] = relationship(back_populates="portion_estimates")
