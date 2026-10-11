import hashlib
import shutil
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import pytest
from httpx import AsyncClient
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from roudio.backup.drive import FakeDrive
from roudio.backup.job import BACKUP, backup_recording, override_drive
from roudio.models import DriveFile, Job, Notification, Recording
from roudio.pipeline.process import process_recording
from tests.conftest import job_for, sign_in, uploaded

pytestmark = pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="needs ffmpeg")


@pytest.fixture
def drive() -> Any:
    fake = FakeDrive()
    override_drive(fake)
    yield fake
    override_drive(None)


@pytest.fixture
async def auth(client: AsyncClient) -> dict[str, str]:
    tokens = await sign_in(client)
    return {"Authorization": f"Bearer {tokens['access_token']}"}


async def on_server(
    db: AsyncSession, s3: Any, audio: Path, started: datetime | None = None
) -> Recording:
    recording = await uploaded(db, s3, audio, started)
    recording.sha256 = hashlib.sha256(audio.read_bytes()).hexdigest()
    await db.commit()
    return recording


def backup_job(recording: Recording) -> Job:
    return Job(
        id=9, kind=BACKUP, payload={"recording_id": str(recording.id)}, attempts=1, max_attempts=8
    )


async def test_the_audio_goes_into_a_day_folder_and_is_verified(
    db: AsyncSession,
    s3: Any,
    audio_file: Path,
    pipeline: Any,
    drive: FakeDrive,
) -> None:
    recording = await on_server(db, s3, audio_file)
    await backup_recording(db, backup_job(recording))
    await db.refresh(recording)
    assert recording.backup_state == "backed_up"
    assert recording.cleanup_state == "can_clean_up"
    assert ("Roudio", None) in drive.folders and any(
        name == "2026-10-11" for name, _ in drive.folders
    )
    audio = await db.scalar(select(DriveFile).where(DriveFile.kind == "audio"))
    assert audio is not None and audio.verified_at is not None
    assert drive.files[audio.drive_file_id][2] == audio_file.read_bytes()


async def test_transcript_and_notes_follow_once_written_up_and_unchanged_files_stay(
    db: AsyncSession,
    s3: Any,
    audio_file: Path,
    pipeline: Any,
    drive: FakeDrive,
) -> None:
    recording = await on_server(db, s3, audio_file)
    await process_recording(db, job_for(recording))
    await backup_recording(db, backup_job(recording))
    kinds = set(await db.scalars(select(DriveFile.kind)))
    assert kinds == {"audio", "transcript_txt", "transcript_json", "notes"}
    uploads = drive.uploads
    await backup_recording(db, backup_job(recording))
    assert drive.uploads == uploads, "nothing changed, nothing re-uploaded"


async def test_a_copy_that_doesnt_match_blocks_clean_up(
    db: AsyncSession,
    s3: Any,
    audio_file: Path,
    pipeline: Any,
    drive: FakeDrive,
) -> None:
    drive.corrupt = True
    recording = await on_server(db, s3, audio_file)
    with pytest.raises(Exception, match="doesn't match"):
        await backup_recording(db, backup_job(recording))
    await db.refresh(recording)
    assert recording.backup_state == "failed"
    assert recording.cleanup_state == "blocked"
    note = await db.scalar(select(Notification).where(Notification.kind == "backup_failed"))
    assert note is not None and note.action == "retry_backup"


async def test_without_drive_it_waits(
    db: AsyncSession,
    s3: Any,
    audio_file: Path,
    pipeline: Any,
) -> None:
    recording = await on_server(db, s3, audio_file)
    await backup_recording(db, backup_job(recording))  # no grant stored in tests
    await db.refresh(recording)
    assert recording.backup_state == "waiting"
    assert "isn't connected" in (recording.backup_error or "")
    assert await db.scalar(select(func.count()).select_from(Job).where(Job.kind == BACKUP)) == 1


async def test_the_gate_only_lists_verified_recordings_with_deleting_on(
    client: AsyncClient,
    auth: dict[str, str],
    db: AsyncSession,
    s3: Any,
    audio_file: Path,
    pipeline: Any,
    drive: FakeDrive,
) -> None:
    recording = await on_server(db, s3, audio_file)
    blocked = await on_server(db, s3, audio_file, datetime(2026, 10, 11, 12, 0, tzinfo=UTC))
    await backup_recording(db, backup_job(recording))

    off = (await client.get(f"/cleanup/{recording.recorder_serial}", headers=auth)).json()
    assert off == {"deleting_enabled": False, "candidates": []}

    assert (await client.put("/settings", json={"deleting_enabled": True}, headers=auth)).json()[
        "deleting_enabled"
    ]
    on = (await client.get(f"/cleanup/{recording.recorder_serial}", headers=auth)).json()
    assert [c["recording_id"] for c in on["candidates"]] == [str(recording.id)]
    assert on["candidates"][0]["sha256"] == recording.sha256

    refused = await client.post(
        f"/recordings/{blocked.id}/cleanup", json={"state": "cleared"}, headers=auth
    )
    assert refused.status_code == 409
    done = await client.post(
        f"/recordings/{recording.id}/cleanup", json={"state": "cleared"}, headers=auth
    )
    assert done.status_code == 204
    await db.refresh(recording)
    assert recording.cleanup_state == "cleared"

    backups = (await client.get("/backups", headers=auth)).json()
    assert backups["cleared"] == 1 and backups["deleting_enabled"] is True
    assert datetime.now(UTC)


async def test_costs_add_up(
    client: AsyncClient,
    auth: dict[str, str],
    db: AsyncSession,
    s3: Any,
    audio_file: Path,
    pipeline: Any,
) -> None:
    recording = await on_server(db, s3, audio_file)
    await process_recording(db, job_for(recording))
    costs = (await client.get("/costs", headers=auth)).json()
    assert costs["total_usd"] >= 0
    assert any(line["service"] == "scribe" for line in costs["total"])
    assert costs["stored_audio_bytes"] == recording.size_bytes


async def test_drive_api_switched_off_says_how_to_fix_it() -> None:
    import httpx

    from roudio.backup.drive import DriveNotEnabled, HttpDrive

    body = '{"error": {"code": 403, "details": [{"reason": "SERVICE_DISABLED"}]}}'
    drive = HttpDrive("id", "secret", "refresh")
    drive._access = "token"
    drive._http = httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _: httpx.Response(403, text=body))
    )
    with pytest.raises(DriveNotEnabled, match="Google Drive API is off"):
        await drive.folder("Roudio", None)
