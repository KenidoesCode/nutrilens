"""Meal image analysis.

The service owns the *use case* -- validate, store, infer, record -- while the
ML package owns the estimation. The pipeline is injected, so swapping engines
never touches this file.
"""

from __future__ import annotations

import hashlib
import uuid
from dataclasses import dataclass

from nutrilens_ml import ImageValidationError, MealAnalysisPipeline
from nutrilens_ml.inference.base import InferenceError
from nutrilens_ml.pipeline import AnalysisOptions
from nutrilens_ml.portion.estimator import ReferenceObject

from ..core.errors import (
    AnalysisFailedError,
    AppError,
    ErrorCode,
    PayloadTooLargeError,
    UnsupportedMediaTypeError,
)
from ..core.logging import get_logger
from ..repositories.prediction import PredictionRepository
from .storage import ObjectStorage, StoredObject

logger = get_logger(__name__)

ALLOWED_CONTENT_TYPES = frozenset({"image/jpeg", "image/png", "image/webp"})

# Validation failures the ML layer raises that map onto a client-visible
# HTTP status rather than a generic 422.
_MEDIA_TYPE_CODES = frozenset({"UNSUPPORTED_MEDIA_TYPE"})
_TOO_LARGE_CODES = frozenset({"IMAGE_TOO_LARGE"})


@dataclass(frozen=True, slots=True)
class AnalysisOutcome:
    prediction_id: uuid.UUID
    stored_image: StoredObject | None
    result: dict
    engine: str
    model_version: str
    processing_ms: int


class AnalysisService:
    def __init__(
        self,
        pipeline: MealAnalysisPipeline,
        storage: ObjectStorage,
        predictions: PredictionRepository,
        *,
        max_upload_bytes: int,
    ) -> None:
        self._pipeline = pipeline
        self._storage = storage
        self._predictions = predictions
        self._max_upload_bytes = max_upload_bytes

    def analyze(
        self,
        *,
        user_id: uuid.UUID,
        image_bytes: bytes,
        content_type: str,
        store_image: bool = True,
        reference: ReferenceObject | None = None,
    ) -> AnalysisOutcome:
        self._validate_upload(image_bytes, content_type)
        digest = hashlib.sha256(image_bytes).hexdigest()

        try:
            result = self._pipeline.analyze_bytes(
                image_bytes,
                declared_mime=content_type,
                options=AnalysisOptions(reference=reference),
            )
        except ImageValidationError as exc:
            raise self._translate_image_error(exc) from exc
        except InferenceError as exc:
            # The engine's internal message may name file paths; log it, and
            # give the client a code plus a neutral sentence.
            logger.error(
                "inference_failed", engine_code=exc.code, image_sha256=digest[:16]
            )
            raise AnalysisFailedError(
                "The meal image could not be analysed. Please try again.",
                details={"engine_code": exc.code},
            ) from exc

        stored: StoredObject | None = None
        if store_image:
            stored = self._storage.put(
                image_bytes, content_type=content_type, owner_id=user_id
            )

        payload = result.to_dict()
        prediction = self._predictions.record(
            user_id=user_id,
            meal_id=None,
            engine=result.engine,
            model_version=result.model_version,
            processing_ms=result.processing_ms,
            raw_output=payload,
            warnings=list(result.warnings),
            image_sha256=digest,
        )

        logger.info(
            "meal_analyzed",
            engine=result.engine,
            model_version=result.model_version,
            item_count=len(result.items),
            processing_ms=result.processing_ms,
            warnings=list(result.warnings),
        )

        return AnalysisOutcome(
            prediction_id=prediction.id,
            stored_image=stored,
            result=payload,
            engine=result.engine,
            model_version=result.model_version,
            processing_ms=result.processing_ms,
        )

    def attach_prediction_to_meal(
        self, prediction_id: uuid.UUID, meal_id: uuid.UUID
    ) -> None:
        self._predictions.attach_to_meal(prediction_id, meal_id)

    def _validate_upload(self, image_bytes: bytes, content_type: str) -> None:
        if content_type.lower() not in ALLOWED_CONTENT_TYPES:
            raise UnsupportedMediaTypeError(
                "Only JPEG, PNG and WebP images are supported.",
            )
        if not image_bytes:
            raise AnalysisFailedError(
                "The uploaded image was empty.", code=ErrorCode.INVALID_IMAGE
            )
        if len(image_bytes) > self._max_upload_bytes:
            raise PayloadTooLargeError(
                f"The image exceeds the {self._max_upload_bytes // (1024 * 1024)} MB limit."
            )

    @staticmethod
    def _translate_image_error(exc: ImageValidationError) -> AppError:
        if exc.code in _MEDIA_TYPE_CODES:
            return UnsupportedMediaTypeError(exc.message)
        if exc.code in _TOO_LARGE_CODES:
            return PayloadTooLargeError(exc.message)
        return AnalysisFailedError(exc.message, code=ErrorCode.INVALID_IMAGE)
