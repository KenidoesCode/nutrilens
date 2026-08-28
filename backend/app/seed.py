"""Seed the food catalog from the bundled reference dataset.

Idempotent by design, so it can run on every deploy: a fresh environment and an
upgraded one converge on the same catalog. Run as ``python -m app.seed``.
"""

from __future__ import annotations

import sys

from .core.config import get_settings
from .core.database import create_db_engine, create_session_factory, session_scope
from .core.logging import configure_logging, get_logger
from .repositories.food import FoodRepository
from .services.food_service import FoodService

logger = get_logger("nutrilens.seed")


def seed() -> int:
    settings = get_settings()
    configure_logging(level=settings.log_level, json_output=settings.log_json)

    engine = create_db_engine(settings)
    factory = create_session_factory(engine)
    try:
        with session_scope(factory) as session:
            count = FoodService(FoodRepository(session)).seed_from_dataset()
        logger.info("food_catalog_seeded", entries=count)
        return count
    finally:
        engine.dispose()


def main() -> int:
    try:
        seed()
    except Exception as exc:  # noqa: BLE001 - a failed seed must not be silent
        logger.error("food_catalog_seed_failed", error_type=type(exc).__name__)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
