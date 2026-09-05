from __future__ import annotations

import hmac

from fastapi import Header, HTTPException, status

from .config import settings


async def verify_internal_token(
    x_lyreo_internal_token: str | None = Header(default=None),
) -> None:
    """Authenticate only the private Core -> AI service boundary.

    Learner/admin JWTs terminate at Core. Provider credentials use a separate header and are never
    accepted as a substitute for this service token.
    """

    expected = settings().ai_service_internal_token
    if not x_lyreo_internal_token or not hmac.compare_digest(
        x_lyreo_internal_token,
        expected,
    ):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid internal token",
        )
