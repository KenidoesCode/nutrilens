"""Registration, login, refresh and logout."""

from __future__ import annotations

from dataclasses import dataclass
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from ..core.config import Settings
from ..core.errors import (
    AuthenticationError,
    EmailAlreadyRegisteredError,
    InvalidCredentialsError,
    ValidationError,
)
from ..core.security import (
    TokenPair,
    TokenType,
    create_token_pair,
    decode_token,
    fingerprint_token_id,
    hash_password,
    validate_password_strength,
    verify_password,
)
from ..models.audit import AuditAction
from ..models.user import User
from ..repositories.audit import AuditRepository
from ..repositories.user import UserRepository

SUPPORTED_LOCALES = frozenset({"en", "te"})


def validate_timezone(timezone: str) -> str:
    """Normalise and verify an IANA zone name.

    Public because profile updates need the same rule as registration, and a
    second implementation would be a second place for them to disagree.
    """
    candidate = (timezone or "UTC").strip()
    try:
        ZoneInfo(candidate)
    except (ZoneInfoNotFoundError, ValueError, KeyError) as exc:
        raise ValidationError(f"Unknown timezone {candidate!r}.") from exc
    return candidate


def validate_locale(locale: str) -> str:
    candidate = (locale or "en").strip().lower()
    if candidate not in SUPPORTED_LOCALES:
        raise ValidationError(
            f"Unsupported locale {candidate!r}. Supported: {sorted(SUPPORTED_LOCALES)}."
        )
    return candidate

# A bcrypt hash of a value nobody can supply. Verifying against it on an
# unknown email keeps the timing of "no such user" close to that of "wrong
# password", so the endpoint does not enumerate registered addresses.
_DUMMY_HASH = hash_password("nutrilens-timing-equaliser-not-a-real-password")


@dataclass(frozen=True, slots=True)
class AuthResult:
    user: User
    tokens: TokenPair


class AuthService:
    def __init__(
        self,
        users: UserRepository,
        audit: AuditRepository,
        settings: Settings,
    ) -> None:
        self._users = users
        self._audit = audit
        self._settings = settings

    def register(
        self,
        *,
        email: str,
        password: str,
        display_name: str | None,
        timezone: str,
        locale: str,
        request_id: str,
        user_agent: str | None = None,
    ) -> AuthResult:
        validate_password_strength(password, self._settings)
        normalized_timezone = validate_timezone(timezone)
        normalized_locale = validate_locale(locale)

        if self._users.email_exists(email):
            raise EmailAlreadyRegisteredError()

        user = self._users.create(
            email=email,
            password_hash=hash_password(password),
            display_name=display_name.strip() if display_name else None,
            timezone=normalized_timezone,
            locale=normalized_locale,
        )
        self._audit.record(
            action=AuditAction.USER_REGISTERED,
            user_id=user.id,
            request_id=request_id,
            client_hint=user_agent,
        )
        return AuthResult(user=user, tokens=self._issue(user, user_agent))

    def login(
        self,
        *,
        email: str,
        password: str,
        request_id: str,
        user_agent: str | None = None,
    ) -> AuthResult:
        user = self._users.get_by_email(email)

        if user is None:
            # Burn comparable time before failing, then fail identically.
            verify_password(password, _DUMMY_HASH)
            self._audit.record(
                action=AuditAction.USER_LOGIN_FAILED,
                request_id=request_id,
                client_hint=user_agent,
                metadata={"reason": "unknown_account"},
            )
            raise InvalidCredentialsError()

        if not verify_password(password, user.password_hash) or not user.is_active:
            self._audit.record(
                action=AuditAction.USER_LOGIN_FAILED,
                user_id=user.id,
                request_id=request_id,
                client_hint=user_agent,
                metadata={"reason": "bad_password_or_inactive"},
            )
            raise InvalidCredentialsError()

        self._users.record_login(user)
        self._audit.record(
            action=AuditAction.USER_LOGIN_SUCCEEDED,
            user_id=user.id,
            request_id=request_id,
            client_hint=user_agent,
        )
        return AuthResult(user=user, tokens=self._issue(user, user_agent))

    def refresh(
        self, *, refresh_token: str, request_id: str, user_agent: str | None = None
    ) -> AuthResult:
        """Exchange a refresh token for a new pair, rotating the old one.

        Rotation is unconditional: the presented token is revoked whether or
        not the caller ever sees the response, so a stolen token is usable at
        most once.
        """
        claims = decode_token(refresh_token, TokenType.REFRESH, self._settings)
        stored = self._users.get_active_refresh_token(fingerprint_token_id(claims.jti))
        if stored is None:
            raise AuthenticationError("The session has expired. Please sign in again.")

        user = self._users.get_by_id(claims.subject)
        if user is None or not user.is_active:
            raise AuthenticationError("The session is no longer valid.")

        self._users.revoke(stored)
        self._audit.record(
            action=AuditAction.TOKEN_REFRESHED,
            user_id=user.id,
            request_id=request_id,
            client_hint=user_agent,
        )
        return AuthResult(user=user, tokens=self._issue(user, user_agent))

    def logout(
        self, *, refresh_token: str, request_id: str, user_agent: str | None = None
    ) -> None:
        """Revoke one session.

        A token that is already invalid is not an error: logging out twice, or
        from a device whose session expired, should simply succeed.
        """
        try:
            claims = decode_token(refresh_token, TokenType.REFRESH, self._settings)
        except AuthenticationError:
            return

        stored = self._users.get_active_refresh_token(fingerprint_token_id(claims.jti))
        if stored is not None:
            self._users.revoke(stored)
            self._audit.record(
                action=AuditAction.USER_LOGGED_OUT,
                user_id=stored.user_id,
                request_id=request_id,
                client_hint=user_agent,
            )

    def logout_everywhere(self, user: User) -> int:
        return self._users.revoke_all_for_user(user.id)

    def authenticate_access_token(self, token: str) -> User:
        claims = decode_token(token, TokenType.ACCESS, self._settings)
        user = self._users.get_by_id(claims.subject)
        if user is None or not user.is_active:
            raise AuthenticationError("The account is not available.")
        return user

    def _issue(self, user: User, user_agent: str | None) -> TokenPair:
        tokens = create_token_pair(user.id, self._settings)
        self._users.add_refresh_token(
            user_id=user.id,
            token_fingerprint=fingerprint_token_id(tokens.refresh_jti),
            expires_at=tokens.refresh_expires_at,
            user_agent=user_agent,
        )
        return tokens
