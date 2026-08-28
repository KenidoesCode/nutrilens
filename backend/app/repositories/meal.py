"""Meal persistence, including the queries the timeline and analytics need."""

from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import Select, func, select
from sqlalchemy.orm import selectinload

from ..core.database import utcnow
from ..models.meal import Meal, MealItem, PortionEstimateRecord
from .base import Repository

MAX_PAGE_SIZE = 100
DEFAULT_PAGE_SIZE = 20


class MealRepository(Repository):
    def _base_query(self, user_id: uuid.UUID) -> Select[tuple[Meal]]:
        return select(Meal).where(Meal.user_id == user_id, Meal.is_deleted.is_(False))

    def get(self, user_id: uuid.UUID, meal_id: uuid.UUID) -> Meal | None:
        statement = (
            self._base_query(user_id)
            .where(Meal.id == meal_id)
            .options(selectinload(Meal.items), selectinload(Meal.images))
        )
        return self._session.execute(statement).scalar_one_or_none()

    def get_by_idempotency_key(self, user_id: uuid.UUID, key: str) -> Meal | None:
        """Find a meal already created by this client operation.

        Includes soft-deleted rows on purpose: replaying the original create
        must not resurrect a meal the user has since deleted.
        """
        statement = (
            select(Meal)
            .where(Meal.user_id == user_id, Meal.idempotency_key == key)
            .options(selectinload(Meal.items), selectinload(Meal.images))
        )
        return self._session.execute(statement).scalar_one_or_none()

    def list_page(
        self,
        user_id: uuid.UUID,
        *,
        limit: int = DEFAULT_PAGE_SIZE,
        offset: int = 0,
        start: datetime | None = None,
        end: datetime | None = None,
    ) -> tuple[list[Meal], int]:
        """A page of meals, newest first, plus the total matching count."""
        limit = max(1, min(limit, MAX_PAGE_SIZE))
        offset = max(0, offset)

        statement = self._base_query(user_id)
        if start is not None:
            statement = statement.where(Meal.consumed_at >= start)
        if end is not None:
            statement = statement.where(Meal.consumed_at < end)

        total = self._session.execute(
            select(func.count()).select_from(statement.subquery())
        ).scalar_one()

        page = (
            statement.order_by(Meal.consumed_at.desc(), Meal.id.desc())
            .limit(limit)
            .offset(offset)
            .options(selectinload(Meal.items), selectinload(Meal.images))
        )
        return list(self._session.execute(page).scalars()), int(total)

    def list_in_range(
        self, user_id: uuid.UUID, start: datetime, end: datetime
    ) -> list[Meal]:
        """Every meal in a half-open UTC range, oldest first.

        Analytics needs all of them, not a page: a partial range would silently
        produce a wrong eating window.
        """
        statement = (
            self._base_query(user_id)
            .where(Meal.consumed_at >= start, Meal.consumed_at < end)
            .order_by(Meal.consumed_at.asc())
        )
        return list(self._session.execute(statement).scalars())

    def add(self, meal: Meal) -> Meal:
        self._session.add(meal)
        self.flush()
        return meal

    def add_portion_estimate(self, record: PortionEstimateRecord) -> PortionEstimateRecord:
        self._session.add(record)
        self.flush()
        return record

    def get_item(self, user_id: uuid.UUID, item_id: uuid.UUID) -> MealItem | None:
        """Fetch an item only if it belongs to this user.

        The ownership join is in the query rather than checked afterwards, so
        no caller can forget it.
        """
        statement = (
            select(MealItem)
            .join(Meal, MealItem.meal_id == Meal.id)
            .where(
                MealItem.id == item_id,
                MealItem.is_deleted.is_(False),
                Meal.user_id == user_id,
                Meal.is_deleted.is_(False),
            )
        )
        return self._session.execute(statement).scalar_one_or_none()

    def soft_delete(self, meal: Meal) -> None:
        now = utcnow()
        meal.is_deleted = True
        meal.deleted_at = now
        for item in meal.items:
            item.is_deleted = True
            item.deleted_at = now
        for image in meal.images:
            image.is_deleted = True
            image.deleted_at = now
        self._session.add(meal)

    def soft_delete_item(self, item: MealItem) -> None:
        item.is_deleted = True
        item.deleted_at = utcnow()
        self._session.add(item)

    def recalculate_totals(self, meal: Meal) -> None:
        """Recompute meal totals from its live items.

        Totals are derived, never accumulated incrementally: an edit or a
        deletion would otherwise leave them permanently out of step.
        """
        live = [item for item in meal.items if not item.is_deleted]
        meal.total_mass_g = (
            round(sum(item.estimated_mass_g for item in live), 2) if live else None
        )
        energies = [item.energy_kcal for item in live if item.energy_kcal is not None]
        meal.total_energy_kcal = round(sum(energies), 2) if energies else None
        self._session.add(meal)

    def count_for_user(self, user_id: uuid.UUID) -> int:
        statement = select(func.count()).select_from(self._base_query(user_id).subquery())
        return int(self._session.execute(statement).scalar_one())

    def changed_since(
        self, user_id: uuid.UUID, since: datetime, limit: int = MAX_PAGE_SIZE
    ) -> list[Meal]:
        """Meals touched since a timestamp, for incremental client pulls.

        Soft-deleted rows are included: the client has to learn about
        deletions, and a row that simply vanished from the feed would leave a
        stale copy on the device forever.
        """
        statement = (
            select(Meal)
            .where(Meal.user_id == user_id, Meal.updated_at > since)
            .order_by(Meal.updated_at.asc())
            .limit(max(1, min(limit, MAX_PAGE_SIZE)))
            .options(selectinload(Meal.items), selectinload(Meal.images))
        )
        return list(self._session.execute(statement).scalars())
