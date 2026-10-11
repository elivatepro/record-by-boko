"""Resumable, chunked upload of original audio from the phone (spec §3 and §4, milestone 2).

1. `PUT /recordings/{serial}/{session_id}` says what's coming (size, SHA-256, times). It's safe
   to repeat: the answer says how many bytes have already arrived, so an interrupted upload
   carries on from there.
2. `PUT /recordings/{id}/parts/{n}` sends part n (1-based) as raw bytes. Every part but the last
   is exactly `part_bytes` long.
3. `POST /recordings/{id}/complete` joins the parts, reads the file back from the bucket and
   compares its SHA-256 with the phone's. Only then is the recording "uploaded". On a mismatch the
   stored copy is thrown away and the phone starts the upload again.
"""

import logging
import re
from datetime import UTC, datetime
from typing import Annotated, Literal
from uuid import UUID

from fastapi import APIRouter, Path, Request, status
from pydantic import BaseModel, Field, field_validator
from sqlalchemy import select

from roudio.api.deps import AppServices, AppSettings, CurrentSession, Db, error
from roudio.models import Recording, TransferState
from roudio.storage import Part, StorageError

log = logging.getLogger(__name__)
router = APIRouter(prefix="/recordings", tags=["recordings"])

# Five hours of Opus at the Note Pro's ~33 kbps is about 75 MB; anything far past that is wrong.
MAX_RECORDING_BYTES = 2 * 1024 * 1024 * 1024
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_SERIAL = r"^[0-9A-Za-z]{4,32}$"


class RecordingUploadRequest(BaseModel):
    started_at: datetime = Field(description="When the recording began, in UTC")
    zone_offset_seconds: int = Field(ge=-14 * 3600, le=14 * 3600)
    duration_ms: int | None = Field(default=None, ge=0)
    size_bytes: int = Field(gt=0, le=MAX_RECORDING_BYTES)
    sha256: str = Field(description="Lower-case hex SHA-256 of the whole file")

    @field_validator("sha256")
    @classmethod
    def _hex(cls, value: str) -> str:
        if not _SHA256.match(value):
            raise ValueError("must be 64 lower-case hex characters")
        return value


class RecordingUpload(BaseModel):
    id: UUID
    state: Literal["uploading", "uploaded"]
    part_bytes: int = Field(description="Size of every part but the last")
    parts: int = Field(description="How many parts the whole file takes")
    received_bytes: int = Field(description="Bytes stored so far, counted from the start")
    next_part: int | None = Field(description="The part to send next; null when all are in")


SerialPath = Annotated[str, Path(pattern=_SERIAL)]


@router.put("/{serial}/{session_id}", operation_id="startRecordingUpload")
async def start_upload(
    serial: SerialPath,
    # Unix seconds: int64, so generated clients don't use a 32-bit int (2038).
    session_id: Annotated[int, Path(ge=0, json_schema_extra={"format": "int64"})],
    body: RecordingUploadRequest,
    _: CurrentSession,
    db: Db,
    services: AppServices,
    settings: AppSettings,
) -> RecordingUpload:
    store = services.audio
    recording = await db.scalar(
        select(Recording)
        .where(Recording.recorder_serial == serial, Recording.session_id == session_id)
        .with_for_update()
    )
    try:
        if recording is not None and recording.transfer_state == TransferState.UPLOADED:
            if recording.sha256 != body.sha256 or recording.size_bytes != body.size_bytes:
                raise error(
                    status.HTTP_409_CONFLICT,
                    "already_uploaded",
                    "A different file for this recording is already on the server.",
                )
            return _uploaded(recording, settings.upload_part_bytes)

        if recording is None:
            recording = Recording(
                recorder_serial=serial,
                session_id=session_id,
                storage_key=f"recordings/{serial}/{session_id}.opus",
                transfer_state=TransferState.UPLOADING.value,
                **_metadata(body),
            )
            db.add(recording)
        elif recording.sha256 != body.sha256 or recording.size_bytes != body.size_bytes:
            # The phone exported it again and got a different file: start over.
            if recording.upload_id:
                await store.abort(recording.storage_key, recording.upload_id)
            recording.upload_id = None
            for name, value in _metadata(body).items():
                setattr(recording, name, value)

        if recording.upload_id is None:
            recording.upload_id = await store.start_upload(recording.storage_key)
        received = await store.parts(recording.storage_key, recording.upload_id)
        await db.commit()
    except StorageError as exc:
        await db.rollback()
        raise _storage_unavailable(exc) from exc
    return _progress(recording, settings.upload_part_bytes, received)


