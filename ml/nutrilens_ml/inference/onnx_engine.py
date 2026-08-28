"""ONNX Runtime recognition backend.

This is the production path. It performs genuine neural inference: the image
is letterboxed by the preprocessing stage, normalised to the model's expected
input statistics, run through ``onnxruntime``, and the output logits are
mapped to catalog foods through a label map shipped alongside the weights.

No weights are bundled with this repository -- an ImageNet-style classifier
is not a food-portion model, and shipping one would imply an accuracy we have
not measured. Point ``NUTRILENS_ONNX_MODEL_PATH`` at a model plus its label
map and this engine takes over from the heuristic one with no other change.

Expected label map format (JSON)::

    {
      "model_version": "food-cls-v1",
      "input_name": "input",
      "input_layout": "NCHW",
      "mean": [0.485, 0.456, 0.406],
      "std": [0.229, 0.224, 0.225],
      "labels": ["rice", "dal", ...]
    }

Each label must resolve against the food catalog (key, display name or alias).
"""

from __future__ import annotations

import json
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np

from ..catalog import FoodCatalog, load_catalog
from ..domain import BoundingBox, Detection
from ..preprocessing.image import PreparedImage
from ..preprocessing.segmentation import segment_plate
from .base import FoodRecognizer, InferenceError

ENGINE_NAME = "onnx-runtime"
DEFAULT_TOP_K = 3
MIN_REPORTABLE_CONFIDENCE = 0.10


@dataclass(frozen=True, slots=True)
class OnnxLabelMap:
    model_version: str
    input_name: str
    input_layout: str
    mean: tuple[float, float, float]
    std: tuple[float, float, float]
    labels: tuple[str, ...]

    @classmethod
    def load(cls, path: Path) -> OnnxLabelMap:
        payload: dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
        labels = tuple(str(label) for label in payload["labels"])
        if not labels:
            raise InferenceError("MODEL_LABELS_EMPTY", "The model label map contains no labels.")
        layout = str(payload.get("input_layout", "NCHW")).upper()
        if layout not in {"NCHW", "NHWC"}:
            raise InferenceError(
                "MODEL_LAYOUT_UNSUPPORTED", f"Unsupported input layout {layout!r}."
            )
        mean = tuple(float(v) for v in payload.get("mean", (0.485, 0.456, 0.406)))
        std = tuple(float(v) for v in payload.get("std", (0.229, 0.224, 0.225)))
        if len(mean) != 3 or len(std) != 3:
            raise InferenceError(
                "MODEL_NORMALIZATION_INVALID", "mean and std must each have three values."
            )
        if any(value <= 0 for value in std):
            raise InferenceError(
                "MODEL_NORMALIZATION_INVALID", "std values must be positive."
            )
        return cls(
            model_version=str(payload.get("model_version", "unknown")),
            input_name=str(payload.get("input_name", "input")),
            input_layout=layout,
            mean=mean,  # type: ignore[arg-type]
            std=std,  # type: ignore[arg-type]
            labels=labels,
        )


def softmax(logits: np.ndarray) -> np.ndarray:
    """Numerically stable softmax over the last axis."""
    shifted = logits - np.max(logits, axis=-1, keepdims=True)
    exponentiated = np.exp(shifted)
    return exponentiated / np.sum(exponentiated, axis=-1, keepdims=True)


