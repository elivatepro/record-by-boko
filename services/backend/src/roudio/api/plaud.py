"""Gives the phone the Plaud SDK user token. The Plaud secret stays here (decision 1)."""

import logging
from datetime import UTC, datetime

from fastapi import APIRouter, status
from pydantic import BaseModel, Field

from roudio.api.deps import AppServices, CurrentSession, error
from roudio.plaud import PlaudError

log = logging.getLogger(__name__)
router = APIRouter(prefix="/plaud", tags=["plaud"])


class PlaudToken(BaseModel):
    access_token: str = Field(description="User token for `PlaudDeviceAgent.initSDK`")
    expires_at: datetime
    domain: str = Field(description="Host to pass to the SDK, e.g. platform-us.plaud.ai")


@router.post("/token", operation_id="getPlaudToken")
async def plaud_token(_: CurrentSession, services: AppServices) -> PlaudToken:
    try:
        token = await services.plaud.user_token()
    except PlaudError as exc:
        log.error("Plaud token exchange failed: %s", exc)
        raise error(
            status.HTTP_502_BAD_GATEWAY,
            "plaud_unavailable",
            "Couldn't reach Plaud to link the recorder. Try again in a moment.",
        ) from exc
    return PlaudToken(
        access_token=token.access_token,
        expires_at=datetime.fromtimestamp(token.expires_at, UTC),
        domain=token.domain,
    )
