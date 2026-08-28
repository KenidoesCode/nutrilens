"""Shared response envelopes."""

from __future__ import annotations

from typing import Generic, TypeVar

from pydantic import BaseModel, ConfigDict, Field

T = TypeVar("T")


class ErrorDetail(BaseModel):
    code: str = Field(description="Stable machine-readable error code.")
    message: str = Field(description="Human-readable message, safe to display.")
    request_id: str = Field(description="Correlation id for support and log lookup.")
    details: dict | None = None


class ErrorResponse(BaseModel):
    model_config = ConfigDict(
        json_schema_extra={
            "example": {
                "error": {
                    "code": "INVALID_IMAGE",
                    "message": "The uploaded image could not be processed.",
                    "request_id": "9f1c2a5e7b0d4c3f",
                }
            }
        }
    )
    error: ErrorDetail


class PageMeta(BaseModel):
    total: int = Field(ge=0)
    limit: int = Field(ge=1)
    offset: int = Field(ge=0)
    has_more: bool


class Page(BaseModel, Generic[T]):
    items: list[T]
    meta: PageMeta