@router.put(
    "/{recording_id}/parts/{number}",
    operation_id="uploadRecordingPart",
    openapi_extra={
        "requestBody": {
            "required": True,
            "content": {
                "application/octet-stream": {"schema": {"type": "string", "format": "binary"}}
            },
        }
    },
)
async def upload_part(
    recording_id: UUID,
    number: Annotated[int, Path(ge=1, le=10_000)],
    request: Request,
    _: CurrentSession,
    db: Db,
    services: AppServices,
    settings: AppSettings,
) -> RecordingUpload:
    recording = await _uploading(db, recording_id, settings.upload_part_bytes)
    if isinstance(recording, RecordingUpload):
        return recording
    part_bytes = settings.upload_part_bytes
    total = _part_count(recording.size_bytes, part_bytes)
    if number > total:
        raise error(status.HTTP_400_BAD_REQUEST, "no_such_part", f"This file has {total} parts.")
    expected = _part_size(recording.size_bytes, part_bytes, number)
    data = await request.body()
    if len(data) != expected:
        raise error(
            status.HTTP_400_BAD_REQUEST,
            "wrong_part_size",
            f"Part {number} should be {expected} bytes, not {len(data)}.",
        )
    assert recording.upload_id is not None
    try:
        await services.audio.put_part(recording.storage_key, recording.upload_id, number, data)
        received = await services.audio.parts(recording.storage_key, recording.upload_id)
    except StorageError as exc:
        raise _storage_unavailable(exc) from exc
    return _progress(recording, part_bytes, received)


@router.post("/{recording_id}/complete", operation_id="completeRecordingUpload")
async def complete_upload(
    recording_id: UUID,
    _: CurrentSession,
    db: Db,
    services: AppServices,
    settings: AppSettings,
) -> RecordingUpload:
    found = await _uploading(db, recording_id, settings.upload_part_bytes, lock=True)
    if isinstance(found, RecordingUpload):
        return found
    recording = found
    store = services.audio
    part_bytes = settings.upload_part_bytes
    assert recording.upload_id is not None
    try:
        received = await store.parts(recording.storage_key, recording.upload_id)
        if _contiguous(received, recording.size_bytes, part_bytes) < recording.size_bytes:
            raise error(
                status.HTTP_409_CONFLICT,
                "parts_missing",
                "Some parts haven't arrived yet.",
            )
        total = _part_count(recording.size_bytes, part_bytes)
        await store.complete(recording.storage_key, recording.upload_id, received[:total])
        sha, size = await store.digest(recording.storage_key)
        if sha != recording.sha256 or size != recording.size_bytes:
            log.warning(
                "recording %s: stored file doesn't match the phone's checksum", recording.id
            )
            await store.delete(recording.storage_key)
            recording.upload_id = None
            await db.commit()
            raise error(
                status.HTTP_409_CONFLICT,
                "checksum_mismatch",
                "The file on the server doesn't match the phone's copy. Upload it again.",
            )
        recording.upload_id = None
        recording.transfer_state = TransferState.UPLOADED.value
        recording.uploaded_at = datetime.now(UTC)
        await db.commit()
    except StorageError as exc:
        await db.rollback()
        raise _storage_unavailable(exc) from exc
    log.info("recording %s uploaded (%d bytes)", recording.id, recording.size_bytes)
    return _uploaded(recording, part_bytes)


async def _uploading(
    db: Db, recording_id: UUID, part_bytes: int, *, lock: bool = False
) -> Recording | RecordingUpload:
    """The recording if it's still uploading; its finished state if it's already uploaded."""
    query = select(Recording).where(Recording.id == recording_id)
    recording = await db.scalar(query.with_for_update() if lock else query)
    if recording is None:
        raise error(status.HTTP_404_NOT_FOUND, "not_found", "No such recording.")
    if recording.transfer_state == TransferState.UPLOADED:
        return _uploaded(recording, part_bytes)
    if recording.upload_id is None:
        raise error(
            status.HTTP_409_CONFLICT,
            "upload_not_started",
            "Start the upload again.",
        )
    return recording


def _metadata(body: RecordingUploadRequest) -> dict[str, object]:
    return {
        "started_at": body.started_at,
        "zone_offset_seconds": body.zone_offset_seconds,
        "duration_ms": body.duration_ms,
        "size_bytes": body.size_bytes,
        "sha256": body.sha256,
    }


def _part_count(size: int, part_bytes: int) -> int:
    return max(1, -(-size // part_bytes))


def _part_size(size: int, part_bytes: int, number: int) -> int:
    return min(part_bytes, size - part_bytes * (number - 1))


def _contiguous(parts: list[Part], size: int, part_bytes: int) -> int:
    """Bytes stored from the start with no gap, each part the size it should be."""
    received = 0
    for expected, part in enumerate(parts, start=1):
        if part.number != expected or part.size != _part_size(size, part_bytes, expected):
            break
        received += part.size
    return received


def _progress(recording: Recording, part_bytes: int, parts: list[Part]) -> RecordingUpload:
    received = _contiguous(parts, recording.size_bytes, part_bytes)
    total = _part_count(recording.size_bytes, part_bytes)
    done = received >= recording.size_bytes
    return RecordingUpload(
        id=recording.id,
        state="uploading",
        part_bytes=part_bytes,
        parts=total,
        received_bytes=received,
        next_part=None if done else received // part_bytes + 1,
    )


def _uploaded(recording: Recording, part_bytes: int) -> RecordingUpload:
    return RecordingUpload(
        id=recording.id,
        state="uploaded",
        part_bytes=part_bytes,
        parts=_part_count(recording.size_bytes, part_bytes),
        received_bytes=recording.size_bytes,
        next_part=None,
    )


def _storage_unavailable(exc: StorageError) -> Exception:
    log.error("audio storage: %s", exc)
    return error(
        status.HTTP_503_SERVICE_UNAVAILABLE,
        "storage_unavailable",
        "The server couldn't store audio just now. The phone keeps its copy and tries again.",
    )
