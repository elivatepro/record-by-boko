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
        from roudio.pipeline.digest import on_worker_start, schedule_tick

        stop = asyncio.Event()
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            loop.add_signal_handler(sig, stop.set)
        await _wait_for_migrations()
        async with get_sessionmaker()() as db:
            await on_worker_start(db)
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


def agent_key() -> None:
    """Make, list or revoke agent keys for /agent/v1 (docs/architecture/agent-feed.md).

    uv run roudio-agent-key create root --scopes read,annotate [--key-file PATH]
    uv run roudio-agent-key list
    uv run roudio-agent-key revoke root
    """
    import argparse
    from datetime import UTC, datetime

    from sqlalchemy import select

    from roudio.agent.keys import create_key
    from roudio.db import get_sessionmaker
    from roudio.models import AgentKey

    parser = argparse.ArgumentParser(prog="roudio-agent-key", description=agent_key.__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    make = commands.add_parser("create", help="make a key; it's shown once, only its hash is kept")
    make.add_argument("name")
    make.add_argument("--scopes", default="read", help="comma-separated: read, annotate")
    make.add_argument(
        "--key-file",
        type=Path,
        help="write the key to this new file (mode 600) instead of printing it",
    )
    commands.add_parser("list", help="names, scopes and last use (never the keys)")
    revoke = commands.add_parser("revoke", help="stop a name's keys working")
    revoke.add_argument("name")
    args = parser.parse_args()

    async def main() -> None:
        async with get_sessionmaker()() as db:
            if args.command == "create":
                if args.key_file:
                    # Refuse to overwrite, and never let the key be readable by others.
                    fd = os.open(args.key_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                try:
                    row, plain = await create_key(db, args.name, args.scopes.split(","))
                except Exception:
                    if args.key_file:
                        os.close(fd)
                        args.key_file.unlink()
                    raise
                if args.key_file:
                    with os.fdopen(fd, "w") as out:
                        out.write(plain + "\n")
                    print(
                        f"Made key {row.id} for {row.name} ({','.join(row.scopes)});"
                        f" written to {args.key_file}"
                    )
                else:
                    print(f"Made key {row.id} for {row.name} ({','.join(row.scopes)}).")
                    print("It won't be shown again:")
                    print(plain)
            elif args.command == "list":
                for k in await db.scalars(select(AgentKey).order_by(AgentKey.id)):
                    state = f"revoked {k.revoked_at:%Y-%m-%d}" if k.revoked_at else "active"
                    used = f"{k.last_used_at:%Y-%m-%d %H:%M}" if k.last_used_at else "never"
                    print(f"{k.id}\t{k.name}\t{','.join(k.scopes)}\t{state}\tlast used {used}")
            else:
                rows = list(
                    await db.scalars(
                        select(AgentKey).where(
                            AgentKey.name == args.name, AgentKey.revoked_at.is_(None)
                        )
                    )
                )
                for k in rows:
                    k.revoked_at = datetime.now(UTC)
                await db.commit()
                print(f"Revoked {len(rows)} key(s) for {args.name}")

    asyncio.run(main())
