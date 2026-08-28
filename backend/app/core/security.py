"""Password hashing and JWT issuing/verification.

Access tokens are short-lived and stateless. Refresh tokens are long-lived but
are *not* trusted on their own: each carries an opaque ``jti`` that is stored
server-side, so a refresh token can be revoked (logout, password change,
suspected theft) even though the JWT itself remains cryptographically valid.
"""

from __future__ import annotations

import hashlib
import secrets
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from enum import StrEnum

from jose import JWTError, jwt
from passlib.context import CryptContext

from .config import Settings
from .errors import TokenError, WeakPasswordError

# bcrypt truncates silently at 72 bytes, so anything longer is rejected up
# front rather than quietly having its tail ignored.
MAX_PASSWORD_BYTES = 72

_pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")


class TokenType(StrEnum):
    ACCESS = "access"
    REFRESH = "refresh"


@dataclass(frozen=True, slots=True)
class TokenClaims:
    subject: uuid.UUID
    token_type: TokenType
    jti: str
    expires_at: datetime


@dataclass(frozen=True, slots=True)
class TokenPair:
    access_token: str
    refresh_token: str
    refresh_jti: str
    access_expires_at: datetime
    refresh_expires_at: datetime
    token_type: str = "bearer"


def validate_password_strength(password: str, settings: Settings) -> None:
    """Reject passwords that are trivially weak or unusable by the hasher."""
    if len(password) < settings.password_min_length:
        raise WeakPasswordError(
            f"The password must be at least {settings.password_min_length} characters."
        )
    if len(password.encode("utf-8")) > MAX_PASSWORD_BYTES:
        raise WeakPasswordError(
            f"The password must not exceed {MAX_PASSWORD_BYTES} bytes."
        )
    if password.lower() in _COMMON_PASSWORDS:
        raise WeakPasswordError("That password is too common. Please choose another.")
    if len(set(password)) < 4:
        raise WeakPasswordError("The password must use at least four distinct characters.")
    if password.isdigit():
        # An all-digit password has ~3.3 bits per character and dominates
        # breach corpora; length alone does not rescue it.
        raise WeakPasswordError("The password must not consist only of digits.")


_COMMON_PASSWORDS = frozenset(
    {
        "password",
        "password1",
        "password123",
        "12345678",
        "123456789",
        "1234567890",
        "qwertyuiop",
        "letmein123",
        "iloveyou1",
        "administrator",
    }
)


def hash_password(password: str) -> str:
    return _pwd_context.hash(password)


def verify_password(password: str, password_hash: str) -> bool:
    """Constant-time-ish verification that never raises on a malformed hash."""
    try:
        return _pwd_context.verify(password, password_hash)
    except ValueError:
        return False


def _encode(
    subject: uuid.UUID,
    token_type: TokenType,
    ttl: timedelta,
    settings: Settings,
    jti: str | None = None,
) -> tuple[str, str, datetime]:
    now = datetime.now(UTC)
    expires_at = now + ttl
    token_id = jti or secrets.token_urlsafe(24)
    payload = {
        "sub": str(subject),
        "typ": token_type.value,
        "jti": token_id,
        "iat": int(now.timestamp()),
        "nbf": int(now.timestamp()),
        "exp": int(expires_at.timestamp()),
    }
    encoded = jwt.encode(payload, settings.jwt_secret, algorithm=settings.jwt_algorithm)
    return encoded, token_id, expires_at


def create_token_pair(subject: uuid.UUID, settings: Settings) -> TokenPair:
    access, _, access_expires = _encode(
        subject,
        TokenType.ACCESS,
        timedelta(minutes=settings.access_token_ttl_minutes),
        settings,
    )
    refresh, refresh_jti, refresh_expires = _encode(
        subject,
        TokenType.REFRESH,
        timedelta(days=settings.refresh_token_ttl_days),
        settings,
    )
    return TokenPair(
        access_token=access,
        refresh_token=refresh,
        refresh_jti=refresh_jti,
        access_expires_at=access_expires,
        refresh_expires_at=refresh_expires,
    )


def decode_token(token: str, expected_type: TokenType, settings: Settings) -> TokenClaims:
    """Decode and validate a token, or raise :class:`TokenError`.

    The expected type is checked explicitly: a refresh token must never be
    accepted where an access token is required.
    """
    try:
        payload = jwt.decode(token, settings.jwt_secret, algorithms=[settings.jwt_algorithm])
    except JWTError as exc:
        raise TokenError("The authentication token is invalid or has expired.") from exc

    token_type = payload.get("typ")
    if token_type != expected_type.value:
        raise TokenError("The authentication token is not valid for this operation.")

    subject_raw = payload.get("sub")
    jti = payload.get("jti")
    expires = payload.get("exp")
    if not subject_raw or not jti or not expires:
        raise TokenError("The authentication token is malformed.")

    try:
        subject = uuid.UUID(str(subject_raw))
    except ValueError as exc:
        raise TokenError("The authentication token is malformed.") from exc

    return TokenClaims(
        subject=subject,
        token_type=TokenType(token_type),
        jti=str(jti),
        expires_at=datetime.fromtimestamp(int(expires), tz=UTC),
    )


def fingerprint_token_id(jti: str) -> str:
    """Store a hash of the refresh token id, never the id itself.

    A database dump then does not hand an attacker usable session identifiers.
    """
    return hashlib.sha256(jti.encode("utf-8")).hexdigest()
