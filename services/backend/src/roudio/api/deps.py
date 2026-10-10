"""Shared FastAPI dependencies."""

from dataclasses import dataclass
from typing import Annotated

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.ext.asyncio import AsyncSession

from roudio.auth.crypto import TokenCipher
from roudio.auth.google import DriveCodeExchanger, GoogleVerifier
from roudio.auth.sessions import SessionError, session_from_access_token
from roudio.config import Settings, get_settings
from roudio.db import get_db
from roudio.models import AuthSession
from roudio.plaud import PlaudAuth


@dataclass
class Services:
    """External services, swapped for fakes in tests."""

    google: GoogleVerifier
    drive: DriveCodeExchanger | None
    cipher: TokenCipher | None
    plaud: PlaudAuth


def get_services(request: Request) -> Services:
    services: Services = request.app.state.services
    return services


def error(status_code: int, code: str, message: str) -> HTTPException:
    """Errors carry a stable `code` for the apps and a plain-language `message`."""
    return HTTPException(status_code, detail={"code": code, "message": message})


_bearer = HTTPBearer(auto_error=False)

Db = Annotated[AsyncSession, Depends(get_db)]
AppSettings = Annotated[Settings, Depends(get_settings)]
AppServices = Annotated[Services, Depends(get_services)]


async def current_session(
    db: Db,
    settings: AppSettings,
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(_bearer)],
) -> AuthSession:
    if credentials is None:
        raise error(status.HTTP_401_UNAUTHORIZED, "signed_out", "Sign in to continue.")
    try:
        return await session_from_access_token(db, settings, credentials.credentials)
    except SessionError as exc:
        raise error(status.HTTP_401_UNAUTHORIZED, "signed_out", "Sign in to continue.") from exc


CurrentSession = Annotated[AuthSession, Depends(current_session)]
