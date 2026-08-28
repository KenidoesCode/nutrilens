"""Version 1 of the public API."""

from fastapi import APIRouter

from . import analysis, analytics, auth, foods, meals, sync, users

router = APIRouter()
router.include_router(auth.router)
router.include_router(users.router)
router.include_router(meals.router)
router.include_router(foods.router)
router.include_router(analysis.router)
router.include_router(analytics.router)
router.include_router(sync.router)

__all__ = ["router"]
