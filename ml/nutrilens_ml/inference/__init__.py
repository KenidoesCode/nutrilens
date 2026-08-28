"""Pluggable food recognition engines."""

from .base import FoodRecognizer, InferenceError
from .factory import EngineConfig, build_recognizer
from .heuristic import HeuristicFoodRecognizer
from .onnx_engine import OnnxFoodRecognizer, OnnxLabelMap, softmax

__all__ = [
    "EngineConfig",
    "FoodRecognizer",
    "HeuristicFoodRecognizer",
    "InferenceError",
    "OnnxFoodRecognizer",
    "OnnxLabelMap",
    "build_recognizer",
    "softmax",
]
