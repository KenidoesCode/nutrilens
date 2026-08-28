"""Raw AI predictions, retained separately from the user's final record.

Keeping these apart from ``meal_items`` is what makes the system evaluable: the
model's original output survives every subsequent user edit, so accuracy can be
measured later against what the user actually said they ate.
"""

from __future__ import annotations

import uuid

from sqlalchemy import CheckConstraint, ForeignKey, Index, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from ..core.database import GUID, Base
from .mixins import Timestamped, UUIDPrimaryKey


class AiPrediction(UUIDPrimaryKey, Timestamped, Base):
    __tablename__ = "ai_predictions"
    __table_args__ = (
        Index("ix_ai_predictions_meal", "meal_id"),
        Index("ix_ai_predictions_engine_version", "engine", "model_version"),
        CheckConstraint("processing_ms >= 0", name="processing_ms_non_negative"),
    )

    meal_id: Mapped[uuid.UUID | None] = mapped_column(
        GUID(), ForeignKey("meals.id", ondelete="CASCADE"), nullable=True
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        GUID(), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True
    )
    engine: Mapped[str] = mapped_column(String(64), nullable=False)
    model_version: Mapped[str] = mapped_column(String(120), nullable=False)
    processing_ms: Mapped[int] = mapped_column(Integer, nullable=False)

    # The engine's verbatim structured output. Stored as text rather than a
    # typed column set because its shape belongs to the model, not the schema.
    raw_output_json: Mapped[str] = mapped_column(Text, nullable=False)
    warnings_json: Mapped[str | None] = mapped_column(Text, nullable=True)
    image_sha256: Mapped[str | None] = mapped_column(String(64), nullable=True, index=True)

    meal: Mapped[Meal | None] = relationship(back_populates="predictions")  # noqa: F821
