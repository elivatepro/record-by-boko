import os

# Point the app at the test database before anything imports roudio.config.
os.environ["DATABASE_URL"] = os.environ.get(
    "TEST_DATABASE_URL", "postgresql+psycopg://localhost/roudio_test"
)
os.environ.setdefault("OWNER_EMAIL", "owner@example.com")

import subprocess
from collections.abc import AsyncIterator, Iterator
from datetime import UTC, datetime
from pathlib import Path
from typing import Any
from uuid import uuid4

import boto3
import pytest
from alembic import command
from alembic.config import Config
from cryptography.fernet import Fernet
from httpx import ASGITransport, AsyncClient
from moto import mock_aws
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
from roudio.models import Job, Recording
from roudio.pipeline import services as pipeline_services
from roudio.pipeline.brain import FakeBrain
from roudio.pipeline.process import PROCESS
from roudio.pipeline.speech import EnergyDetector, SpeechSettings
from roudio.pipeline.transcribe import FakeTranscriber
from roudio.plaud import FakePlaudAuth
from roudio.storage import S3AudioStore

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
        await db.execute(
            text(
                "TRUNCATE recording, job, auth_session, drive_grant, owner, word, day_digest,"
                " notification, cost_entry RESTART IDENTITY CASCADE"
            )
        )
        await db.commit()


@pytest.fixture
async def db() -> AsyncIterator[AsyncSession]:
    async with get_sessionmaker()() as session:
        yield session


@pytest.fixture
def settings() -> Settings:
    return get_settings()


TEST_BUCKET = "roudio-test-audio"


@pytest.fixture
def s3() -> Iterator[object]:
    """A fake S3 (moto) with an empty bucket; the real S3AudioStore talks to it."""
    with mock_aws():
        client = boto3.client("s3", region_name="us-east-1")
        client.create_bucket(Bucket=TEST_BUCKET)
        yield client


@pytest.fixture
def services(s3: object) -> Services:
    return Services(
        google=FakeGoogle(),
        drive=FakeDrive(),
        cipher=TokenCipher(Fernet.generate_key().decode()),
        plaud=FakePlaudAuth(),
        audio=S3AudioStore(
            TEST_BUCKET,
            endpoint=None,
            access_key_id="test",
            secret_access_key="test",
            region="us-east-1",
        ),
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


# Pipeline fixtures (synthetic audio only; real recordings never go in the repo).


@pytest.fixture(scope="session")
def audio_file(tmp_path_factory: pytest.TempPathFactory) -> Path:
    """Synthetic only: 2 s silence, 3 s tone, 4 s silence, 3 s tone, 2 s silence."""
    out = tmp_path_factory.mktemp("audio") / "day.ogg"
    parts = (
        "anullsrc=r=16000:cl=mono:d=2[a];sine=f=440:r=16000:d=3[b];anullsrc=r=16000:cl=mono:d=4[c];"
        "sine=f=660:r=16000:d=3[d];anullsrc=r=16000:cl=mono:d=2[e];[a][b][c][d][e]concat=n=5:v=0:a=1"
    )
    subprocess.run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-y",
            "-filter_complex",
            parts,
            "-c:a",
            "libopus",
            "-b:a",
            "24k",
            str(out),
        ],
        check=True,
    )
    return out


@pytest.fixture
def pipeline(s3: Any) -> Any:
    svc = pipeline_services.PipelineServices(
        audio=S3AudioStore(
            TEST_BUCKET, endpoint=None, access_key_id="t", secret_access_key="t", region="us-east-1"
        ),
        detector=EnergyDetector(SpeechSettings()),
        transcriber=FakeTranscriber(),
        brain=FakeBrain(),
        settings=get_settings(),
    )
    pipeline_services.override(svc)
    yield svc
    pipeline_services.override(None)


async def uploaded(
    db: AsyncSession, s3: Any, audio: Path, started: datetime | None = None
) -> Recording:
    key = f"recordings/TEST/{uuid4().hex}.opus"
    s3.put_object(Bucket=TEST_BUCKET, Key=key, Body=audio.read_bytes())
    recording = Recording(
        recorder_serial="8810000004F2A",
        session_id=int((started or datetime(2026, 10, 11, 9, 0, tzinfo=UTC)).timestamp()),
        started_at=started or datetime(2026, 10, 11, 9, 0, tzinfo=UTC),
        zone_offset_seconds=3600,
        size_bytes=audio.stat().st_size,
        sha256="0" * 64,
        storage_key=key,
        transfer_state="uploaded",
    )
    db.add(recording)
    await db.commit()
    return recording


def job_for(recording: Recording) -> Job:
    return Job(
        id=1, kind=PROCESS, payload={"recording_id": str(recording.id)}, attempts=1, max_attempts=8
    )
