"""Idempotency ledger for client sync operations."""

from __future__ import annotations

import uuid

from sqlalchemy import select

from ..core.database import utcnow
from ..models.sync import SyncOperation, SyncState
from .base import Repository


class SyncRepository(Repository):
    def find(self, user_id: uuid.UUID, idempotency_key: str) -> SyncOperation | None:
        statement = select(SyncOperation).where(
            SyncOperation.user_id == user_id,
            SyncOperation.idempotency_key == idempotency_key,
        )
        return self._session.execute(statement).scalar_one_or_none()

    def record_success(
        self,
        *,
        user_id: uuid.UUID,
        idempotency_key: str,
        operation: str,
        entity_type: str,
        entity_id: uuid.UUID | None,
    ) -> SyncOperation:
        """Record an accepted operation, or bump the attempt count of a replay.

        Returning the existing row unchanged is what makes a retry a no-op
        rather than a duplicate.
        """
        existing = self.find(user_id, idempotency_key)
        if existing is not None:
            existing.attempts += 1
            self._session.add(existing)
            return existing

        record = SyncOperation(
            user_id=user_id,
            idempotency_key=idempotency_key,
            operation=operation,
            entity_type=entity_type,
            entity_id=entity_id,
            state=SyncState.SYNCED,
            attempts=1,
            completed_at=utcnow(),
        )
        self._session.add(record)
        self.flush()
        return record

    def record_failure(
        self,
        *,
        user_id: uuid.UUID,
        idempotency_key: str,
        operation: str,
        entity_type: str,
        error: str,
    ) -> SyncOperation:
        existing = self.find(user_id, idempotency_key)
        if existing is not None:
            existing.attempts += 1
            existing.state = SyncState.FAILED
            existing.last_error = error[:500]
            self._session.add(existing)
            return existing

        record = SyncOperation(
            user_id=user_id,
            idempotency_key=idempotency_key,
            operation=operation,
            entity_type=entity_type,
            state=SyncState.FAILED,
            attempts=1,
            last_error=error[:500],
        )
        self._session.add(record)
        self.flush()
        return record
