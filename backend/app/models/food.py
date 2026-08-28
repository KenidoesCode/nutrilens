"""Server-side food catalog and density reference tables.

The bundled ML dataset seeds these tables. Once in the database they can be
versioned, extended per deployment and audited, without a code release.
"""

from __future__ import annotations

import uuid

from sqlalchemy import CheckConstraint, Float, ForeignKey, Index, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from ..core.database import GUID, Base
from .mixins import Timestamped, UUIDPrimaryKey


class FoodCatalogEntry(UUIDPrimaryKey, Timestamped, Base):
    __tablename__ = "food_catalog"
    __table_args__ = (
        UniqueConstraint("food_key", name="uq_food_catalog_food_key"),
        Index("ix_food_catalog_category", "category"),
    )

    food_key: Mapped[str] = mapped_column(String(80), nullable=False)
    display_name: Mapped[str] = mapped_column(String(160), nullable=False)
    display_name_te: Mapped[str | None] = mapped_column(String(160), nullable=True)
    category: Mapped[str] = mapped_column(String(16), nullable=False)

    energy_kcal_per_100g: Mapped[float | None] = mapped_column(Float, nullable=True)
    protein_g_per_100g: Mapped[float | None] = mapped_column(Float, nullable=True)
    carbohydrate_g_per_100g: Mapped[float | None] = mapped_column(Float, nullable=True)
    fat_g_per_100g: Mapped[float | None] = mapped_column(Float, nullable=True)

    densities: Mapped[list[FoodDensity]] = relationship(
        back_populates="food", cascade="all, delete-orphan", lazy="selectin"
    )


class FoodDensity(UUIDPrimaryKey, Timestamped, Base):
    """A density value with its provenance.

    Several sources may disagree about the same food, so provenance is part of
    the key rather than an afterthought.
    """

    __tablename__ = "food_densities"
    __table_args__ = (
        UniqueConstraint("food_id", "source", "source_version", name="uq_food_densities_source"),
        CheckConstraint(
            "density_g_per_ml > 0 AND density_g_per_ml < 3", name="density_is_plausible"
        ),
        CheckConstraint("confidence >= 0 AND confidence <= 1", name="confidence_is_probability"),
    )

    food_id: Mapped[uuid.UUID] = mapped_column(
        GUID(), ForeignKey("food_catalog.id", ondelete="CASCADE"), nullable=False, index=True
    )
    category: Mapped[str] = mapped_column(String(16), nullable=False)
    density_g_per_ml: Mapped[float] = mapped_column(Float, nullable=False)
    source: Mapped[str] = mapped_column(String(120), nullable=False)
    source_version: Mapped[str] = mapped_column(String(40), nullable=False)
    confidence: Mapped[float] = mapped_column(Float, nullable=False)

    food: Mapped[FoodCatalogEntry] = relationship(back_populates="densities")
