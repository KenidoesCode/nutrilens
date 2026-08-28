"""Persistence for raw AI predictions."""

from __future__ import annotations

import json
import uuid

from sqlalchemy import select

from ..models.ai_prediction import AiPrediction
from .base import Repository


class PredictionRepository(Repository):
    def record(
        self,
        *,
        user_id: uuid.UUID,
        meal_id: uuid.UUID | None,
        engine: str,
        model_version: str,
        processing_ms: int,
        raw_output: dict,
        warnings: list[str],
        image_sha256: str | None,
    ) -> AiPrediction:
        prediction = AiPrediction(
            user_id=user_id,
            meal_id=meal_id,
            engine=engine,
            model_version=model_version,
            processing_ms=processing_ms,
            raw_output_json=json.dumps(raw_output, separators=(",", ":"), sort_keys=True),
            warnings_json=json.dumps(warnings) if warnings else None,
            image_sha256=image_sha256,
        )
        self._session.add(prediction)
        self.flush()
        return prediction

    def attach_to_meal(self, prediction_id: uuid.UUID, meal_id: uuid.UUID) -> None:
        prediction = self._session.get(AiPrediction, prediction_id)
        if prediction is not None:
            prediction.meal_id = meal_id
            self._session.add(prediction)

    def list_for_meal(self, meal_id: uuid.UUID) -> list[AiPrediction]:
        statement = (
            select(AiPrediction)
            .where(AiPrediction.meal_id == meal_id)
            .order_by(AiPrediction.created_at.asc())
        )
        return list(self._session.execute(statement).scalars())
