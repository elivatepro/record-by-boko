"""The processing job (spec §5): find speech → transcribe → tidy → understand → done.

Each step saves the recording's processing state so the apps can show it, and replaces its own
earlier output, so the job is safe to run twice. A missing API key leaves the recording "waiting"
and tries again in an hour instead of failing.
"""

import asyncio
import contextlib
import logging
import tempfile
from collections.abc import AsyncIterator
from datetime import UTC, date, datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path
from uuid import UUID

from sqlalchemy import delete, func, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from roudio import jobs
from roudio.db import get_sessionmaker
from roudio.models import (
    Conversation,
    CostEntry,
    GeneratedItem,
    ItemKind,
    Job,
    Notification,
    ProcessingState,
    Recording,
    ReviewItem,
    ReviewState,
    Speaker,
    SpeechSegment,
    TranscriptSegment,
    TransferState,
    Word,
)
from roudio.pipeline import services
from roudio.pipeline.audio import encode_opus, speech_only, to_original
from roudio.pipeline.brain import BrainError, BrainNotConfigured, Line, Understood, Usage
from roudio.pipeline.transcribe import TranscriptionError, TranscriptionNotConfigured, turns
from roudio.pipeline.transcribe import Word as HeardWord
from roudio.worker.loop import PermanentJobError

log = logging.getLogger(__name__)

PROCESS = "process_recording"
DIGEST = "build_digest"
# At most this much speech per transcription request; speakers are told apart within one.
MAX_REQUEST_MS = 2 * 3600 * 1000
NOT_CONFIGURED_RETRY = timedelta(hours=1)


async def enqueue_processing(db: AsyncSession, recording: Recording) -> None:
    await jobs.enqueue(
        db,
        PROCESS,
        {"recording_id": str(recording.id)},
        dedupe_key=f"process:{recording.id}:{recording.sha256}",
    )


async def enqueue_digest(db: AsyncSession, day: date) -> None:
    await jobs.enqueue(db, DIGEST, {"day": day.isoformat()})


@contextlib.asynccontextmanager
async def _heartbeat(job: Job) -> AsyncIterator[None]:
    """Keep the job's lease while a long step (a big transcription) runs."""

    async def beat() -> None:
        while True:
            await asyncio.sleep(60)
            async with get_sessionmaker()() as db:
                await jobs.heartbeat(db, job)

    task = asyncio.create_task(beat())
    try:
        yield
    finally:
        task.cancel()


def local_day(recording: Recording, offset_ms: int) -> date:
    """The local date at `offset_ms` into the recording, using the phone's offset when recorded."""
    zone = timezone(timedelta(seconds=recording.zone_offset_seconds))
    return (recording.started_at + timedelta(milliseconds=offset_ms)).astimezone(zone).date()


async def process_recording(db: AsyncSession, job: Job) -> None:
    recording = await db.get(Recording, UUID(job.payload["recording_id"]))
    if recording is None:
        raise PermanentJobError("The recording no longer exists.")
    if recording.transfer_state != TransferState.UPLOADED:
        raise PermanentJobError("The recording isn't fully uploaded.")
    svc = services.get()
    async with _heartbeat(job):
        try:
            with tempfile.TemporaryDirectory(prefix="roudio-") as tmp:
                await _run(db, recording, Path(tmp), svc)
        except (TranscriptionNotConfigured, BrainNotConfigured) as exc:
            await db.rollback()
            await _set_state(db, recording, ProcessingState.WAITING, str(exc))
            await jobs.enqueue(
                db,
                PROCESS,
                {"recording_id": str(recording.id)},
                run_after=datetime.now(UTC) + NOT_CONFIGURED_RETRY,
            )
        except PermanentJobError:
            raise
        except Exception as exc:
            await db.rollback()
            message = _plain(exc)
            await _set_state(db, recording, ProcessingState.FAILED, message)
            await _notify_failure(db, recording, message)
            raise


def _plain(exc: Exception) -> str:
    if isinstance(exc, TranscriptionError):
        return "Transcription failed. It will be tried again."
    if isinstance(exc, BrainError):
        return "Writing up failed. It will be tried again."
    return "Processing failed. It will be tried again."


async def _set_state(
    db: AsyncSession, recording: Recording, state: ProcessingState, error: str | None = None
) -> None:
    await db.refresh(recording)
    recording.processing_state = state.value
    recording.processing_error = error
    await db.commit()


async def _notify_failure(db: AsyncSession, recording: Recording, message: str) -> None:
    exists = await db.scalar(
        select(func.count())
        .select_from(Notification)
        .where(
            Notification.recording_id == recording.id,
            Notification.kind == "processing_failed",
            Notification.read_at.is_(None),
        )
    )
    if not exists:
        db.add(
            Notification(
                kind="processing_failed",
                recording_id=recording.id,
                title="Writing up failed",
                body=f"{message} The audio is safe on your phone and the server.",
                action="retry",
            )
        )
        await db.commit()


