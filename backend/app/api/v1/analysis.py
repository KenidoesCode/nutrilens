"""Meal image analysis endpoint."""

from __future__ import annotations

from fastapi import APIRouter, Depends, File, Form, UploadFile, status
from nutrilens_ml.portion.estimator import ReferenceObject

from ...core.config import Settings
from ...core.errors import PayloadTooLargeError, ValidationError
from ...models.user import User
from ...schemas.analysis import AnalysisResponse
from ...schemas.common import ErrorResponse
from ...services.analysis_service import AnalysisService
from ..dependencies import get_analysis_service, get_current_user, get_settings_dependency

router = APIRouter(
    prefix="/analysis",
    tags=["analysis"],
    responses={
        401: {"model": ErrorResponse},
        413: {"model": ErrorResponse},
        415: {"model": ErrorResponse},
        422: {"model": ErrorResponse},
    },
)


@router.post(
    "/meal-image",
    response_model=AnalysisResponse,
    status_code=status.HTTP_200_OK,
    summary="Analyse a meal photograph",
    description=(
        "Detects foods, estimates portion volume and converts it to mass using "
        "reference densities.\n\n"
        "**Every figure returned is an estimate, not a measurement.** Volume is "
        "inferred from a single uncalibrated image, which cannot recover depth; "
        "confidences are returned alongside every value and clients must surface "
        "them. Supplying a `reference_*` object of known size materially improves "
        "the scale estimate."
    ),
)
async def analyze_meal_image(
    image: UploadFile = File(description="JPEG, PNG or WebP meal photograph."),
    store_image: bool = Form(default=True),
    reference_name: str | None = Form(default=None),
    reference_real_area_cm2: float | None = Form(default=None),
    reference_image_area_ratio: float | None = Form(default=None),
    user: User = Depends(get_current_user),
    service: AnalysisService = Depends(get_analysis_service),
    settings: Settings = Depends(get_settings_dependency),
) -> AnalysisResponse:
    data = await _read_bounded(image, settings.max_upload_bytes)
    outcome = service.analyze(
        user_id=user.id,
        image_bytes=data,
        content_type=image.content_type or "application/octet-stream",
        store_image=store_image,
        reference=_build_reference(
            reference_name, reference_real_area_cm2, reference_image_area_ratio
        ),
    )
    return AnalysisResponse(
        prediction_id=outcome.prediction_id,
        items=outcome.result["items"],
        engine=outcome.result["engine"],
        model_version=outcome.result["model_version"],
        processing_ms=outcome.result["processing_ms"],
        total_estimated_mass_g=outcome.result["total_estimated_mass_g"],
        warnings=outcome.result["warnings"],
    )


async def _read_bounded(upload: UploadFile, max_bytes: int) -> bytes:
    """Read an upload while refusing to buffer more than the limit.

    Reading in chunks and stopping at the limit means an oversized upload is
    rejected without ever being held in memory in full.
    """
    chunks: list[bytes] = []
    total = 0
    while chunk := await upload.read(64 * 1024):
        total += len(chunk)
        if total > max_bytes:
            raise PayloadTooLargeError(
                f"The image exceeds the {max_bytes // (1024 * 1024)} MB limit."
            )
        chunks.append(chunk)
    return b"".join(chunks)


def _build_reference(
    name: str | None, real_area_cm2: float | None, image_area_ratio: float | None
) -> ReferenceObject | None:
    supplied = [name, real_area_cm2, image_area_ratio]
    if all(value is None for value in supplied):
        return None
    if any(value is None for value in supplied):
        raise ValidationError(
            "A reference object needs reference_name, reference_real_area_cm2 "
            "and reference_image_area_ratio together."
        )
    try:
        return ReferenceObject(
            name=str(name)[:80],
            real_area_cm2=float(real_area_cm2),  # type: ignore[arg-type]
            image_area_ratio=float(image_area_ratio),  # type: ignore[arg-type]
            confidence=0.8,
        )
    except ValueError as exc:
        raise ValidationError(f"Invalid reference object: {exc}") from exc
