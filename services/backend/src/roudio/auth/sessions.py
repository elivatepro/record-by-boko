"""Roudio's own sessions: a short-lived access token plus a rotating refresh token.

The access token is a signed JWT naming the session. The refresh token is random; only its
SHA-256 is stored. Each refresh swaps it for a new one. If an old one is presented again, it
was copied, so the whole session is revoked.
"""

import hashlib
import secrets
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from uuid import UUID

import jwt
from sqlalchemy import or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from roudio.config import Settings
from roudio.models import AuthSession, Owner

ALGORITHM = "HS256"


class SessionError(Exception):
    """The access or refresh token is invalid, expired or revoked."""


@dataclass(frozen=True)
class TokenPair:
    access_token: str
    access_expires_at: datetime
    refresh_token: str
    refresh_expires_at: datetime


def _hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def _access_token(settings: Settings, session: AuthSession, now: datetime) -> tuple[str, datetime]:
    expires = now + timedelta(minutes=settings.access_token_minutes)
    claims = {
        "sub": str(session.owner_id),
        "sid": str(session.id),
        "typ": "access",
        "iat": now,
        "exp": expires,
    }
    token = jwt.encode(claims, settings.session_secret.get_secret_value(), algorithm=ALGORITHM)
    return token, expires


async def create_session(
    db: AsyncSession, settings: Settings, owner: Owner, client: str
) -> TokenPair:
    now = datetime.now(UTC)
    refresh = secrets.token_urlsafe(32)
    session = AuthSession(
        owner_id=owner.id,
        client=client,
        refresh_hash=_hash(refresh),
        expires_at=now + timedelta(days=settings.refresh_token_days),
    )
    db.add(session)
    await db.flush()
    access, access_expires = _access_token(settings, session, now)
    await db.commit()
    return TokenPair(access, access_expires, refresh, session.expires_at)


async def refresh_session(db: AsyncSession, settings: Settings, refresh: str) -> TokenPair:
    now = datetime.now(UTC)
    presented = _hash(refresh)
    session = (
        await db.execute(
            select(AuthSession)
            .where(
                or_(
                    AuthSession.refresh_hash == presented,
                    AuthSession.previous_refresh_hash == presented,
                )
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if session is None or session.revoked_at is not None or session.expires_at <= now:
        raise SessionError("Refresh token is not valid")
    if session.previous_refresh_hash == presented:
        session.revoked_at = now
        await db.commit()
        raise SessionError("Refresh token was already used; session revoked")
    new_refresh = secrets.token_urlsafe(32)
    session.previous_refresh_hash = session.refresh_hash
    session.refresh_hash = _hash(new_refresh)
    session.last_used_at = now
    access, access_expires = _access_token(settings, session, now)
    await db.commit()
    return TokenPair(access, access_expires, new_refresh, session.expires_at)


async def session_from_access_token(
    db: AsyncSession, settings: Settings, token: str
) -> AuthSession:
    try:
        claims = jwt.decode(
            token,
            settings.session_secret.get_secret_value(),
            algorithms=[ALGORITHM],
            options={"require": ["exp", "sid", "sub", "typ"]},
        )
    except jwt.PyJWTError as exc:
        raise SessionError("Access token is not valid") from exc
    if claims["typ"] != "access":
        raise SessionError("Not an access token")
    session = await db.get(AuthSession, UUID(claims["sid"]))
    if session is None or session.revoked_at is not None:
        raise SessionError("Session has ended")
    return session


async def revoke_session(db: AsyncSession, session: AuthSession) -> None:
    session.revoked_at = datetime.now(UTC)
    await db.commit()
