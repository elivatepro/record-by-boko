from datetime import UTC, datetime, timedelta

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from roudio import jobs
from roudio.db import get_sessionmaker
from roudio.models import Job, JobStatus
from roudio.worker.loop import PermanentJobError, run_once


async def test_enqueue_with_dedupe_key_returns_existing_job(db: AsyncSession) -> None:
    first = await jobs.enqueue(db, "ping", {"n": 1}, dedupe_key="rec-1:transcribe")
    second = await jobs.enqueue(db, "ping", {"n": 2}, dedupe_key="rec-1:transcribe")
    assert second.id == first.id
    assert second.payload == {"n": 1}


async def test_two_workers_never_claim_the_same_job(db: AsyncSession) -> None:
    await jobs.enqueue(db, "ping")
    maker = get_sessionmaker()
    async with maker() as a, maker() as b:
        claimed_a = await jobs.claim(a, "worker-a")
        claimed_b = await jobs.claim(b, "worker-b")
    assert claimed_a is not None
    assert claimed_b is None
    assert claimed_a.status == JobStatus.RUNNING
    assert claimed_a.attempts == 1


async def test_future_jobs_are_not_claimed(db: AsyncSession) -> None:
    await jobs.enqueue(db, "ping", run_after=datetime.now(UTC) + timedelta(minutes=5))
    assert await jobs.claim(db, "w") is None


async def test_successful_job_is_marked_succeeded(db: AsyncSession) -> None:
    seen = []

    async def handler(_: AsyncSession, job: Job) -> None:
        seen.append(job.payload)

    job = await jobs.enqueue(db, "echo", {"hello": "world"})
    assert await run_once(get_sessionmaker(), {"echo": handler}, "w")
    await db.refresh(job)
    assert seen == [{"hello": "world"}]
    assert job.status == JobStatus.SUCCEEDED
    assert job.finished_at is not None


async def test_failed_job_retries_with_backoff_then_fails(db: AsyncSession) -> None:
    async def handler(_: AsyncSession, __: Job) -> None:
        raise RuntimeError("Scribe timed out")

    job = await jobs.enqueue(db, "flaky", max_attempts=2)
    await run_once(get_sessionmaker(), {"flaky": handler}, "w")
    await db.refresh(job)
    assert job.status == JobStatus.QUEUED
    assert job.last_error == "RuntimeError: Scribe timed out"
    assert job.run_after > datetime.now(UTC) + timedelta(seconds=20)

    # Make it due again and fail the last attempt.
    await db.execute(update(Job).where(Job.id == job.id).values(run_after=datetime.now(UTC)))
    await db.commit()
    await run_once(get_sessionmaker(), {"flaky": handler}, "w")
    await db.refresh(job)
    assert job.status == JobStatus.FAILED
    assert job.attempts == 2


async def test_permanent_error_does_not_retry(db: AsyncSession) -> None:
    async def handler(_: AsyncSession, __: Job) -> None:
        raise PermanentJobError("recording no longer exists")

    job = await jobs.enqueue(db, "doomed")
    await run_once(get_sessionmaker(), {"doomed": handler}, "w")
    await db.refresh(job)
    assert job.status == JobStatus.FAILED
    assert job.attempts == 1


async def test_unknown_kind_fails_without_retry(db: AsyncSession) -> None:
    job = await jobs.enqueue(db, "nobody-handles-this")
    await run_once(get_sessionmaker(), {}, "w")
    await db.refresh(job)
    assert job.status == JobStatus.FAILED


async def test_stale_running_job_is_requeued(db: AsyncSession) -> None:
    job = await jobs.enqueue(db, "ping")
    assert await jobs.claim(db, "crashed-worker") is not None
    await db.execute(
        update(Job).where(Job.id == job.id).values(locked_at=datetime.now(UTC) - timedelta(hours=1))
    )
    await db.commit()
    assert await jobs.requeue_stale(db, timedelta(minutes=15)) == 1
    await db.refresh(job)
    assert job.status == JobStatus.QUEUED
    assert job.locked_by is None


def test_backoff_doubles_and_caps() -> None:
    assert jobs.backoff(1) == timedelta(seconds=30)
    assert jobs.backoff(2) == timedelta(minutes=1)
    assert jobs.backoff(20) == timedelta(hours=1)


async def test_a_handler_that_rolls_back_and_returns_still_completes(db: AsyncSession) -> None:
    """Handlers that roll back (expiring every object, the job included) must not break the loop."""

    async def rolls_back(session: AsyncSession, job: Job) -> None:
        await session.rollback()

    await jobs.enqueue(db, "rollback")
    assert await run_once(get_sessionmaker(), {"rollback": rolls_back}, "worker-a")
    job = await db.scalar(select(Job).where(Job.kind == "rollback"))
    assert job is not None and job.status == JobStatus.SUCCEEDED
