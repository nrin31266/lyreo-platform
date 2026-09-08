from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


class ExecuteRequest(BaseModel):
    """Stable internal capability request sent by Lyreo Core.

    `prompt` is business-owned text constructed by Java. `input` is the business payload and
    `options` contains provider-neutral execution hints plus a small provider-options escape hatch.
    The FastAPI service must not grow lesson/curriculum/gamification orchestration around this DTO.
    """

    invocation_id: str
    provider: str
    model: str
    prompt: str | None = None
    input: dict[str, Any] = Field(default_factory=dict)
    options: dict[str, Any] = Field(default_factory=dict)


class ExecuteResponse(BaseModel):
    output: Any
    usage: dict[str, int | float] = Field(default_factory=dict)
    metadata: dict[str, Any] = Field(default_factory=dict)


class HealthResponse(BaseModel):
    status: str
    runtime_mode: str