async def _run(
    db: AsyncSession, recording: Recording, tmp: Path, svc: services.PipelineServices
) -> None:
    # 1. Find speech in the original.
    await _set_state(db, recording, ProcessingState.FINDING_SPEECH)
    original = tmp / "original.opus"
    await svc.audio.download(recording.storage_key, original)
    segments, total_ms = await asyncio.to_thread(svc.detector.detect, original)
    await db.execute(delete(SpeechSegment).where(SpeechSegment.recording_id == recording.id))
    db.add_all(SpeechSegment(recording_id=recording.id, start_ms=s, end_ms=e) for s, e in segments)
    recording.speech_ms = sum(e - s for s, e in segments)
    if recording.duration_ms is None:
        recording.duration_ms = total_ms
    await db.commit()

    # 2. Transcribe only the speech, in requests of up to two hours.
    await _set_state(db, recording, ProcessingState.TRANSCRIBING)
    keyterms = list(await db.scalars(select(Word.term).where(Word.confirmed)))
    heard: list[HeardWord] = []
    language: str | None = None
    groups = _groups(segments)
    for n, group in enumerate(groups):
        raw, opus = tmp / f"speech-{n}.pcm", tmp / f"speech-{n}.opus"
        pieces, _ = await asyncio.to_thread(speech_only, original, group, raw)
        if not pieces:
            continue
        await asyncio.to_thread(encode_opus, raw, opus)
        transcript = await svc.transcriber.transcribe(opus, keyterms=keyterms)
        language = language or transcript.language
        prefix = f"{n + 1}:" if len(groups) > 1 else ""
        heard += [
            HeardWord(
                w.text,
                to_original(pieces, w.start_ms),
                to_original(pieces, w.end_ms),
                prefix + w.speaker,
            )
            for w in transcript.words
        ]
        await _cost(
            db,
            recording,
            "scribe",
            Decimal(str(round(transcript.billed_seconds, 3))),
            "seconds",
            Decimal(
                str(
                    transcript.billed_seconds
                    / 3600
                    * (
                        svc.settings.scribe_usd_per_hour
                        + (
                            svc.settings.scribe_keyterms_usd_per_hour
                            if transcript.keyterms_used
                            else 0
                        )
                    )
                )
            ),
        )
    spoken = turns(heard)
    await db.execute(
        delete(TranscriptSegment).where(TranscriptSegment.recording_id == recording.id)
    )
    labels = sorted({t.speaker for t in spoken})
    names = {
        s.label: s.name
        for s in await db.scalars(select(Speaker).where(Speaker.recording_id == recording.id))
    }
    await db.execute(delete(Speaker).where(Speaker.recording_id == recording.id))
    db.add_all(
        Speaker(recording_id=recording.id, label=label, name=names.get(label)) for label in labels
    )
    rows = [
        TranscriptSegment(
            recording_id=recording.id,
            idx=i,
            speaker=t.speaker,
            start_ms=t.start_ms,
            end_ms=t.end_ms,
            text=t.text,
            original_text=t.text,
            language=language,
            words=[{"t": w.text, "s": w.start_ms, "e": w.end_ms} for w in t.words],
        )
        for i, t in enumerate(spoken)
    ]
    db.add_all(rows)
    recording.language = language
    await db.commit()

    lines = [Line(r.idx, r.speaker, r.start_ms, r.text) for r in rows]
    if lines:
        # 3. Tidy: flagged corrections only where the words really are there.
        await _set_state(db, recording, ProcessingState.TIDYING)
        words = list(await db.scalars(select(Word.term).where(Word.confirmed)))
        tidy = await svc.brain.tidy(lines, words)
        await _ai_cost(db, recording, tidy.usage, svc)
        by_idx = {r.idx: r for r in rows}
        for fix in tidy.value:
            row = by_idx.get(fix.idx)
            if row is None or not fix.find or fix.find not in row.text or fix.find == fix.replace:
                continue
            row.text = row.text.replace(fix.find, fix.replace, 1)
            row.corrections = [*row.corrections, {"from": fix.find, "to": fix.replace}]
        await db.commit()
        lines = [Line(r.idx, r.speaker, r.start_ms, r.text) for r in rows]

        # 4. Understand: conversations and items, every citation checked.
        await _set_state(db, recording, ProcessingState.UNDERSTANDING)
        understood = await svc.brain.understand(lines)
        await _ai_cost(db, recording, understood.usage, svc)
        await _save_conversations(db, recording, rows, understood.value)
    else:
        await _save_conversations(db, recording, rows, [])

    # Done.
    await db.refresh(recording)
    recording.processing_state = ProcessingState.DONE.value
    recording.processing_error = None
    recording.processed_at = datetime.now(UTC)
    recording.results_version += 1
    await db.execute(
        update(Notification)
        .where(Notification.recording_id == recording.id, Notification.kind == "processing_failed")
        .values(read_at=func.now())
    )
    await db.commit()
    for day in {local_day(recording, 0), local_day(recording, recording.duration_ms or 0)}:
        await enqueue_digest(db, day)
    # The transcript and notes go to Drive too (and again whenever they change).
    from roudio.backup.job import enqueue_backup

    await enqueue_backup(db, recording)


