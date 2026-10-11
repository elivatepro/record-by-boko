import shutil
from datetime import UTC, date, datetime, timedelta
from pathlib import Path
from typing import Any

import pytest
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from roudio.models import (
    Conversation,
    CostEntry,
    DayDigest,
    GeneratedItem,
    Job,
    ReviewItem,
    SpeechSegment,
    TranscriptSegment,
    Word,
)
from roudio.pipeline.brain import BrainResult, FakeBrain, Item, Line, UnconfiguredBrain, Understood
from roudio.pipeline.digest import build_digest
from roudio.pipeline.process import PROCESS, process_recording
from tests.conftest import job_for, uploaded

pytestmark = pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="needs ffmpeg")


async def test_a_recording_is_found_transcribed_and_written_up(
    db: AsyncSession, s3: Any, audio_file: Path, pipeline: Any
) -> None:
    recording = await uploaded(db, s3, audio_file)
    db.add(Word(term="Roudio"))
    await db.commit()
    await process_recording(db, job_for(recording))
    await db.refresh(recording)

    assert recording.processing_state == "done"
    assert recording.results_version == 1
    speech = list(
        await db.scalars(select(SpeechSegment).where(SpeechSegment.recording_id == recording.id))
    )
    assert len(speech) == 2
    # The tones are at 2 to 5 s and 9 to 12 s; padding widens them a little.
    assert 1500 <= speech[0].start_ms <= 2100 and 4900 <= speech[0].end_ms <= 5400
    assert 8500 <= speech[1].start_ms <= 9100
    segments = list(
        await db.scalars(
            select(TranscriptSegment)
            .where(TranscriptSegment.recording_id == recording.id)
            .order_by(TranscriptSegment.idx)
        )
    )
    assert segments, "the fake transcriber's words become speaker turns"
    # Word times point back into the original file, inside the speech it heard.
    assert all(any(s.start_ms - 50 <= seg.start_ms <= s.end_ms for s in speech) for seg in segments)
    assert pipeline.transcriber.calls[0][1] == ["Roudio"], "the word list is sent as keyterms"
    conversations = list(
        await db.scalars(select(Conversation).where(Conversation.recording_id == recording.id))
    )
    assert conversations and conversations[0].day == date(2026, 10, 11)
    items = list(await db.scalars(select(GeneratedItem)))
    assert all(item.sources for item in items)
    tasks = await db.scalar(select(func.count()).select_from(ReviewItem))
    assert tasks == sum(1 for i in items if i.kind == "task")
    assert (
        await db.scalar(select(func.count()).select_from(Job).where(Job.kind == "build_digest"))
        == 1
    )


async def test_reprocessing_keeps_review_decisions(
    db: AsyncSession, s3: Any, audio_file: Path, pipeline: Any
) -> None:
    recording = await uploaded(db, s3, audio_file)
    await process_recording(db, job_for(recording))
    review = (await db.execute(select(ReviewItem, GeneratedItem).join(GeneratedItem))).first()
    assert review is not None
    review[0].state = "accepted"
    await db.commit()

    await process_recording(db, job_for(recording))
    states = list(await db.scalars(select(ReviewItem.state)))
    assert "accepted" in states
    await db.refresh(recording)
    assert recording.results_version == 2


async def test_claims_without_valid_sources_are_dropped(
    db: AsyncSession, s3: Any, audio_file: Path, pipeline: Any
) -> None:
    class Inventive(FakeBrain):
        async def understand(self, lines: list[Line]) -> BrainResult[list[Understood]]:
            last = lines[-1].idx
            return BrainResult(
                [
                    Understood(
                        0,
                        last,
                        "conversation",
                        "Call",
                        "Summary",
                        [999],
                        [
                            Item("decision", "Backed by the transcript", [0]),
                            Item("decision", "Made up", [999]),
                            Item("task", "Cites nothing", []),
                        ],
                    ),
                ]
            )

    pipeline.brain = Inventive()
    recording = await uploaded(db, s3, audio_file)
    await process_recording(db, job_for(recording))
    texts = list(await db.scalars(select(GeneratedItem.text)))
    assert texts == ["Backed by the transcript"]
    conversation = await db.scalar(select(Conversation))
    assert conversation is not None and conversation.summary_sources == [0]


