"""Backups & clean-up (screen 14) and the recorder clean-up gate the phone follows (spec §8)."""

from datetime import UTC, datetime
from typing import Literal
from uuid import UUID

from fastapi import APIRouter, status
from pydantic import BaseModel, Field
from sqlalchemy import select

from roudio.api.deps import CurrentSession, Db, error
from roudio.api.library import RecordingSummary, recording_view
from roudio.api.schema import INT64
from roudio.backup.job import enqueue_backup, set_setting, setting
from roudio.models import DriveGrant, Notification, Recording

router = APIRouter(tags=["backups"])

DELETING = "deleting_enabled"


class BackupsView(BaseModel):
    drive_connected: bool
    deleting_enabled: bool = Field(
        description="Off until the owner has watched the gate for a while"
    )
    recordings: list[RecordingSummary]
    backed_up: int
    waiting: int
    failed: int
    can_clean_up: int
    cleared: int


@router.get("/backups", operation_id="getBackups")
async def get_backups(_: CurrentSession, db: Db) -> BackupsView:
    recordings = list(
        await db.scalars(select(Recording).order_by(Recording.started_at.desc()).limit(500))
    )
    states = [r.backup_state for r in recordings]
    cleanups = [r.cleanup_state for r in recordings]
    return BackupsView(
        drive_connected=(await db.scalar(select(DriveGrant).limit(1))) is not None,
        deleting_enabled=bool(await setting(db, DELETING, False)),
        recordings=[recording_view(r) for r in recordings],
        backed_up=states.count("backed_up"),
        waiting=sum(s in ("waiting", "uploading", "checking") for s in states),
        failed=states.count("failed"),
        can_clean_up=cleanups.count("can_clean_up"),
        cleared=cleanups.count("cleared"),
    )


@router.post("/recordings/{recording_id}/backup", operation_id="retryBackup", status_code=202)
async def retry_backup(recording_id: UUID, _: CurrentSession, db: Db) -> None:
    recording = await db.get(Recording, recording_id)
    if recording is None or recording.transfer_state != "uploaded":
        raise error(
            status.HTTP_404_NOT_FOUND, "not_found", "That recording isn't on the server yet."
        )
    from roudio import jobs
    from roudio.backup.job import BACKUP

    # A fresh job, even if one ran before: the owner asked to try again.
    await jobs.enqueue(db, BACKUP, {"recording_id": str(recording.id)})


@router.post("/recordings/{recording_id}/process", operation_id="reprocess", status_code=202)
async def reprocess(recording_id: UUID, _: CurrentSession, db: Db) -> None:
    """Write a recording up again (S8 "Try again now", or after the keys change)."""
    recording = await db.get(Recording, recording_id)
    if recording is None or recording.transfer_state != "uploaded":
        raise error(
            status.HTTP_404_NOT_FOUND, "not_found", "That recording isn't on the server yet."
        )
    from roudio import jobs
    from roudio.pipeline.process import PROCESS

    recording.processing_state = "waiting"
    recording.processing_error = None
    await db.commit()
    await jobs.enqueue(db, PROCESS, {"recording_id": str(recording.id)})


class Settings(BaseModel):
    deleting_enabled: bool


@router.get("/settings", operation_id="getSettings")
async def get_settings_view(_: CurrentSession, db: Db) -> Settings:
    return Settings(deleting_enabled=bool(await setting(db, DELETING, False)))


@router.put("/settings", operation_id="putSettings")
async def put_settings(body: Settings, _: CurrentSession, db: Db) -> Settings:
    await set_setting(db, DELETING, body.deleting_enabled)
    await db.commit()
    return body


class CleanupCandidate(BaseModel):
    recording_id: UUID
    session_id: int = Field(json_schema_extra=INT64)
    sha256: str = Field(
        description="The phone deletes only if its own copy still has this checksum"
    )
    size_bytes: int = Field(json_schema_extra=INT64)


class CleanupList(BaseModel):
    deleting_enabled: bool
    candidates: list[CleanupCandidate]


@router.get("/cleanup/{serial}", operation_id="getCleanup")
async def get_cleanup(serial: str, _: CurrentSession, db: Db) -> CleanupList:
    """Recordings from this recorder whose server copy matches the phone's and whose Drive copy is
    verified. Empty while deleting is switched off."""
    enabled = bool(await setting(db, DELETING, False))
    if not enabled:
        return CleanupList(deleting_enabled=False, candidates=[])
    rows = await db.scalars(
        select(Recording).where(
            Recording.recorder_serial == serial,
            Recording.cleanup_state == "can_clean_up",
            Recording.transfer_state == "uploaded",
            Recording.backup_state == "backed_up",
        )
    )
    return CleanupList(
        deleting_enabled=True,
        candidates=[
            CleanupCandidate(
                recording_id=r.id, session_id=r.session_id, sha256=r.sha256, size_bytes=r.size_bytes
            )
            for r in rows
        ],
    )


class CleanupReport(BaseModel):
    state: Literal["deleting", "cleared", "failed"]
    error: str | None = Field(default=None, max_length=500)


@router.post("/recordings/{recording_id}/cleanup", operation_id="reportCleanup", status_code=204)
async def report_cleanup(
    recording_id: UUID, body: CleanupReport, _: CurrentSession, db: Db
) -> None:
    recording = await db.get(Recording, recording_id)
    if recording is None:
        raise error(status.HTTP_404_NOT_FOUND, "not_found", "No such recording.")
    if body.state in ("deleting", "cleared") and recording.cleanup_state not in (
        "can_clean_up",
        "deleting",
        "cleared",
    ):
        raise error(
            status.HTTP_409_CONFLICT,
            "not_safe",
            "This recording isn't cleared for deleting from the recorder.",
        )
    if body.state == "failed":
        # Stays on the recorder and is tried again; nothing else is undone (spec §8).
        recording.cleanup_state = "can_clean_up"
        db.add(
            Notification(
                kind="cleanup_partial",
                recording_id=recording.id,
                title="Couldn't clear a recording from the recorder",
                body="It's backed up and safe. Roudio will try again.",
                action=None,
            )
        )
    else:
        recording.cleanup_state = body.state
    recording.updated_at = datetime.now(UTC)
    await db.commit()


__all__ = ["enqueue_backup", "router"]
