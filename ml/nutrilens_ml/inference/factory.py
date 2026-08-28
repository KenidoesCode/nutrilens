"""Engine selection.

The choice of recognition backend is configuration, resolved in exactly one
place. Callers depend on :class:`FoodRecognizer`, never on a concrete engine.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from pathlib import Path

from .base import FoodRecognizer, InferenceError
from .heuristic import HeuristicFoodRecognizer
from .onnx_engine import OnnxFoodRecognizer

logger = logging.getLogger(__name__)

ENGINE_HEURISTIC = "heuristic"
ENGINE_ONNX = "onnx"
ENGINE_AUTO = "auto"


@dataclass(frozen=True, slots=True)
class EngineConfig:
    engine: str = ENGINE_AUTO
    onnx_model_path: Path | None = None
    onnx_label_map_path: Path | None = None

    def onnx_is_configured(self) -> bool:
        return (
            self.onnx_model_path is not None
            and self.onnx_label_map_path is not None
            and self.onnx_model_path.is_file()
            and self.onnx_label_map_path.is_file()
        )


def build_recognizer(config: EngineConfig) -> FoodRecognizer:
    """Construct the configured recognizer.

    ``auto`` prefers ONNX when a model is actually present on disk and falls
    back to the classical engine otherwise. ``onnx`` is strict: a missing model
    is an error rather than a silent downgrade, because a deployment that
    believes it is running a model must not quietly run rules instead.
    """
    engine = config.engine.strip().lower()

    if engine == ENGINE_HEURISTIC:
        return HeuristicFoodRecognizer()

    if engine == ENGINE_ONNX:
        if not config.onnx_is_configured():
            raise InferenceError(
                "MODEL_NOT_CONFIGURED",
                "Engine 'onnx' was requested but no model and label map were found.",
            )
        return OnnxFoodRecognizer(config.onnx_model_path, config.onnx_label_map_path)  # type: ignore[arg-type]

    if engine == ENGINE_AUTO:
        if config.onnx_is_configured():
            return OnnxFoodRecognizer(
                config.onnx_model_path,  # type: ignore[arg-type]
                config.onnx_label_map_path,  # type: ignore[arg-type]
            )
        logger.info("No ONNX model configured; using the classical recognition engine.")
        return HeuristicFoodRecognizer()

    raise InferenceError("UNKNOWN_ENGINE", f"Unknown recognition engine {config.engine!r}.")
