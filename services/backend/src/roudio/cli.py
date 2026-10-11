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
    from roudio.db import get_sessionmaker
    from roudio.worker.loop import run_forever

    settings = get_settings()
    settings.check_for_deploy()

    async def main() -> None:
        stop = asyncio.Event()
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            loop.add_signal_handler(sig, stop.set)
        await run_forever(
            get_sessionmaker(),
            poll_seconds=settings.worker_poll_seconds,
            lease=timedelta(minutes=settings.job_lease_minutes),
            stop=stop,
        )

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
