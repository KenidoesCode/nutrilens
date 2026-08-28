"""Pydantic request/response models. The API's public contract."""

from .analysis import AnalysisItemResponse, AnalysisResponse, ReferenceObjectRequest
from .analytics import (
    DailyPatternResponse,
    NutritionTotalsResponse,
    RangePatternResponse,
)
from .auth import (
    LoginRequest,
    LogoutRequest,
    RefreshRequest,
    RegisterRequest,
    TokenResponse,
)
from .common import ErrorResponse, Page, PageMeta
from .food import FoodResponse
from .meal import (
    MealCreateRequest,
    MealItemCreateRequest,
    MealItemResponse,
    MealResponse,
    PortionCorrectionRequest,
    RenameItemRequest,
)
from .sync import SyncPullResponse, SyncPushRequest, SyncPushResponse
from .user import UserResponse, UserUpdateRequest

__all__ = [
    "AnalysisItemResponse",
    "AnalysisResponse",
    "DailyPatternResponse",
    "ErrorResponse",
    "FoodResponse",
    "LoginRequest",
    "LogoutRequest",
    "MealCreateRequest",
    "MealItemCreateRequest",
    "MealItemResponse",
    "MealResponse",
    "NutritionTotalsResponse",
    "Page",
    "PageMeta",
    "PortionCorrectionRequest",
    "RangePatternResponse",
    "RefreshRequest",
    "ReferenceObjectRequest",
    "RegisterRequest",
    "RenameItemRequest",
    "SyncPullResponse",
    "SyncPushRequest",
    "SyncPushResponse",
    "TokenResponse",
    "UserResponse",
    "UserUpdateRequest",
]
