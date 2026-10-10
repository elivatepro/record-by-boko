"""Postgres-backed job queue (decision 16).

Workers claim jobs with `FOR UPDATE SKIP LOCKED`, so any number of workers can run side by side.
A job that throws is retried with exponential back-off until `max_attempts`, then marked failed.
A job whose worker vanished (for example a Railway restart) is requeued once its lease expires,
which is why every handler must be safe to run twice.
"""

from datetime import UTC, datetime, timedelta
from typing import Any

from sqlalchemy import CursorResult, select, text, update
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession

from roudio.models import Job, JobStatus

BACKOFF_BASE = timedelta(seconds=30)
BACKOFF_MAX = timedelta(hours=1)


def backoff(attempts: int) -> timedelta:
    """Delay before the next try, after `attempts` failed tries: 30 s, 1 min, 2 min … 1 h."""
    delay: timedelta = BACKOFF_BASE * (2 ** max(attempts - 1, 0))
    return min(delay, BACKOFF_MAX)


async def enqueue(
    db: AsyncSession,
    kind: str,
    payload: dict[str, Any] | None = None,
    *,
    dedupe_key: str | None = None,
    max_attempts: int = 8,
    run_after: datetime | None = None,
) -> Job:
    """Add a job. With a `dedupe_key`, an existing job with that key is returned instead."""
    values: dict[str, Any] = {
        "kind": kind,
        "payload": payload or {},
        "dedupe_key": dedupe_key,
        "max_attempts": max_attempts,
        "status": JobStatus.QUEUED,
    }
    if run_after is not None:
        values["run_after"] = run_after
    stmt = insert(Job).values(**values)
    if dedupe_key is not None:
        stmt = stmt.on_conflict_do_nothing(index_elements=[Job.dedupe_key])
    job = (await db.execute(stmt.returning(Job))).scalar_one_or_none()
    if job is None:
        job = (await db.execute(select(Job).where(Job.dedupe_key == dedupe_key))).scalar_one()
    await db.commit()
    return job


_CLAIM = text(
    """
    UPDATE job
       SET status = 'running', locked_by = :worker, locked_at = now(),
           attempts = attempts + 1, updated_at = now()
     WHERE id = (
           SELECT id FROM job
            WHERE status = 'queued' AND run_after <= now()
            ORDER BY run_after, id
            FOR UPDATE SKIP LOCKED
            LIMIT 1)
    RETURNING id
    """
)


async def claim(db: AsyncSession, worker: str) -> Job | None:
    """Take the next ready job, or return None if there isn't one."""
    job_id = (await db.execute(_CLAIM, {"worker": worker})).scalar_one_or_none()
    await db.commit()
    if job_id is None:
        return None
    return await db.get(Job, job_id, populate_existing=True)


async def complete(db: AsyncSession, job: Job) -> None:
    await db.execute(
        update(Job)
        .where(Job.id == job.id)
        .values(status=JobStatus.SUCCEEDED, finished_at=datetime.now(UTC), last_error=None)
    )
    await db.commit()


async def fail(db: AsyncSession, job: Job, error: str, *, retry: bool = True) -> None:
    """Record a failure. Retries later unless attempts are used up or `retry` is False."""
    values: dict[str, Any] = {"last_error": error[:4000], "locked_by": None, "locked_at": None}
    if retry and job.attempts < job.max_attempts:
        values |= {
            "status": JobStatus.QUEUED,
            "run_after": datetime.now(UTC) + backoff(job.attempts),
        }
    else:
        values |= {"status": JobStatus.FAILED, "finished_at": datetime.now(UTC)}
    await db.execute(update(Job).where(Job.id == job.id).values(**values))
    await db.commit()


async def heartbeat(db: AsyncSession, job: Job) -> None:
    """Extend a long-running job's lease so it isn't requeued underneath it."""
    await db.execute(
        update(Job)
        .where(Job.id == job.id, Job.status == JobStatus.RUNNING)
        .values(locked_at=datetime.now(UTC))
    )
    await db.commit()


async def requeue_stale(db: AsyncSession, lease: timedelta) -> int:
    """Put back jobs whose worker stopped heartbeating. Returns how many were requeued."""
    result: CursorResult[Any] = await db.execute(  # type: ignore[assignment]
        update(Job)
        .where(Job.status == JobStatus.RUNNING, Job.locked_at < datetime.now(UTC) - lease)
        .values(
            status=JobStatus.QUEUED,
            locked_by=None,
            locked_at=None,
            last_error="Worker stopped before finishing; requeued.",
        )
    )
    await db.commit()
    return result.rowcount or 0
