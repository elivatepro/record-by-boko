import json

import httpx
import pytest
from httpx import AsyncClient

from roudio.api.deps import Services
from roudio.plaud import HttpPlaudAuth, PlaudError
from tests.conftest import sign_in

BASE = "https://platform-us.plaud.ai/developer/api"


async def test_plaud_token_needs_a_session(client: AsyncClient) -> None:
    assert (await client.post("/plaud/token")).status_code == 401


async def test_plaud_token_for_signed_in_owner(client: AsyncClient) -> None:
    tokens = await sign_in(client)
    response = await client.post(
        "/plaud/token", headers={"Authorization": f"Bearer {tokens['access_token']}"}
    )
    assert response.status_code == 200
    body = response.json()
    assert body["access_token"] == "fake-plaud-user-token"
    assert body["domain"] == "platform-us.plaud.ai"


async def test_plaud_failure_is_a_502_without_details(
    client: AsyncClient, services: Services
) -> None:
    class Broken:
        async def user_token(self) -> object:
            raise PlaudError("Plaud partner token: HTTP 401")

    services.plaud = Broken()  # type: ignore[assignment]
    tokens = await sign_in(client)
    response = await client.post(
        "/plaud/token", headers={"Authorization": f"Bearer {tokens['access_token']}"}
    )
    assert response.status_code == 502
    assert response.json()["detail"]["code"] == "plaud_unavailable"


def _plaud_transport(calls: list[httpx.Request], *, user_status: int = 200) -> httpx.MockTransport:
    def handle(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        if request.url.path.endswith("/oauth/partner/access-token"):
            return httpx.Response(
                200, json={"access_token": "partner-1", "token_type": "bearer", "expires_in": 3600}
            )
        if request.url.path.endswith("/open/partner/users/access-token"):
            if user_status != 200:
                return httpx.Response(user_status, json={"secret": "do-not-log"})
            return httpx.Response(200, json={"access_token": "user-jwt", "expires_in": 86400})
        return httpx.Response(404)

    return httpx.MockTransport(handle)


async def test_http_exchange_follows_plaud_docs_and_caches() -> None:
    calls: list[httpx.Request] = []
    auth = HttpPlaudAuth(
        client_id="cid",
        client_secret="csecret",
        user_id="roudio-owner",
        api_base=BASE,
        transport=_plaud_transport(calls),
    )
    token = await auth.user_token()
    assert token.access_token == "user-jwt"
    assert token.domain == "platform-us.plaud.ai"

    partner, user = calls
    assert partner.url == f"{BASE}/oauth/partner/access-token"
    assert partner.headers["Authorization"].startswith("Basic ")
    assert partner.headers["Content-Type"] == "application/x-www-form-urlencoded"
    assert user.url == f"{BASE}/open/partner/users/access-token"
    assert user.headers["Authorization"] == "Bearer partner-1"
    assert json.loads(user.content) == {"user_id": "roudio-owner", "expires_in": 86400}

    # A second call within the token's life doesn't hit Plaud again.
    await auth.user_token()
    assert len(calls) == 2


async def test_http_exchange_errors_hide_the_response_body() -> None:
    auth = HttpPlaudAuth(
        client_id="cid",
        client_secret="csecret",
        user_id="roudio-owner",
        api_base=BASE,
        transport=_plaud_transport([], user_status=401),
    )
    with pytest.raises(PlaudError) as caught:
        await auth.user_token()
    assert "401" in str(caught.value)
    assert "do-not-log" not in str(caught.value)


def test_user_id_length_is_checked() -> None:
    with pytest.raises(ValueError):
        HttpPlaudAuth(client_id="a", client_secret="b", user_id="me", api_base=BASE)
