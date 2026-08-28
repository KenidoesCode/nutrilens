"""Authentication endpoints."""

from __future__ import annotations

from fastapi import APIRouter, Depends, status

from ...core.request_context import get_request_id
from ...schemas.auth import (
    LoginRequest,
    LogoutRequest,
    RefreshRequest,
    RegisterRequest,
    TokenResponse,
)
from ...schemas.common import ErrorResponse
from ...services.auth_service import AuthResult, AuthService
from ..dependencies import client_user_agent, enforce_auth_rate_limit, get_auth_service

router = APIRouter(
    prefix="/auth",
    tags=["auth"],
    responses={
        401: {"model": ErrorResponse},
        422: {"model": ErrorResponse},
        429: {"model": ErrorResponse},
    },
)


@router.post(
    "/register",
    response_model=TokenResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Create an account",
    responses={409: {"model": ErrorResponse}},
    dependencies=[Depends(enforce_auth_rate_limit)],
)
def register(
    payload: RegisterRequest,
    auth: AuthService = Depends(get_auth_service),
    user_agent: str | None = Depends(client_user_agent),
) -> TokenResponse:
    result = auth.register(
        email=payload.email,
        password=payload.password,
        display_name=payload.display_name,
        timezone=payload.timezone,
        locale=payload.locale,
        request_id=get_request_id(),
        user_agent=user_agent,
    )
    return _to_token_response(result)


@router.post(
    "/login",
    response_model=TokenResponse,
    summary="Exchange credentials for a token pair",
    dependencies=[Depends(enforce_auth_rate_limit)],
)
def login(
    payload: LoginRequest,
    auth: AuthService = Depends(get_auth_service),
    user_agent: str | None = Depends(client_user_agent),
) -> TokenResponse:
    result = auth.login(
        email=payload.email,
        password=payload.password,
        request_id=get_request_id(),
        user_agent=user_agent,
    )
    return _to_token_response(result)


@router.post(
    "/refresh",
    response_model=TokenResponse,
    summary="Rotate a refresh token for a new pair",
    dependencies=[Depends(enforce_auth_rate_limit)],
)
def refresh(
    payload: RefreshRequest,
    auth: AuthService = Depends(get_auth_service),
    user_agent: str | None = Depends(client_user_agent),
) -> TokenResponse:
    result = auth.refresh(
        refresh_token=payload.refresh_token,
        request_id=get_request_id(),
        user_agent=user_agent,
    )
    return _to_token_response(result)


@router.post(
    "/logout",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="Revoke one refresh session",
)
def logout(
    payload: LogoutRequest,
    auth: AuthService = Depends(get_auth_service),
    user_agent: str | None = Depends(client_user_agent),
) -> None:
    # Deliberately 204 whether or not the token was still valid: telling a
    # caller their token was already dead is information they do not need.
    auth.logout(
        refresh_token=payload.refresh_token,
        request_id=get_request_id(),
        user_agent=user_agent,
    )


def _to_token_response(result: AuthResult) -> TokenResponse:
    tokens = result.tokens
    return TokenResponse(
        access_token=tokens.access_token,
        refresh_token=tokens.refresh_token,
        token_type=tokens.token_type,
        access_expires_at=tokens.access_expires_at,
        refresh_expires_at=tokens.refresh_expires_at,
    )
