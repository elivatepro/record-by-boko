"""Sign-in with Google, limited to the owner's account (decision 10)."""

import logging
from datetime import datetime
from typing import Literal

from fastapi import APIRouter, Response, status
from pydantic import BaseModel, Field, model_validator
from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert

from roudio.api.deps import AppServices, AppSettings, CurrentSession, Db, error
from roudio.auth.google import DRIVE_FILE_SCOPE, DriveGrantResult, GoogleAuthError, GoogleIdentity
from roudio.auth.sessions import SessionError, TokenPair, create_session, refresh_session
from roudio.auth.sessions import revoke_session as end_session
from roudio.models import DriveGrant, Owner

log = logging.getLogger(__name__)
router = APIRouter(prefix="/auth", tags=["auth"])

# Where each client's authorization code was issued. Android's server auth code has no
# redirect; Google Identity Services' popup flow on the web uses "postmessage".
REDIRECT_URI = {"android": "", "web": "postmessage"}


class GoogleSignInRequest(BaseModel):
    """Android sends `id_token` (and `drive_auth_code` when Drive is granted). The web sends
    only `drive_auth_code` from one Google popup; its ID token comes back from the exchange."""

    client: Literal["android", "web"]
    id_token: str | None = Field(default=None, description="Google ID token (Credential Manager)")
    drive_auth_code: str | None = Field(
        default=None,
        description=f"One-time authorization code for `openid email {DRIVE_FILE_SCOPE}`",
    )

    @model_validator(mode="after")
    def _needs_a_credential(self) -> "GoogleSignInRequest":
        if not self.id_token and not self.drive_auth_code:
            raise ValueError("Send id_token, drive_auth_code, or both")
        return self


class RefreshRequest(BaseModel):
    refresh_token: str


class SessionTokens(BaseModel):
    access_token: str
    access_expires_at: datetime
    refresh_token: str
    refresh_expires_at: datetime
    token_type: Literal["bearer"] = "bearer"  # noqa: S105

    @classmethod
    def of(cls, pair: TokenPair) -> "SessionTokens":
        return cls(
            access_token=pair.access_token,
            access_expires_at=pair.access_expires_at,
            refresh_token=pair.refresh_token,
            refresh_expires_at=pair.refresh_expires_at,
        )


class SignInResult(SessionTokens):
    drive_connected: bool
    drive_error: str | None = Field(
        default=None, description="Why the Drive grant wasn't saved, if a code was sent"
    )


class Me(BaseModel):
    email: str
    drive_connected: bool


@router.post("/google", operation_id="signInWithGoogle")
async def sign_in_with_google(
    body: GoogleSignInRequest, db: Db, settings: AppSettings, services: AppServices
) -> SignInResult:
    grant: DriveGrantResult | None = None
    drive_error: str | None = None
    try:
        if body.id_token:
            identity = await services.google.verify(body.id_token)
        else:
            grant, drive_error = await _exchange(services, body)
            if grant is None:
                raise GoogleAuthError(drive_error or "code exchange failed")
            identity = await services.google.verify(grant.id_token)
    except GoogleAuthError as exc:
        log.info("Google sign-in rejected: %s", exc)
        raise error(
            status.HTTP_401_UNAUTHORIZED,
            "google_token_invalid",
            "Google sign-in didn't work. Try again.",
        ) from exc

    owner = await _owner_for(db, settings.owner_email, identity)

    if body.drive_auth_code:
        if grant is None and drive_error is None:
            grant, drive_error = await _exchange(services, body)
        if grant is not None:
            drive_error = await _save_drive_grant(db, services, owner, identity.sub, grant)
    drive_connected = (await db.get(DriveGrant, owner.id)) is not None

    pair = await create_session(db, settings, owner, body.client)
    return SignInResult(
        **SessionTokens.of(pair).model_dump(),
        drive_connected=drive_connected,
        drive_error=drive_error,
    )


async def _owner_for(db: Db, owner_email: str, identity: GoogleIdentity) -> Owner:
    """The owner row for this Google account, or 403 for anyone else."""
    # The refused address goes back to the person who just signed in with it, nowhere else.
    refused = error(
        status.HTTP_403_FORBIDDEN,
        "not_owner",
        f"{identity.email} isn't the owner. Pick the account you use on your phone.",
        email=identity.email,
    )
    if not identity.email_verified or identity.email != owner_email:
        log.warning("Refused sign-in from a Google account that isn't the owner's")
        raise refused
    owner = (await db.execute(select(Owner))).scalar_one_or_none()
    if owner is None:
        owner = Owner(email=identity.email, google_sub=identity.sub)
        db.add(owner)
        await db.flush()
    elif owner.google_sub != identity.sub:
        # Same address, different Google account (e.g. the address was recycled).
        log.warning("Refused sign-in: email matches the owner but the Google account doesn't")
        raise refused
    return owner


async def _exchange(
    services: AppServices, body: GoogleSignInRequest
) -> tuple[DriveGrantResult | None, str | None]:
    """Swap the authorization code. Returns the grant, or a plain-language error."""
    if services.drive is None or services.cipher is None:
        return None, "Drive backup isn't set up on the server yet."
    try:
        grant = await services.drive.exchange(body.drive_auth_code or "", REDIRECT_URI[body.client])
    except GoogleAuthError as exc:
        log.warning("Drive grant exchange failed: %s", exc)
        return None, "Google didn't grant Drive access. Try connecting Drive again."
    return grant, None


async def _save_drive_grant(
    db: Db, services: AppServices, owner: Owner, sub: str, grant: DriveGrantResult
) -> str | None:
    """Store the offline Drive grant. Returns a plain-language error instead of raising."""
    assert services.cipher is not None  # checked in _exchange
    if grant.sub != sub:
        return "The Drive permission came from a different Google account."
    if DRIVE_FILE_SCOPE not in grant.scopes:
        return "Drive access wasn't allowed. Roudio needs it to back up recordings."
    if grant.refresh_token is None:
        # Google only sends a refresh token on first consent; keep the one already stored.
        if await db.get(DriveGrant, owner.id) is None:
            return "Google didn't grant offline Drive access. Try connecting Drive again."
        return None
    values = {
        "encrypted_refresh_token": services.cipher.encrypt(grant.refresh_token),
        "scopes": " ".join(grant.scopes),
    }
    await db.execute(
        insert(DriveGrant)
        .values(owner_id=owner.id, **values)
        .on_conflict_do_update(index_elements=[DriveGrant.owner_id], set_=values)
    )
    return None


@router.post("/refresh", operation_id="refreshSession")
async def refresh(body: RefreshRequest, db: Db, settings: AppSettings) -> SessionTokens:
    try:
        pair = await refresh_session(db, settings, body.refresh_token)
    except SessionError as exc:
        raise error(status.HTTP_401_UNAUTHORIZED, "signed_out", "Sign in to continue.") from exc
    return SessionTokens.of(pair)


@router.post("/sign-out", operation_id="signOut", status_code=status.HTTP_204_NO_CONTENT)
async def sign_out(session: CurrentSession, db: Db) -> Response:
    await end_session(db, session)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("/me", operation_id="getMe")
async def me(session: CurrentSession, db: Db) -> Me:
    owner = await db.get(Owner, session.owner_id)
    if owner is None:
        raise error(status.HTTP_401_UNAUTHORIZED, "signed_out", "Sign in to continue.")
    return Me(email=owner.email, drive_connected=(await db.get(DriveGrant, owner.id)) is not None)
