"""The daily digest (spec §5.5): "updating" while a day's recordings are still being written up,
"finished" once the day is over and nothing from it is waiting, "quiet" when nothing was said."""

import logging
from datetime import UTC, date, datetime, timedelta, timezone

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from roudio import jobs
from roudio.models import (
    Conversation,
    DayDigest,
    DigestState,
    GeneratedItem,
    Job,
    ProcessingState,
    Recording,
    TransferState,
)
from roudio.pipeline import services
from roudio.pipeline.brain import BrainError, BrainNotConfigured, DigestInput
from roudio.pipeline.process import DIGEST, PROCESS, _ai_cost, enqueue_digest, local_day

log = logging.getLogger(__name__)

TICK = "tick"


async def _recordings_on(db: AsyncSession, day: date) -> list[Recording]:
    # Recordings start at most a day before (5-hour files cross midnight); filter by local day.
    start = datetime.combine(day, datetime.min.time(), UTC) - timedelta(days=1)
    candidates = await db.scalars(
        select(Recording).where(
            Recording.started_at >= start, Recording.started_at < start + timedelta(days=3)
        )
    )
    return [r for r in candidates if local_day(r, 0) <= day <= local_day(r, r.duration_ms or 0)]


def _day_over(day: date, recordings: list[Recording], now: datetime) -> bool:
    offset = recordings[0].zone_offset_seconds if recordings else 0
    end = datetime.combine(
        day + timedelta(days=1), datetime.min.time(), timezone(timedelta(seconds=offset))
    )
    return now >= end


async def build_digest(db: AsyncSession, job: Job) -> None:
    day = date.fromisoformat(job.payload["day"])
    svc = services.get()
    recordings = await _recordings_on(db, day)
    done = [r for r in recordings if r.processing_state == ProcessingState.DONE]
    conversations = list(
        await db.scalars(
            select(Conversation).where(Conversation.day == day).order_by(Conversation.start_ms)
        )
    )
    conversations.sort(
        key=lambda c: next(
            (r.started_at for r in recordings if r.id == c.recording_id),
            datetime.min.replace(tzinfo=UTC),
        )
    )
    digest = await db.get(DayDigest, day) or DayDigest(day=day, state=DigestState.UPDATING.value)
    db.add(digest)
    digest.recordings_total = len(recordings)
    digest.recordings_done = len(done)
    finished = _day_over(day, recordings, datetime.now(UTC)) and len(done) == len(recordings)

    if not conversations:
        digest.state = DigestState.QUIET.value if finished else DigestState.UPDATING.value
        digest.overview = None
        digest.moments = []
        digest.error = None
        await db.commit()
        return

    items: dict[int, tuple[Conversation, GeneratedItem]] = {}
    inputs = []
    for c in conversations:
        rows = list(
            await db.scalars(
                select(GeneratedItem)
                .where(GeneratedItem.conversation_id == c.id)
                .order_by(GeneratedItem.position)
            )
        )
        for item in rows:
            items[item.id] = (c, item)
        inputs.append(DigestInput(c.title, c.summary, [(i.id, i.kind, i.text) for i in rows]))
    try:
        result = await svc.brain.digest(day.isoformat(), inputs)
    except BrainNotConfigured:
        digest.state = DigestState.UPDATING.value
        await db.commit()
        return
    except BrainError as exc:
        digest.state = DigestState.FAILED.value
        digest.error = "The digest couldn't be written. It will be tried again."
        await db.commit()
        raise exc
    await _ai_cost(db, None, result.usage, svc)
    digest.overview = result.value.overview.strip()
    digest.moments = [
        {
            "conversation_id": str(items[i][0].id),
            "item_id": i,
            "at_ms": items[i][1].at_ms,
            "text": items[i][1].text,
            "kind": items[i][1].kind,
        }
        for i in result.value.moment_item_ids
        if i in items
    ][:5]
    digest.state = DigestState.FINISHED.value if finished else DigestState.UPDATING.value
    digest.error = None
    await db.commit()


async def redo_stand_in_results(db: AsyncSession) -> None:
    """Once Scribe and OpenAI are configured, write up again what the stand-ins wrote."""
    if not services.get().real:
        return
    for recording in await db.scalars(
        select(Recording).where(
            Recording.processing_state == ProcessingState.DONE,
            Recording.written_by.is_distinct_from("real"),
        )
    ):
        log.info("recording %s was written up by the stand-ins; writing it up again", recording.id)
        recording.processing_state = ProcessingState.WAITING.value
        await db.commit()
        await jobs.ensure(db, PROCESS, {"recording_id": str(recording.id)})


async def tick(db: AsyncSession, job: Job) -> None:
    """Hourly: finish the digests of days that have ended, make sure every uploaded recording
    waiting to be written up or backed up has a job, then schedule the next tick."""
    now = datetime.now(UTC)
    await redo_stand_in_results(db)
    for recording in await db.scalars(
        select(Recording).where(
            Recording.transfer_state == TransferState.UPLOADED,
            Recording.processing_state == ProcessingState.WAITING,
        )
    ):
        await jobs.ensure(db, PROCESS, {"recording_id": str(recording.id)})
    # Backups too: recordings uploaded before Drive backup existed, whose job was lost, or that
    # failed (Drive was down, or switched off); a backup is safe to try again.
    from roudio.backup.job import BACKUP

    for recording in await db.scalars(
        select(Recording).where(
            Recording.transfer_state == TransferState.UPLOADED,
            Recording.backup_state.in_(["waiting", "failed"]),
        )
    ):
        await jobs.ensure(db, BACKUP, {"recording_id": str(recording.id)})
    for digest in await db.scalars(
        select(DayDigest).where(DayDigest.state == DigestState.UPDATING)
    ):
        if digest.day < now.date():
            await enqueue_digest(db, digest.day)
    await schedule_tick(db, now + timedelta(hours=1))


async def schedule_tick(db: AsyncSession, at: datetime) -> None:
    await jobs.enqueue(db, TICK, run_after=at, dedupe_key=f"tick:{at:%Y-%m-%dT%H}")


HANDLERS = {DIGEST: build_digest, TICK: tick}
