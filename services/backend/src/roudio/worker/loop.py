"""The worker loop: claim a job, run its handler, record the outcome, repeat."""

import asyncio
import logging
import os
import socket
from collections.abc import Awaitable, Callable, Mapping
from datetime import timedelta
from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from roudio import jobs
from roudio.models import Job

log = logging.getLogger(__name__)

Handler = Callable[[AsyncSession, Job], Awaitable[None]]


class PermanentJobError(Exception):
    """Raise from a handler when retrying can't help (bad payload, missing recording…)."""


async def _ping(db: AsyncSession, job: Job) -> None:
    log.info("ping job %s: %s", job.id, job.payload)


# Processing steps (find speech, transcribe, tidy, …) register here as they are built.
HANDLERS: dict[str, Handler] = {"ping": _ping}


def worker_name() -> str:
    return f"{socket.gethostname()}:{os.getpid()}"


async def run_once(
    sessionmaker: async_sessionmaker[AsyncSession],
    handlers: Mapping[str, Handler],
    worker: str,
) -> bool:
    """Run at most one job. Returns True if a job was claimed."""
    async with sessionmaker() as db:
        job = await jobs.claim(db, worker)
        if job is None:
            return False
        handler = handlers.get(job.kind)
        if handler is None:
            await jobs.fail(db, job, f"No handler for job kind {job.kind!r}", retry=False)
            return True
        try:
            await handler(db, job)
        except PermanentJobError as exc:
            # Rolling back drops the handler's partial writes and expires `job`; reload it.
            await db.rollback()
            await db.refresh(job)
            log.warning("job %s (%s) failed permanently: %s", job.id, job.kind, exc)
            await jobs.fail(db, job, str(exc), retry=False)
        except Exception as exc:
            await db.rollback()
            await db.refresh(job)
            log.exception("job %s (%s) failed on attempt %s", job.id, job.kind, job.attempts)
            await jobs.fail(db, job, f"{type(exc).__name__}: {exc}")
        else:
            await jobs.complete(db, job)
        return True


async def run_forever(
    sessionmaker: async_sessionmaker[AsyncSession],
    *,
    handlers: Mapping[str, Handler] = HANDLERS,
    poll_seconds: float = 2.0,
    lease: timedelta = timedelta(minutes=15),
    stop: asyncio.Event | None = None,
) -> None:
    stop = stop or asyncio.Event()
    worker = worker_name()
    log.info("worker %s started with handlers: %s", worker, ", ".join(sorted(handlers)))
    while not stop.is_set():
        try:
            async with sessionmaker() as db:
                if requeued := await jobs.requeue_stale(db, lease):
                    log.warning("requeued %s stale job(s)", requeued)
            busy = await run_once(sessionmaker, handlers, worker)
        except Exception:
            # The database may be restarting; wait and carry on rather than crash-looping.
            log.exception("worker loop error")
            busy = False
        if not busy:
            try:
                await asyncio.wait_for(stop.wait(), timeout=poll_seconds)
            except TimeoutError:
                pass
    log.info("worker %s stopped", worker)


__all__: list[Any] = ["HANDLERS", "Handler", "PermanentJobError", "run_forever", "run_once"]