class OnnxFoodRecognizer(FoodRecognizer):
    """Classifies each segmented region with an ONNX model.

    Segmentation still comes from the classical stage: a classifier tells us
    *what* is in a crop, not *where* the crops are. Replacing this with a
    detection model means implementing :class:`FoodRecognizer` again -- nothing
    outside this file changes.
    """

    def __init__(
        self,
        model_path: str | Path,
        label_map_path: str | Path,
        *,
        catalog: FoodCatalog | None = None,
        providers: list[str] | None = None,
        top_k: int = DEFAULT_TOP_K,
    ) -> None:
        self._model_path = Path(model_path)
        self._label_map_path = Path(label_map_path)
        self._catalog = catalog or load_catalog()
        self._providers = providers
        self._top_k = max(1, top_k)
        self._session: Any | None = None
        self._label_map: OnnxLabelMap | None = None
        self._input_size: int | None = None
        self._lock = threading.Lock()

    @property
    def name(self) -> str:
        return ENGINE_NAME

    @property
    def model_version(self) -> str:
        label_map = self._label_map
        version = label_map.model_version if label_map else "unloaded"
        return f"{version}+catalog-{self._catalog.dataset_version}"

    def warmup(self) -> None:
        self._ensure_session()

    def close(self) -> None:
        with self._lock:
            self._session = None

    def _ensure_session(self) -> tuple[Any, OnnxLabelMap]:
        with self._lock:
            if self._session is not None and self._label_map is not None:
                return self._session, self._label_map

            if not self._model_path.is_file():
                raise InferenceError(
                    "MODEL_NOT_FOUND", f"ONNX model not found at {self._model_path}."
                )
            if not self._label_map_path.is_file():
                raise InferenceError(
                    "MODEL_LABELS_NOT_FOUND",
                    f"Label map not found at {self._label_map_path}.",
                )
            try:
                import onnxruntime  # noqa: PLC0415 - optional heavy dependency
            except ImportError as exc:
                raise InferenceError(
                    "ONNX_RUNTIME_MISSING",
                    "onnxruntime is not installed; install the 'onnx' extra.",
                ) from exc

            label_map = OnnxLabelMap.load(self._label_map_path)
            providers = self._providers or ["CPUExecutionProvider"]
            options = onnxruntime.SessionOptions()
            options.graph_optimization_level = (
                onnxruntime.GraphOptimizationLevel.ORT_ENABLE_ALL
            )
            session = onnxruntime.InferenceSession(
                str(self._model_path), sess_options=options, providers=providers
            )
            self._session = session
            self._label_map = label_map
            self._input_size = self._infer_input_size(session, label_map)
            return session, label_map

    @staticmethod
    def _infer_input_size(session: Any, label_map: OnnxLabelMap) -> int:
        shape = session.get_inputs()[0].shape
        spatial = shape[2:] if label_map.input_layout == "NCHW" else shape[1:3]
        for dimension in spatial:
            if isinstance(dimension, int) and dimension > 0:
                return dimension
        return 224

    def recognize(self, image: PreparedImage) -> list[Detection]:
        session, label_map = self._ensure_session()
        regions = segment_plate(image.rgb)
        if not regions:
            return []

        detections: list[Detection] = []
        for region in regions:
            crop = self._crop(image, region.bbox)
            tensor = self._to_tensor(crop, label_map)
            try:
                outputs = session.run(None, {label_map.input_name: tensor})
            except Exception as exc:  # onnxruntime raises bare Exception subclasses
                raise InferenceError(
                    "INFERENCE_FAILED", "The recognition model failed to run."
                ) from exc

            probabilities = softmax(np.asarray(outputs[0], dtype=np.float32).reshape(-1))
            if probabilities.size != len(label_map.labels):
                raise InferenceError(
                    "MODEL_OUTPUT_MISMATCH",
                    "Model output size does not match the label map.",
                )

            for index in np.argsort(probabilities)[::-1][: self._top_k]:
                confidence = float(probabilities[index])
                if confidence < MIN_REPORTABLE_CONFIDENCE:
                    break
                record = self._catalog.resolve(label_map.labels[index])
                if record is None:
                    continue
                detections.append(
                    Detection(
                        label=record.display_name,
                        category=record.category,
                        confidence=confidence,
                        bbox=region.bbox,
                        pixel_area_ratio=region.area_ratio,
                        engine=self.name,
                        attributes={
                            "food_key": record.key,
                            "area_ratio_of_plate": round(region.area_ratio_of_plate, 4),
                        },
                    )
                )
                break  # one label per region; the rest are recorded by the caller if needed

        detections.sort(key=lambda d: d.confidence, reverse=True)
        return detections

    def _crop(self, image: PreparedImage, bbox: BoundingBox) -> np.ndarray:
        height, width = image.rgb.shape[:2]
        x0 = int(bbox.x * width)
        y0 = int(bbox.y * height)
        x1 = max(x0 + 1, int((bbox.x + bbox.width) * width))
        y1 = max(y0 + 1, int((bbox.y + bbox.height) * height))
        return image.rgb[y0:y1, x0:x1]

    def _to_tensor(self, crop: np.ndarray, label_map: OnnxLabelMap) -> np.ndarray:
        size = self._input_size or 224
        resized = _resize_nearest(crop, size)
        normalised = resized.astype(np.float32) / 255.0
        normalised = (normalised - np.asarray(label_map.mean, dtype=np.float32)) / np.asarray(
            label_map.std, dtype=np.float32
        )
        if label_map.input_layout == "NCHW":
            return np.ascontiguousarray(normalised.transpose(2, 0, 1)[None, ...])
        return np.ascontiguousarray(normalised[None, ...])


def _resize_nearest(image: np.ndarray, size: int) -> np.ndarray:
    height, width = image.shape[:2]
    rows = np.linspace(0, height - 1, size).round().astype(np.intp)
    cols = np.linspace(0, width - 1, size).round().astype(np.intp)
    return image[np.ix_(rows, cols)]
