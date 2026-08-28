"""NutriLens ML: food recognition, portion estimation and density conversion.

Nothing in this package performs I/O beyond reading its own bundled dataset,
and nothing depends on the web framework or the mobile client. It is the
domain core shared by every consumer.
"""

from .catalog import FoodCatalog, FoodRecord, load_catalog
from .density.engine import DensityEngine, DensityUnavailableError
from .domain import (
    AnalysisResult,
    AnalyzedFoodItem,
    BoundingBox,
    ConfidenceBand,
    Detection,
    FoodCategory,
    MassEstimate,
    NutritionEstimate,
    PortionEstimate,
)
from .inference.base import FoodRecognizer, InferenceError
from .inference.factory import EngineConfig, build_recognizer
from .nutrition import NutritionEstimator
from .pipeline import AnalysisOptions, MealAnalysisPipeline
from .portion.estimator import PortionEstimator, ReferenceObject
from .preprocessing.image import ImageValidationError, PreparedImage, prepare_image

__version__ = "0.1.0"

__all__ = [
    "AnalysisOptions",
    "AnalysisResult",
    "AnalyzedFoodItem",
    "BoundingBox",
    "ConfidenceBand",
    "Detection",
    "DensityEngine",
    "DensityUnavailableError",
    "EngineConfig",
    "FoodCatalog",
    "FoodCategory",
    "FoodRecognizer",
    "FoodRecord",
    "ImageValidationError",
    "InferenceError",
    "MassEstimate",
    "MealAnalysisPipeline",
    "NutritionEstimate",
    "NutritionEstimator",
    "PortionEstimate",
    "PortionEstimator",
    "PreparedImage",
    "ReferenceObject",
    "__version__",
    "build_recognizer",
    "load_catalog",
    "prepare_image",
]
