"""Server-side view of client synchronisation."""

from __future__ import annotations

import uuid
from datetime import datetime
from enum import StrEnum

from sqlalchemy import ForeignKey, Index, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from ..core.database import GUID, Base, UTCDateTime
from .mixins import Timestamped, UUIDPrimaryKey


class SyncState(StrEnum):
    """Lifecycle of one queued client operation. Mirrored on the device."""

    PENDING = "pending"
    SYNCING = "syncing"
    SYNCED = "synced"
    FAILED = "failed"
    RETRYING = "retrying"


class SyncOperation(UUIDPrimaryKey, Timestamped, Base):
    """A record of one client operation the server has accepted.

    The unique ``(user_id, idempotency_key)`` pair is what makes replay safe:
    a client that never saw the response can resend the same operation and the
    server recognises it instead of duplicating work.
    """

    __tablename__ = "sync_queue"
    __table_args__ = (
        UniqueConstraint("user_id", "idempotency_key", name="uq_sync_queue_user_idempotency"),
        Index("ix_sync_queue_user_state", "user_id", "state"),
    )

    user_id: Mapped[uuid.UUID] = mapped_column(
        GUID(), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    idempotency_key: Mapped[str] = mapped_column(String(80), nullable=False)
    operation: Mapped[str] = mapped_column(String(48), nullable=False)
    entity_type: Mapped[str] = mapped_column(String(48), nullable=False)
    entity_id: Mapped[uuid.UUID | None] = mapped_column(GUID(), nullable=True)

    state: Mapped[SyncState] = mapped_column(
        String(16), nullable=False, default=SyncState.SYNCED
    )
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    last_error: Mapped[str | None] = mapped_column(Text, nullable=True)
    completed_at: Mapped[datetime | None] = mapped_column(UTCDateTime(), nullable=True)
