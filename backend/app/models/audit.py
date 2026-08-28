"""Append-only audit trail for security-relevant events.

Written for authentication, account changes and data export/erasure. Contains
no credentials and no meal content -- only who did what, when, and from where
(coarsely).
"""

from __future__ import annotations

import uuid
from enum import StrEnum

from sqlalchemy import ForeignKey, Index, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from ..core.database import GUID, Base
from .mixins import Timestamped, UUIDPrimaryKey


class AuditAction(StrEnum):
    USER_REGISTERED = "user.registered"
    USER_LOGIN_SUCCEEDED = "user.login.succeeded"
    USER_LOGIN_FAILED = "user.login.failed"
    USER_LOGGED_OUT = "user.logged_out"
    TOKEN_REFRESHED = "token.refreshed"
    MEAL_CREATED = "meal.created"
    MEAL_UPDATED = "meal.updated"
    MEAL_DELETED = "meal.deleted"
    DATA_EXPORTED = "data.exported"
    ACCOUNT_DELETED = "account.deleted"


class AuditEvent(UUIDPrimaryKey, Timestamped, Base):
    __tablename__ = "audit_events"
    __table_args__ = (
        Index("ix_audit_events_user_created", "user_id", "created_at"),
        Index("ix_audit_events_action", "action"),
    )

    # Nullable: a failed login for an unknown email still has to be recorded.
    user_id: Mapped[uuid.UUID | None] = mapped_column(
        GUID(), ForeignKey("users.id", ondelete="SET NULL"), nullable=True
    )
    action: Mapped[str] = mapped_column(String(64), nullable=False)
    request_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    # Truncated at the source; never a full user-agent fingerprint.
    client_hint: Mapped[str | None] = mapped_column(String(120), nullable=True)
    metadata_json: Mapped[str | None] = mapped_column(Text, nullable=True)
