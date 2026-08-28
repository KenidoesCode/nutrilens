"""User and refresh-token persistence."""

from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import select, update

from ..core.database import utcnow
from ..models.user import RefreshToken, User
from .base import Repository


class UserRepository(Repository):
    def get_by_id(self, user_id: uuid.UUID) -> User | None:
        statement = select(User).where(User.id == user_id, User.is_deleted.is_(False))
        return self._session.execute(statement).scalar_one_or_none()

    def get_by_email(self, email: str) -> User | None:
        statement = select(User).where(
            User.email == _normalize_email(email), User.is_deleted.is_(False)
        )
        return self._session.execute(statement).scalar_one_or_none()

    def email_exists(self, email: str) -> bool:
        return self.get_by_email(email) is not None

    def create(
        self,
        *,
        email: str,
        password_hash: str,
        display_name: str | None,
        timezone: str,
        locale: str,
    ) -> User:
        user = User(
            email=_normalize_email(email),
            password_hash=password_hash,
            display_name=display_name,
            timezone=timezone,
            locale=locale,
        )
        self._session.add(user)
        self.flush()
        return user

    def record_login(self, user: User) -> None:
        user.last_login_at = utcnow()
        self._session.add(user)

    def soft_delete(self, user: User) -> None:
        """Deactivate the account and revoke every session in one step."""
        now = utcnow()
        user.is_deleted = True
        user.deleted_at = now
        user.is_active = False
        self._session.add(user)
        self.revoke_all_for_user(user.id)

    # --- refresh tokens --------------------------------------------------

    def add_refresh_token(
        self,
        *,
        user_id: uuid.UUID,
        token_fingerprint: str,
        expires_at: datetime,
        user_agent: str | None,
    ) -> RefreshToken:
        token = RefreshToken(
            user_id=user_id,
            token_fingerprint=token_fingerprint,
            expires_at=expires_at,
            user_agent=user_agent[:255] if user_agent else None,
        )
        self._session.add(token)
        self.flush()
        return token

    def get_active_refresh_token(self, token_fingerprint: str) -> RefreshToken | None:
        statement = select(RefreshToken).where(
            RefreshToken.token_fingerprint == token_fingerprint,
            RefreshToken.revoked_at.is_(None),
            RefreshToken.expires_at > utcnow(),
        )
        return self._session.execute(statement).scalar_one_or_none()

    def revoke(self, token: RefreshToken) -> None:
        token.revoked_at = utcnow()
        self._session.add(token)

    def revoke_all_for_user(self, user_id: uuid.UUID) -> int:
        statement = (
            update(RefreshToken)
            .where(RefreshToken.user_id == user_id, RefreshToken.revoked_at.is_(None))
            .values(revoked_at=utcnow())
        )
        return int(self._session.execute(statement).rowcount or 0)


def _normalize_email(email: str) -> str:
    """Lower-case and trim.

    The local part is technically case-sensitive, but no real provider treats
    it that way, and letting ``A@x.com`` and ``a@x.com`` both register would
    guarantee duplicate accounts and confused logins.
    """
    return email.strip().lower()
