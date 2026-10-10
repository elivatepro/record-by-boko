"""The `api` service."""

import logging

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.routing import APIRoute
from pydantic import BaseModel
from sqlalchemy import text

from roudio import __version__
from roudio.api import auth, plaud
from roudio.api.deps import Db, Services
from roudio.auth.crypto import TokenCipher
from roudio.auth.google import HttpDriveCodeExchanger, HttpGoogleVerifier
from roudio.config import Settings, get_settings
from roudio.plaud import FakePlaudAuth, HttpPlaudAuth, PlaudAuth

log = logging.getLogger(__name__)


class Health(BaseModel):
    status: str
    version: str
    database: str


def build_services(settings: Settings) -> Services:
    plaud_auth: PlaudAuth
    if settings.plaud_client_id and settings.plaud_client_secret:
        plaud_auth = HttpPlaudAuth(
            client_id=settings.plaud_client_id,
            client_secret=settings.plaud_client_secret.get_secret_value(),
            user_id=settings.plaud_user_id,
            api_base=settings.plaud_api_base,
        )
    elif settings.is_production_like:
        raise RuntimeError("PLAUD_CLIENT_ID and PLAUD_CLIENT_SECRET must be set")
    else:
        log.warning("No Plaud credentials; using the fake Plaud token")
        plaud_auth = FakePlaudAuth()

    drive = None
    if settings.google_client_ids and settings.google_client_secret:
        drive = HttpDriveCodeExchanger(
            settings.google_client_ids[0], settings.google_client_secret.get_secret_value()
        )
    cipher = None
    if settings.drive_token_key:
        cipher = TokenCipher(settings.drive_token_key.get_secret_value())
    return Services(
        google=HttpGoogleVerifier(settings.google_client_ids),
        drive=drive,
        cipher=cipher,
        plaud=plaud_auth,
    )


def _operation_id(route: APIRoute) -> str:
    return route.name


def create_app(settings: Settings | None = None, services: Services | None = None) -> FastAPI:
    settings = settings or get_settings()
    settings.check_for_deploy()
    app = FastAPI(
        title="Roudio API",
        version=__version__,
        generate_unique_id_function=_operation_id,
    )
    app.state.services = services or build_services(settings)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origins,
        allow_methods=["*"],
        allow_headers=["Authorization", "Content-Type"],
    )

    @app.get("/health", operation_id="getHealth", tags=["health"])
    async def health(db: Db) -> Health:
        try:
            await db.execute(text("SELECT 1"))
            database = "ok"
        except Exception:
            log.exception("health check: database unavailable")
            database = "unavailable"
        return Health(status="ok", version=__version__, database=database)

    app.include_router(auth.router)
    app.include_router(plaud.router)
    return app
