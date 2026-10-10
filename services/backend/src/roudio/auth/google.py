"""Google sign-in: ID token checks and the Drive authorization-code exchange."""

from dataclasses import dataclass
from typing import Protocol

import httpx
import jwt
from fastapi.concurrency import run_in_threadpool
from google.auth.transport import requests as google_requests
from google.oauth2 import id_token as google_id_token

DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
TOKEN_URL = "https://oauth2.googleapis.com/token"  # noqa: S105 (a URL, not a secret)


class GoogleAuthError(Exception):
    """The Google token or code was invalid, expired or for the wrong app."""


@dataclass(frozen=True)
class GoogleIdentity:
    sub: str
    email: str
    email_verified: bool


@dataclass(frozen=True)
class DriveGrantResult:
    refresh_token: str | None  # only sent on first consent
    scopes: list[str]
    sub: str
    id_token: str


class GoogleVerifier(Protocol):
    async def verify(self, id_token: str) -> GoogleIdentity: ...


class DriveCodeExchanger(Protocol):
    async def exchange(self, code: str, redirect_uri: str) -> DriveGrantResult: ...


class HttpGoogleVerifier:
    """Checks the signature, expiry, issuer and audience of a Google ID token."""

    def __init__(self, client_ids: list[str]) -> None:
        self._client_ids = client_ids
        self._request = google_requests.Request()

    async def verify(self, id_token: str) -> GoogleIdentity:
        if not self._client_ids:
            raise GoogleAuthError("No Google client IDs are configured")
        try:
            claims = await run_in_threadpool(
                google_id_token.verify_oauth2_token, id_token, self._request, self._client_ids
            )
        except ValueError as exc:
            raise GoogleAuthError(str(exc)) from exc
        return GoogleIdentity(
            sub=str(claims["sub"]),
            email=str(claims.get("email", "")).lower(),
            email_verified=bool(claims.get("email_verified", False)),
        )


class HttpDriveCodeExchanger:
    """Swaps a one-time authorization code for an offline refresh token."""

    def __init__(self, client_id: str, client_secret: str) -> None:
        self._client_id = client_id
        self._client_secret = client_secret

    async def exchange(self, code: str, redirect_uri: str) -> DriveGrantResult:
        async with httpx.AsyncClient(timeout=20) as client:
            response = await client.post(
                TOKEN_URL,
                data={
                    "code": code,
                    "client_id": self._client_id,
                    "client_secret": self._client_secret,
                    "redirect_uri": redirect_uri,
                    "grant_type": "authorization_code",
                },
            )
        if not response.is_success:
            raise GoogleAuthError(f"Google code exchange failed: HTTP {response.status_code}")
        body = response.json()
        if "id_token" not in body:
            raise GoogleAuthError("Google returned no ID token; request the openid scope")
        # Only used to match the grant to the signed-in account. Sign-in itself always
        # verifies an ID token's signature through GoogleVerifier.
        claims = jwt.decode(body["id_token"], options={"verify_signature": False})
        return DriveGrantResult(
            refresh_token=body.get("refresh_token"),
            scopes=str(body.get("scope", "")).split(),
            sub=str(claims["sub"]),
            id_token=body["id_token"],
        )
