"""The backup job: original audio, transcript (text + JSON with times) and notes into a per-day
Drive folder, each verified by MD5 and by reading it back (spec §8). Re-run when results change.

When the audio's Drive copy is verified (and the server's copy already matched the phone's),
the recording's clean-up state becomes "can_clean_up". The phone deletes from the recorder only
then, and only with deleting switched on in Settings.
"""

import asyncio
import hashlib
import json
import logging
import tempfile
from datetime import UTC, datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from uuid import UUID

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from roudio import jobs
from roudio.auth.crypto import TokenCipher
from roudio.backup.drive import Drive, DriveAccessLost, DriveError, DriveSetupError, HttpDrive
from roudio.config import get_settings
from roudio.models import (
    AppSetting,
    BackupState,
    Conversation,
    DriveFile,
    DriveGrant,
    GeneratedItem,
    Job,
    Note,
    Notification,
    ProcessingState,
    Recording,
    Speaker,
    TranscriptSegment,
    TransferState,
)
from roudio.pipeline import services
from roudio.worker.loop import PermanentJobError

log = logging.getLogger(__name__)

BACKUP = "backup_recording"
RETRY_WITHOUT_DRIVE = timedelta(hours=1)

_drive_override: Drive | None = None


def override_drive(drive: Drive | None) -> None:
    """Tests: use this Drive instead of the owner's."""
    global _drive_override
    _drive_override = drive


async def drive_for_owner(db: AsyncSession) -> Drive | None:
    if _drive_override is not None:
        return _drive_override
    settings = get_settings()
    grant = await db.scalar(select(DriveGrant).limit(1))
    if (
        grant is None
        or not settings.drive_token_key
        or not settings.google_client_secret
        or not settings.google_client_ids
    ):
        return None
    refresh = TokenCipher(settings.drive_token_key.get_secret_value()).decrypt(
        grant.encrypted_refresh_token
    )
    return HttpDrive(
        settings.google_client_ids[0], settings.google_client_secret.get_secret_value(), refresh
    )


async def enqueue_backup(db: AsyncSession, recording: Recording) -> None:
    await jobs.enqueue(
        db,
        BACKUP,
        {"recording_id": str(recording.id)},
        dedupe_key=f"backup:{recording.id}:{recording.sha256}:{recording.results_version}",
    )


async def setting(db: AsyncSession, key: str, default: Any = None) -> Any:
    row = await db.get(AppSetting, key)
    return default if row is None else row.value


async def set_setting(db: AsyncSession, key: str, value: Any) -> None:
    row = await db.get(AppSetting, key)
    if row is None:
        db.add(AppSetting(key=key, value=value))
    else:
        row.value = value


def _md5_and_sha(path: Path) -> tuple[str, str]:
    md5, sha = hashlib.md5(), hashlib.sha256()  # noqa: S324 - MD5 is what Drive reports
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            md5.update(block)
            sha.update(block)
    return md5.hexdigest(), sha.hexdigest()


async def backup_recording(db: AsyncSession, job: Job) -> None:
    recording = await db.get(Recording, UUID(job.payload["recording_id"]))
    if recording is None:
        raise PermanentJobError("The recording no longer exists.")
    if recording.transfer_state != TransferState.UPLOADED:
        raise PermanentJobError("The recording isn't on the server yet.")
    drive = await drive_for_owner(db)
    if drive is None:
        await _fail(
            db,
            recording,
            "Google Drive isn't connected. Sign in again to connect it.",
            state=BackupState.WAITING,
        )
        await jobs.enqueue(
            db,
            BACKUP,
            {"recording_id": str(recording.id)},
            run_after=datetime.now(UTC) + RETRY_WITHOUT_DRIVE,
        )
        return
    try:
        with tempfile.TemporaryDirectory(prefix="roudio-backup-") as tmp:
            await _backup(db, recording, drive, Path(tmp))
    except DriveAccessLost as exc:
        await db.rollback()
        await _fail(db, recording, str(exc))
        raise PermanentJobError(str(exc)) from exc
    except DriveSetupError as exc:
        # Something the owner fixes in Google Cloud or on Railway; the message says what.
        await db.rollback()
        await _fail(db, recording, str(exc))
        raise exc
    except DriveError as exc:
        await db.rollback()
        await _fail(db, recording, "The backup to Drive didn't finish. It will be tried again.")
        raise exc


