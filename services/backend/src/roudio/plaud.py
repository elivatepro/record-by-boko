"""Plaud user-token exchange (decision 1).

The Android SDK needs a short-lived *user* token. Getting one takes two calls to the Plaud
developer API (https://docs.plaud.ai/plaud-embedded/auth-api-overview):

1. `POST /oauth/partner/access-token` with HTTP Basic `client_id:secret` → a partner token.
2. `POST /open/partner/users/access-token` with that partner token and our stable `user_id`
   → the user token the SDK is initialised with.

The client secret never leaves the backend. Device binding is tied to `user_id`, so it must
never change once the recorder is linked.
"""

import asyncio
import time
from dataclasses import dataclass
from typing import Any, Protocol

import httpx


class PlaudError(Exception):
    """Plaud refused or failed the token exchange."""


@dataclass(frozen=True)
class PlaudUserToken:
    access_token: str
    expires_at: float  # Unix seconds
    domain: str  # host the SDK must talk to, e.g. "platform-us.plaud.ai"


class PlaudAuth(Protocol):
    async def user_token(self) -> PlaudUserToken: ...


# Fetch a fresh token when less than this much lifetime is left.
REFRESH_MARGIN_SECONDS = 600


class HttpPlaudAuth:
    def __init__(
        self,
        *,
        client_id: str,
        client_secret: str,
        user_id: str,
        api_base: str,
        user_token_seconds: int = 86400,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        if not 6 <= len(user_id) <= 120:
            raise ValueError("Plaud user_id must be 6 to 120 characters")
        self._auth = httpx.BasicAuth(client_id, client_secret)
        self._user_id = user_id
        self._api_base = api_base.rstrip("/")
        self._user_token_seconds = user_token_seconds
        self._transport = transport
        self._partner: tuple[str, float] | None = None
        self._user: PlaudUserToken | None = None
        self._lock = asyncio.Lock()

    @property
    def domain(self) -> str:
        return httpx.URL(self._api_base).host

    async def user_token(self) -> PlaudUserToken:
        async with self._lock:
            if self._user and self._user.expires_at - time.time() > REFRESH_MARGIN_SECONDS:
                return self._user
            async with httpx.AsyncClient(
                base_url=self._api_base, timeout=20, transport=self._transport
            ) as client:
                partner = await self._partner_token(client)
                self._user = await self._mint_user_token(client, partner)
            return self._user

    async def _partner_token(self, client: httpx.AsyncClient) -> str:
        if self._partner and self._partner[1] - time.time() > REFRESH_MARGIN_SECONDS:
            return self._partner[0]
        response = await client.post(
            "/oauth/partner/access-token",
            auth=self._auth,
            data={"grant_type": "client_credentials"},
        )
        body = _json_or_raise(response, "partner token")
        self._partner = (body["access_token"], time.time() + float(body.get("expires_in", 3600)))
        return self._partner[0]

    async def _mint_user_token(self, client: httpx.AsyncClient, partner: str) -> PlaudUserToken:
        response = await client.post(
            "/open/partner/users/access-token",
            headers={"Authorization": f"Bearer {partner}"},
            json={"user_id": self._user_id, "expires_in": self._user_token_seconds},
        )
        if response.status_code == 401:
            # The partner token was revoked early; forget it so the next call fetches a new one.
            self._partner = None
        body = _json_or_raise(response, "user token")
        return PlaudUserToken(
            access_token=body["access_token"],
            expires_at=time.time() + float(body.get("expires_in", self._user_token_seconds)),
            domain=self.domain,
        )


def _json_or_raise(response: httpx.Response, what: str) -> dict[str, Any]:
    if response.is_success:
        try:
            body = response.json()
        except ValueError:
            body = None
        if isinstance(body, dict) and "access_token" in body:
            return body
        raise PlaudError(f"Plaud {what}: unexpected response")
    # Never include the response body: it may echo credentials.
    raise PlaudError(f"Plaud {what}: HTTP {response.status_code}")


class UnconfiguredPlaudAuth:
    """Deployed without Plaud credentials: every request fails with a clear reason."""

    async def user_token(self) -> PlaudUserToken:
        raise PlaudError("PLAUD_CLIENT_ID / PLAUD_CLIENT_SECRET aren't set")


class FakePlaudAuth:
    """Used in tests and in development when no Plaud credentials are set."""

    def __init__(self, token: str = "fake-plaud-user-token") -> None:  # noqa: S107
        self.token = token
        self.calls = 0

    async def user_token(self) -> PlaudUserToken:
        self.calls += 1
        return PlaudUserToken(self.token, time.time() + 86400, "platform-us.plaud.ai")
