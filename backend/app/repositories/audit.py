"""Append-only audit writer."""

from __future__ import annotations

import json
import uuid
from typing import Any

from sqlalchemy import select

from ..models.audit import AuditEvent
from .base import Repository

MAX_CLIENT_HINT_LENGTH = 120


class AuditRepository(Repository):
    def record(
        self,
        *,
        action: str,
        user_id: uuid.UUID | None = None,
        request_id: str | None = None,
        client_hint: str | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> AuditEvent:
        event = AuditEvent(
            user_id=user_id,
            action=action,
            request_id=request_id,
            client_hint=client_hint[:MAX_CLIENT_HINT_LENGTH] if client_hint else None,
            metadata_json=json.dumps(metadata, sort_keys=True) if metadata else None,
        )
        self._session.add(event)
        return event

    def list_for_user(self, user_id: uuid.UUID, limit: int = 100) -> list[AuditEvent]:
        statement = (
            select(AuditEvent)
            .where(AuditEvent.user_id == user_id)
            .order_by(AuditEvent.created_at.desc())
            .limit(max(1, min(limit, 500)))
        )
        return list(self._session.execute(statement).scalars())
