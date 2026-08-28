"""Dependency wiring.

Everything a handler needs is constructed here and nowhere else, so a handler
receives finished collaborators rather than assembling them itself.
"""

from __future__ import annotations

from collections.abc import Iterator

from fastapi import Depends, Header, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from nutrilens_ml import DensityEngine, NutritionEstimator
from sqlalchemy.orm import Session

from ..core.config import Settings
from ..core.errors import AuthenticationError, RateLimitedError
from ..domain.chrononutrition import ChrononutritionEngine
from ..models.user import User
from ..repositories.audit import AuditRepository
from ..repositories.food import FoodRepository
from ..repositories.meal import MealRepository
from ..repositories.prediction import PredictionRepository
from ..repositories.sync import SyncRepository
from ..repositories.user import UserRepository
from ..services.analysis_service import AnalysisService
from ..services.analytics_service import AnalyticsService
from ..services.auth_service import AuthService
from ..services.food_service import FoodService
from ..services.meal_service import MealService
from ..services.rate_limit import RateLimiter

# auto_error is off so a missing header raises our own error shape rather than
# FastAPI's, keeping every 401 identical to clients.
bearer_scheme = HTTPBearer(auto_error=False)


def get_settings_dependency(request: Request) -> Settings:
    """The settings this application was built with.

    Reading the module-level cache instead would make an app constructed with
    explicit settings silently run on the environment's settings, so anything
    embedding the app -- tests, a second app in one process -- would be
    configured differently from what it asked for.
    """
    return request.app.state.settings


def get_session(request: Request) -> Iterator[Session]:
    """One database session per request, committed on success.

    The transaction boundary lives here so a handler that touches several
    repositories still gets all-or-nothing semantics without managing it.
    """
    factory = request.app.state.session_factory
    session: Session = factory()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()


def get_rate_limiter(request: Request) -> RateLimiter:
    return request.app.state.rate_limiter


def get_chrononutrition_engine(request: Request) -> ChrononutritionEngine:
    return request.app.state.chrononutrition_engine


def get_density_engine(request: Request) -> DensityEngine:
    return request.app.state.density_engine


def get_nutrition_estimator(request: Request) -> NutritionEstimator:
    return request.app.state.nutrition_estimator


# --- repositories --------------------------------------------------------


def get_user_repository(session: Session = Depends(get_session)) -> UserRepository:
    return UserRepository(session)


def get_meal_repository(session: Session = Depends(get_session)) -> MealRepository:
    return MealRepository(session)


def get_food_repository(session: Session = Depends(get_session)) -> FoodRepository:
    return FoodRepository(session)


def get_audit_repository(session: Session = Depends(get_session)) -> AuditRepository:
    return AuditRepository(session)


def get_sync_repository(session: Session = Depends(get_session)) -> SyncRepository:
    return SyncRepository(session)


def get_prediction_repository(
    session: Session = Depends(get_session),
) -> PredictionRepository:
    return PredictionRepository(session)


# --- services ------------------------------------------------------------


def get_auth_service(
    users: UserRepository = Depends(get_user_repository),
    audit: AuditRepository = Depends(get_audit_repository),
    settings: Settings = Depends(get_settings_dependency),
) -> AuthService:
    return AuthService(users, audit, settings)


def get_meal_service(
    meals: MealRepository = Depends(get_meal_repository),
    sync: SyncRepository = Depends(get_sync_repository),
    audit: AuditRepository = Depends(get_audit_repository),
    density: DensityEngine = Depends(get_density_engine),
    nutrition: NutritionEstimator = Depends(get_nutrition_estimator),
) -> MealService:
    return MealService(meals, sync, audit, density, nutrition)


def get_analysis_service(
    request: Request,
    predictions: PredictionRepository = Depends(get_prediction_repository),
    settings: Settings = Depends(get_settings_dependency),
) -> AnalysisService:
    return AnalysisService(
        request.app.state.analysis_pipeline,
        request.app.state.object_storage,
        predictions,
        max_upload_bytes=settings.max_upload_bytes,
    )


def get_analytics_service(
    meals: MealRepository = Depends(get_meal_repository),
    engine: ChrononutritionEngine = Depends(get_chrononutrition_engine),
) -> AnalyticsService:
    return AnalyticsService(meals, engine)


def get_food_service(
    foods: FoodRepository = Depends(get_food_repository),
) -> FoodService:
    return FoodService(foods)


# --- authentication ------------------------------------------------------


def get_current_user(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
    auth: AuthService = Depends(get_auth_service),
) -> User:
    if credentials is None or not credentials.credentials:
        raise AuthenticationError("Authentication is required for this endpoint.")
    return auth.authenticate_access_token(credentials.credentials)


def client_user_agent(user_agent: str | None = Header(default=None)) -> str | None:
    return user_agent


def enforce_auth_rate_limit(
    request: Request,
    limiter: RateLimiter = Depends(get_rate_limiter),
    settings: Settings = Depends(get_settings_dependency),
) -> None:
    """Tighter budget for credential endpoints.

    Applied per client address: the general per-request limit is far too
    generous to slow down password guessing.
    """
    if not settings.rate_limit_enabled:
        return
    key = f"auth:{_client_key(request)}"
    result = limiter.check(
        key,
        limit=settings.auth_rate_limit_requests,
        window_seconds=settings.auth_rate_limit_window_seconds,
    )
    if not result.allowed:
        raise RateLimitedError(result.retry_after_seconds)


def _client_key(request: Request) -> str:
    client = request.client
    return client.host if client else "unknown"