async def test_without_a_key_it_waits_and_tries_again_later(
    db: AsyncSession, s3: Any, audio_file: Path, pipeline: Any
) -> None:
    pipeline.brain = UnconfiguredBrain()
    recording = await uploaded(db, s3, audio_file)
    await process_recording(db, job_for(recording))
    await db.refresh(recording)
    assert recording.processing_state == "waiting"
    assert "isn't set up yet" in (recording.processing_error or "")
    retry = await db.scalar(select(Job).where(Job.kind == PROCESS))
    assert retry is not None and retry.run_after > datetime.now(UTC) + timedelta(minutes=50)


async def test_a_failure_is_shown_with_a_plain_message(
    db: AsyncSession, s3: Any, audio_file: Path, pipeline: Any
) -> None:
    class Broken(FakeBrain):
        async def understand(self, lines: list[Line]) -> BrainResult[list[Understood]]:
            from roudio.pipeline.brain import BrainError

            raise BrainError("HTTP 500 from somewhere")

    pipeline.brain = Broken()
    recording = await uploaded(db, s3, audio_file)
    with pytest.raises(Exception, match="HTTP 500"):
        await process_recording(db, job_for(recording))
    await db.refresh(recording)
    assert recording.processing_state == "failed"
    assert recording.processing_error == "Writing up failed. It will be tried again."


async def test_the_digest_updates_then_finishes_after_the_day(
    db: AsyncSession, s3: Any, audio_file: Path, pipeline: Any
) -> None:
    today = datetime.now(UTC).replace(hour=8, minute=0, second=0, microsecond=0)
    recording = await uploaded(db, s3, audio_file, started=today)
    await process_recording(db, job_for(recording))
    day = (today + timedelta(hours=1)).date()
    await build_digest(
        db,
        Job(
            id=2, kind="build_digest", payload={"day": day.isoformat()}, attempts=1, max_attempts=8
        ),
    )
    digest = await db.get(DayDigest, day)
    assert digest is not None
    assert digest.state == "updating"
    assert digest.overview and digest.moments
    assert (digest.recordings_total, digest.recordings_done) == (1, 1)

    past = await uploaded(db, s3, audio_file, started=today - timedelta(days=3))
    await process_recording(db, job_for(past))
    old_day = (today - timedelta(days=3) + timedelta(hours=1)).date()
    await build_digest(
        db,
        Job(
            id=3,
            kind="build_digest",
            payload={"day": old_day.isoformat()},
            attempts=1,
            max_attempts=8,
        ),
    )
    finished = await db.get(DayDigest, old_day)
    assert finished is not None and finished.state == "finished"


async def test_costs_are_logged(db: AsyncSession, s3: Any, audio_file: Path, pipeline: Any) -> None:
    recording = await uploaded(db, s3, audio_file)
    await process_recording(db, job_for(recording))
    services_used = set(await db.scalars(select(CostEntry.service)))
    assert "scribe" in services_used


async def test_stand_in_results_are_written_up_again_once_the_real_services_exist(
    db: AsyncSession, s3: Any, audio_file: Path, pipeline: Any, monkeypatch: pytest.MonkeyPatch
) -> None:
    from roudio.pipeline import services
    from roudio.pipeline.digest import tick

    recording = await uploaded(db, s3, audio_file)
    await process_recording(db, job_for(recording))
    await db.refresh(recording)
    assert recording.written_by == "stand_in"

    await tick(db, job_for(recording))  # still the stand-ins: nothing to redo
    await db.refresh(recording)
    assert recording.processing_state == "done"

    monkeypatch.setattr(services.PipelineServices, "real", property(lambda _: True))
    await tick(db, job_for(recording))
    await db.refresh(recording)
    assert recording.processing_state == "waiting"
    queued = await db.scalar(
        select(func.count()).select_from(Job).where(Job.kind == PROCESS, Job.status == "queued")
    )
    assert queued == 1
