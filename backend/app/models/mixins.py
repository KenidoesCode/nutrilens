"""Column mixins shared across tables."""

from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import Boolean, func
from sqlalchemy.orm import Mapped, mapped_column

from ..core.database import GUID, UTCDateTime


class UUIDPrimaryKey:
    """UUID primary keys, generated client-side.

    Offline-first requires it: the phone must be able to mint an id for a meal
    it records with no network, and that id has to survive the later sync
    unchanged so the record is never duplicated.
    """

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid.uuid4)


class Timestamped:
    created_at: Mapped[datetime] = mapped_column(
        UTCDateTime(), nullable=False, server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        UTCDateTime(), nullable=False, server_default=func.now(), onupdate=func.now()
    )


class SoftDeletable:
    """Soft deletion.

    Meal history is the product. A delete that cannot be undone would make an
    accidental tap destructive, and it would break sync: a client that deleted
    a row while offline still has to reconcile with the server. Hard deletion
    is reserved for account erasure, which is handled explicitly.
    """

    deleted_at: Mapped[datetime | None] = mapped_column(UTCDateTime(), nullable=True)
    is_deleted: Mapped[bool] = mapped_column(
        Boolean, nullable=False, default=False, server_default="0", index=True
    )