async def _fail(
    db: AsyncSession, recording: Recording, message: str, state: BackupState = BackupState.FAILED
) -> None:
    await db.refresh(recording)
    recording.backup_state = state.value
    recording.backup_error = message
    if recording.cleanup_state == "can_clean_up":
        recording.cleanup_state = "blocked"
    unread = await db.scalar(
        select(func.count())
        .select_from(Notification)
        .where(
            Notification.recording_id == recording.id,
            Notification.kind == "backup_failed",
            Notification.read_at.is_(None),
        )
    )
    if not unread:
        db.add(
            Notification(
                kind="backup_failed",
                recording_id=recording.id,
                title="Backup failed",
                body=f"{message} It's safe on your phone and the server, and on the recorder.",
                action="retry_backup",
            )
        )
    await db.commit()


async def _day_folder(db: AsyncSession, drive: Drive, recording: Recording) -> str:
    root = await setting(db, "drive_root")
    if root is None:
        root = await drive.folder("Roudio", None)
        await set_setting(db, "drive_root", root)
    zone = timezone(timedelta(seconds=recording.zone_offset_seconds))
    day = recording.started_at.astimezone(zone).strftime("%Y-%m-%d")
    key = f"drive_day:{day}"
    folder = await setting(db, key)
    if folder is None:
        folder = await drive.folder(day, root)
        await set_setting(db, key, folder)
    await db.commit()
    return str(folder)


async def _put(
    db: AsyncSession,
    drive: Drive,
    recording: Recording,
    kind: str,
    path: Path,
    name: str,
    parent: str,
    mime: str,
) -> None:
    """Upload if the content changed, then verify: Drive's MD5 matches and the file reads back."""
    md5, _ = await asyncio.to_thread(_md5_and_sha, path)
    size = (await asyncio.to_thread(path.stat)).st_size
    existing = await db.scalar(
        select(DriveFile).where(DriveFile.recording_id == recording.id, DriveFile.kind == kind)
    )
    if existing is None or existing.md5 != md5 or existing.verified_at is None:
        info = await drive.upload(
            path, name, parent, mime, existing.drive_file_id if existing else None
        )
        if existing is None:
            existing = DriveFile(
                recording_id=recording.id,
                kind=kind,
                drive_file_id=info.id,
                md5=md5,
                size_bytes=size,
            )
            db.add(existing)
        existing.drive_file_id, existing.md5, existing.size_bytes, existing.verified_at = (
            info.id,
            md5,
            size,
            None,
        )
        await db.commit()
    checked = await drive.info(existing.drive_file_id)
    if checked.md5 != md5 or checked.size != size:
        existing.verified_at = None
        await db.commit()
        raise DriveError(f"The Drive copy of the {kind} doesn't match")
    # An empty file (the transcript of a recording with no speech) has nothing to read back; its
    # MD5 and size already matched.
    if size > 0 and not await drive.read_head(existing.drive_file_id):
        raise DriveError(f"The Drive copy of the {kind} can't be read back")
    existing.verified_at = datetime.now(UTC)
    await db.commit()


