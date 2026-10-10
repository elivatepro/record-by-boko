import os

# Point the app at the test database before anything imports roudio.config.
os.environ["DATABASE_URL"] = os.environ.get(
    "TEST_DATABASE_URL", "postgresql+psycopg://localhost/roudio_test"
)
os.environ.setdefault("OWNER_EMAIL", "owner@example.com")

from collections.abc import AsyncIterator

import pytest
from alembic import command
from alembic.config import Config
from cryptography.fernet import Fernet
from httpx import ASGITransport, AsyncClient
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from roudio.api.app import create_app
from roudio.api.deps import Services
from roudio.auth.crypto import TokenCipher
from roudio.auth.google import (
    DRIVE_FILE_SCOPE,
    DriveGrantResult,
    GoogleAuthError,
    GoogleIdentity,
)
from roudio.config import Settings, get_settings
from roudio.db import get_sessionmaker
from roudio.plaud import FakePlaudAuth

OWNER = GoogleIdentity(sub="google-sub-owner", email="owner@example.com", email_verified=True)


class FakeGoogle:
    """ID tokens are looked up in a dict: token string → identity."""

    def __init__(self) -> None:
        self.identities: dict[str, GoogleIdentity] = {"owner-token": OWNER}

    async def verify(self, id_token: str) -> GoogleIdentity:
        try:
            return self.identities[id_token]
        except KeyError:
            raise GoogleAuthError("bad token") from None


class FakeDrive:
    def __init__(self) -> None:
        self.result = DriveGrantResult(
            "drive-refresh", [DRIVE_FILE_SCOPE, "openid"], OWNER.sub, "owner-token"
        )
        self.calls: list[tuple[str, str]] = []

    async def exchange(self, code: str, redirect_uri: str) -> DriveGrantResult:
        self.calls.append((code, redirect_uri))
        if code == "bad-code":
            raise GoogleAuthError("invalid_grant")
        return self.result


@pytest.fixture(scope="session", autouse=True)
def _migrate() -> None:
    config = Config("alembic.ini")
    command.downgrade(config, "base")
    command.upgrade(config, "head")


@pytest.fixture(autouse=True)
async def _clean() -> AsyncIterator[None]:
    yield
    async with get_sessionmaker()() as db:
        await db.execute(text("TRUNCATE job, auth_session, drive_grant, owner RESTART IDENTITY"))
        await db.commit()


@pytest.fixture
async def db() -> AsyncIterator[AsyncSession]:
    async with get_sessionmaker()() as session:
        yield session


@pytest.fixture
def settings() -> Settings:
    return get_settings()


@pytest.fixture
def services() -> Services:
    return Services(
        google=FakeGoogle(),
        drive=FakeDrive(),
        cipher=TokenCipher(Fernet.generate_key().decode()),
        plaud=FakePlaudAuth(),
    )


@pytest.fixture
async def client(settings: Settings, services: Services) -> AsyncIterator[AsyncClient]:
    app = create_app(settings, services)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as c:
        yield c


async def sign_in(client: AsyncClient, **extra: object) -> dict[str, object]:
    response = await client.post(
        "/auth/google", json={"id_token": "owner-token", "client": "android", **extra}
    )
    assert response.status_code == 200, response.text
    body: dict[str, object] = response.json()
    return body
