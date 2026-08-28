"""SQLAlchemy ORM models. Importing this package registers every table."""

from .ai_prediction import AiPrediction
from .audit import AuditEvent
from .food import FoodCatalogEntry, FoodDensity
from .meal import Meal, MealImage, MealItem, MealType, PortionEstimateRecord
from .sync import SyncOperation, SyncState
from .user import RefreshToken, User

__all__ = [
    "AiPrediction",
    "AuditEvent",
    "FoodCatalogEntry",
    "FoodDensity",
    "Meal",
    "MealImage",
    "MealItem",
    "MealType",
    "PortionEstimateRecord",
    "RefreshToken",
    "SyncOperation",
    "SyncState",
    "User",
]