async def _backup(db: AsyncSession, recording: Recording, drive: Drive, tmp: Path) -> None:
    recording.backup_state = BackupState.UPLOADING.value
    recording.backup_error = None
    await db.commit()
    folder = await _day_folder(db, drive, recording)
    zone = timezone(timedelta(seconds=recording.zone_offset_seconds))
    stem = (
        f"{recording.started_at.astimezone(zone):%Y-%m-%d %H.%M} {recording.recorder_serial[-4:]}"
    )

    audio = tmp / "audio.opus"
    await services.get().audio.download(recording.storage_key, audio)
    _, sha = await asyncio.to_thread(_md5_and_sha, audio)
    if sha != recording.sha256:
        raise DriveError("The server's copy no longer matches the phone's checksum")
    recording.backup_state = BackupState.CHECKING.value
    await db.commit()
    await _put(db, drive, recording, "audio", audio, f"{stem}.opus", folder, "audio/ogg")

    if recording.processing_state == ProcessingState.DONE:
        transcript, data = await _transcript(db, recording)
        txt, js = tmp / "transcript.txt", tmp / "transcript.json"
        txt.write_text(transcript, encoding="utf-8")
        js.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
        await _put(
            db,
            drive,
            recording,
            "transcript_txt",
            txt,
            f"{stem} transcript.txt",
            folder,
            "text/plain",
        )
        await _put(
            db,
            drive,
            recording,
            "transcript_json",
            js,
            f"{stem} transcript.json",
            folder,
            "application/json",
        )
        notes = tmp / "notes.md"
        notes.write_text(await _notes(db, recording), encoding="utf-8")
        await _put(
            db, drive, recording, "notes", notes, f"{stem} notes.md", folder, "text/markdown"
        )

    await db.refresh(recording)
    log.info("recording %s backed up to Drive", recording.id)
    recording.backup_state = BackupState.BACKED_UP.value
    recording.backup_error = None
    recording.backed_up_at = datetime.now(UTC)
    # Phone and server checksums matched at upload; the Drive audio is now verified (spec §8).
    if recording.cleanup_state in ("blocked", "can_clean_up"):
        recording.cleanup_state = "can_clean_up"
    await db.execute(
        Notification.__table__.update()  # type: ignore[attr-defined]
        .where(Notification.recording_id == recording.id, Notification.kind == "backup_failed")
        .values(read_at=func.now())
    )
    await db.commit()


def _clock(ms: int) -> str:
    s = ms // 1000
    return f"{s // 3600}:{s % 3600 // 60:02d}:{s % 60:02d}"


async def _transcript(db: AsyncSession, recording: Recording) -> tuple[str, dict[str, Any]]:
    names = {
        s.label: s.name
        for s in await db.scalars(select(Speaker).where(Speaker.recording_id == recording.id))
    }
    segments = list(
        await db.scalars(
            select(TranscriptSegment)
            .where(TranscriptSegment.recording_id == recording.id)
            .order_by(TranscriptSegment.idx)
        )
    )
    text = "\n\n".join(
        f"[{_clock(s.start_ms)}] {names.get(s.speaker) or s.speaker}: {s.text}" for s in segments
    )
    data = {
        "recording": {
            "started_at": recording.started_at.astimezone(UTC).isoformat(),
            "zone_offset_seconds": recording.zone_offset_seconds,
            "language": recording.language,
        },
        "segments": [
            {
                "idx": s.idx,
                "speaker": names.get(s.speaker) or s.speaker,
                "start_ms": s.start_ms,
                "end_ms": s.end_ms,
                "text": s.text,
                "original_text": s.original_text,
                "corrections": s.corrections,
                "words": s.words,
            }
            for s in segments
        ],
    }
    return text, data


async def _notes(db: AsyncSession, recording: Recording) -> str:
    parts = []
    for c in await db.scalars(
        select(Conversation)
        .where(Conversation.recording_id == recording.id)
        .order_by(Conversation.start_ms)
    ):
        parts.append(
            f"# {c.title}\n\n_{_clock(c.start_ms)} to {_clock(c.end_ms)}_\n\n{c.summary}\n"
        )
        for kind, heading in (
            ("decision", "Decided"),
            ("task", "Tasks"),
            ("key_moment", "Key moments"),
        ):
            items = list(
                await db.scalars(
                    select(GeneratedItem)
                    .where(GeneratedItem.conversation_id == c.id, GeneratedItem.kind == kind)
                    .order_by(GeneratedItem.position)
                )
            )
            if items:
                parts.append(
                    f"## {heading}\n"
                    + "\n".join(f"- {i.text} ({_clock(i.at_ms)})" for i in items)
                    + "\n"
                )
        note = await db.get(Note, c.id)
        if note:
            parts.append(f"## Your note\n{note.text}\n")
    return "\n".join(parts) or "Nothing was written up for this recording.\n"
