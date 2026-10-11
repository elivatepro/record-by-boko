"""Entry points: `roudio-api`, `roudio-worker` and `roudio-openapi`."""

import asyncio
import json
import logging
import os
import signal
import sys
from datetime import timedelta
from pathlib import Path

import uvicorn

from roudio.config import get_settings


def _logging() -> None:
    logging.basicConfig(
        level=os.environ.get("LOG_LEVEL", "INFO"),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )


def api() -> None:
    _logging()
    uvicorn.run(
        "roudio.api.app:create_app",
        factory=True,
        host="0.0.0.0",  # noqa: S104 (Railway routes to the container)
        port=int(os.environ.get("PORT", "8000")),
        proxy_headers=True,
        forwarded_allow_ips="*",
    )


def worker() -> None:
    _logging()
    from datetime import UTC, datetime

    from roudio.db import get_sessionmaker
    from roudio.worker.handlers import all_handlers
    from roudio.worker.loop import run_forever

    settings = get_settings()
    settings.check_for_deploy()

    async def main() -> None:
        from roudio.pipeline.digest import redo_stand_in_results, schedule_tick

        stop = asyncio.Event()
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            loop.add_signal_handler(sig, stop.set)
        await _wait_for_migrations()
        async with get_sessionmaker()() as db:
            await redo_stand_in_results(db)
            await schedule_tick(db, datetime.now(UTC))
        await run_forever(
            get_sessionmaker(),
            handlers=all_handlers(),
            poll_seconds=settings.worker_poll_seconds,
            lease=timedelta(minutes=settings.job_lease_minutes),
            stop=stop,
        )

    asyncio.run(main())


async def _wait_for_migrations(timeout_s: float = 600) -> None:
    """The api's pre-deploy step migrates the database; the worker starts alongside it, so wait
    until the database is at the newest migration before touching new tables."""
    from alembic.config import Config
    from alembic.script import ScriptDirectory
    from sqlalchemy import text

    from roudio.db import get_engine

    head = ScriptDirectory.from_config(Config("alembic.ini")).get_current_head()
    log = logging.getLogger("roudio.worker")
    waited = 0.0
    while waited < timeout_s:
        try:
            async with get_engine().connect() as conn:
                current = (
                    await conn.execute(text("SELECT version_num FROM alembic_version"))
                ).scalar()
            if current == head:
                return
            log.info("waiting for migrations: database at %s, code at %s", current, head)
        except Exception as exc:  # the database may still be starting
            log.info("waiting for the database: %s", exc)
        await asyncio.sleep(5)
        waited += 5
    log.warning("database still isn't at %s; starting anyway", head)


def reprocess() -> None:
    """Write every uploaded recording up again (after adding or changing the AI keys)."""
    _logging()
    from sqlalchemy import select

    from roudio import jobs
    from roudio.db import get_sessionmaker
    from roudio.models import Recording
    from roudio.pipeline.process import PROCESS

    async def main() -> None:
        async with get_sessionmaker()() as db:
            rows = list(
                await db.scalars(select(Recording).where(Recording.transfer_state == "uploaded"))
            )
            for recording in rows:
                recording.processing_state = "waiting"
                await jobs.enqueue(db, PROCESS, {"recording_id": str(recording.id)})
            print(f"Queued {len(rows)} recording(s) for processing")

    asyncio.run(main())


def openapi() -> None:
    """Write the OpenAPI description to the path given (default: packages/api-spec)."""
    from roudio.api.app import create_app
    from roudio.api.deps import Services
    from roudio.auth.google import GoogleIdentity
    from roudio.plaud import FakePlaudAuth
    from roudio.storage import UnconfiguredAudioStore

    class _Unused:
        async def verify(self, id_token: str) -> GoogleIdentity:
            raise NotImplementedError

    services = Services(
        google=_Unused(),
        drive=None,
        cipher=None,
        plaud=FakePlaudAuth(),
        audio=UnconfiguredAudioStore(),
    )
    app = create_app(services=services)
    default = Path(__file__).resolve().parents[4] / "packages" / "api-spec" / "openapi.json"
    target = Path(sys.argv[1]) if len(sys.argv) > 1 else default
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(app.openapi(), indent=2, sort_keys=False) + "\n")
    print(f"Wrote {target}")
