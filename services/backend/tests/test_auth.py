from dataclasses import replace

from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from roudio.api.deps import Services
from roudio.auth.google import GoogleIdentity
from roudio.models import DriveGrant, Owner
from tests.conftest import FakeDrive, FakeGoogle, sign_in


async def test_owner_can_sign_in_and_use_the_session(client: AsyncClient) -> None:
    tokens = await sign_in(client)
    assert tokens["token_type"] == "bearer"
    me = await client.get("/auth/me", headers={"Authorization": f"Bearer {tokens['access_token']}"})
    assert me.status_code == 200
    assert me.json() == {"email": "owner@example.com", "drive_connected": False}


async def test_other_google_account_is_refused(client: AsyncClient, services: Services) -> None:
    assert isinstance(services.google, FakeGoogle)
    services.google.identities["stranger"] = GoogleIdentity("sub-2", "someone@gmail.com", True)
    response = await client.post("/auth/google", json={"id_token": "stranger", "client": "web"})
    assert response.status_code == 403
    assert response.json()["detail"]["code"] == "not_owner"
    assert response.json()["detail"]["email"] == "someone@gmail.com"


async def test_unverified_owner_email_is_refused(client: AsyncClient, services: Services) -> None:
    assert isinstance(services.google, FakeGoogle)
    services.google.identities["unverified"] = GoogleIdentity(
        "google-sub-owner", "owner@example.com", False
    )
    response = await client.post("/auth/google", json={"id_token": "unverified", "client": "web"})
    assert response.status_code == 403


async def test_owner_email_on_a_different_google_account_is_refused(
    client: AsyncClient, services: Services
) -> None:
    await sign_in(client)  # pins the owner's Google account
    assert isinstance(services.google, FakeGoogle)
    services.google.identities["recycled"] = GoogleIdentity(
        "some-other-sub", "owner@example.com", True
    )
    response = await client.post("/auth/google", json={"id_token": "recycled", "client": "web"})
    assert response.status_code == 403


async def test_invalid_google_token_is_rejected(client: AsyncClient) -> None:
    response = await client.post("/auth/google", json={"id_token": "forged", "client": "web"})
    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "google_token_invalid"


async def test_requests_without_a_session_are_refused(client: AsyncClient) -> None:
    assert (await client.get("/auth/me")).status_code == 401
    bad = await client.get("/auth/me", headers={"Authorization": "Bearer not-a-jwt"})
    assert bad.status_code == 401


async def test_refresh_rotates_and_reuse_revokes_the_session(client: AsyncClient) -> None:
    first = await sign_in(client)
    refreshed = await client.post("/auth/refresh", json={"refresh_token": first["refresh_token"]})
    assert refreshed.status_code == 200
    second = refreshed.json()
    assert second["refresh_token"] != first["refresh_token"]

    # Replaying the old refresh token means it was copied: the whole session ends.
    replay = await client.post("/auth/refresh", json={"refresh_token": first["refresh_token"]})
    assert replay.status_code == 401
    after = await client.post("/auth/refresh", json={"refresh_token": second["refresh_token"]})
    assert after.status_code == 401
    me = await client.get("/auth/me", headers={"Authorization": f"Bearer {second['access_token']}"})
    assert me.status_code == 401


async def test_sign_out_ends_the_session(client: AsyncClient) -> None:
    tokens = await sign_in(client)
    headers = {"Authorization": f"Bearer {tokens['access_token']}"}
    assert (await client.post("/auth/sign-out", headers=headers)).status_code == 204
    assert (await client.get("/auth/me", headers=headers)).status_code == 401


async def test_drive_grant_is_stored_encrypted(
    client: AsyncClient, services: Services, db: AsyncSession
) -> None:
    tokens = await sign_in(client, drive_auth_code="good-code")
    assert tokens["drive_connected"] is True
    assert tokens["drive_error"] is None
    assert isinstance(services.drive, FakeDrive)
    assert services.drive.calls == [("good-code", "")]

    grant = (await db.execute(select(DriveGrant))).scalar_one()
    assert "drive-refresh" not in grant.encrypted_refresh_token
    assert services.cipher is not None
    assert services.cipher.decrypt(grant.encrypted_refresh_token) == "drive-refresh"


async def test_web_drive_code_uses_postmessage_redirect(
    client: AsyncClient, services: Services
) -> None:
    response = await client.post(
        "/auth/google",
        json={"id_token": "owner-token", "client": "web", "drive_auth_code": "good-code"},
    )
    assert response.status_code == 200
    assert isinstance(services.drive, FakeDrive)
    assert services.drive.calls == [("good-code", "postmessage")]


async def test_failed_drive_grant_still_signs_in_and_says_why(
    client: AsyncClient, db: AsyncSession
) -> None:
    tokens = await sign_in(client, drive_auth_code="bad-code")
    assert tokens["drive_connected"] is False
    assert "Drive" in str(tokens["drive_error"])
    assert (await db.execute(select(Owner))).scalar_one().email == "owner@example.com"


async def test_drive_grant_without_drive_scope_is_not_stored(
    client: AsyncClient, services: Services
) -> None:
    assert isinstance(services.drive, FakeDrive)
    services.drive.result = replace(services.drive.result, scopes=["openid"])
    tokens = await sign_in(client, drive_auth_code="good-code")
    assert tokens["drive_connected"] is False


async def test_web_signs_in_with_only_the_code(client: AsyncClient, services: Services) -> None:
    response = await client.post(
        "/auth/google", json={"client": "web", "drive_auth_code": "good-code"}
    )
    assert response.status_code == 200, response.text
    assert response.json()["drive_connected"] is True
    assert isinstance(services.drive, FakeDrive)
    assert services.drive.calls == [("good-code", "postmessage")]  # exchanged once


async def test_code_only_sign_in_from_another_account_is_refused(
    client: AsyncClient, services: Services, db: AsyncSession
) -> None:
    assert isinstance(services.google, FakeGoogle) and isinstance(services.drive, FakeDrive)
    services.google.identities["stranger"] = GoogleIdentity("sub-2", "someone@gmail.com", True)
    services.drive.result = replace(services.drive.result, sub="sub-2", id_token="stranger")
    response = await client.post("/auth/google", json={"client": "web", "drive_auth_code": "c"})
    assert response.status_code == 403
    assert (await db.execute(select(DriveGrant))).first() is None


async def test_code_only_sign_in_with_a_bad_code_is_rejected(client: AsyncClient) -> None:
    response = await client.post(
        "/auth/google", json={"client": "web", "drive_auth_code": "bad-code"}
    )
    assert response.status_code == 401


async def test_sign_in_needs_a_credential(client: AsyncClient) -> None:
    assert (await client.post("/auth/google", json={"client": "web"})).status_code == 422


async def test_later_consent_without_refresh_token_keeps_the_stored_grant(
    client: AsyncClient, services: Services
) -> None:
    await sign_in(client, drive_auth_code="good-code")
    assert isinstance(services.drive, FakeDrive)
    services.drive.result = replace(services.drive.result, refresh_token=None)
    tokens = await sign_in(client, drive_auth_code="good-code")
    assert tokens["drive_connected"] is True
    assert tokens["drive_error"] is None