def _groups(segments: list[tuple[int, int]]) -> list[list[tuple[int, int]]]:
    groups: list[list[tuple[int, int]]] = [[]]
    length = 0
    for start, end in segments:
        if groups[-1] and length + (end - start) > MAX_REQUEST_MS:
            groups.append([])
            length = 0
        groups[-1].append((start, end))
        length += end - start
    return [g for g in groups if g]


async def _save_conversations(
    db: AsyncSession,
    recording: Recording,
    rows: list[TranscriptSegment],
    understood: list[Understood],
) -> None:
    by_idx = {r.idx: r for r in rows}
    # Keep the owner's Review decisions across reprocessing, matched on the task's text.
    old_states: dict[str, tuple[str, str | None]] = {}
    rows_before = await db.execute(
        select(GeneratedItem.text, ReviewItem.state, ReviewItem.edited_text)
        .join(ReviewItem, ReviewItem.generated_item_id == GeneratedItem.id)
        .join(Conversation, Conversation.id == GeneratedItem.conversation_id)
        .where(Conversation.recording_id == recording.id)
    )
    for text, state, edited in rows_before.all():
        key = text.strip().lower()
        # The same task can appear twice; a decision the owner made beats "waiting".
        if key not in old_states or old_states[key][0] == ReviewState.WAITING:
            old_states[key] = (state, edited)
    await db.execute(delete(Conversation).where(Conversation.recording_id == recording.id))
    await db.flush()
    for c in sorted(understood, key=lambda c: c.start_idx):
        start, end = max(c.start_idx, 0), min(c.end_idx, len(rows) - 1)
        if start > end or start not in by_idx:
            continue
        inside = set(range(start, end + 1))
        conversation = Conversation(
            recording_id=recording.id,
            day=local_day(recording, by_idx[start].start_ms),
            start_ms=by_idx[start].start_ms,
            end_ms=by_idx[end].end_ms,
            kind=c.kind if c.kind in {"conversation", "event", "voice_note"} else "conversation",
            title=c.title.strip()[:120] or "Conversation",
            summary=c.summary.strip(),
            summary_sources=[i for i in c.summary_sources if i in inside] or [start],
        )
        db.add(conversation)
        await db.flush()
        for position, item in enumerate(c.items):
            sources = sorted({i for i in item.sources if i in inside})
            if not sources or item.kind not in {k.value for k in ItemKind} or not item.text.strip():
                continue  # a claim without a valid source is dropped (spec §7)
            generated = GeneratedItem(
                conversation_id=conversation.id,
                kind=item.kind,
                text=item.text.strip(),
                at_ms=by_idx[sources[0]].start_ms,
                sources=sources,
                position=position,
            )
            db.add(generated)
            if item.kind == ItemKind.TASK:
                await db.flush()
                state, edited = old_states.get(
                    generated.text.lower(), (ReviewState.WAITING.value, None)
                )
                db.add(ReviewItem(generated_item_id=generated.id, state=state, edited_text=edited))
    await db.commit()


async def _cost(
    db: AsyncSession, recording: Recording, service: str, units: Decimal, unit: str, usd: Decimal
) -> None:
    db.add(CostEntry(recording_id=recording.id, service=service, units=units, unit=unit, usd=usd))


async def _ai_cost(
    db: AsyncSession, recording: Recording | None, usage: Usage, svc: services.PipelineServices
) -> None:
    if not usage.input_tokens and not usage.output_tokens:
        return
    usd = (
        usage.input_tokens * svc.settings.openai_usd_per_million_input
        + usage.output_tokens * svc.settings.openai_usd_per_million_output
    ) / 1_000_000
    db.add(
        CostEntry(
            recording_id=recording.id if recording else None,
            service="openai",
            units=Decimal(usage.input_tokens + usage.output_tokens),
            unit="tokens",
            usd=Decimal(str(usd)),
        )
    )
