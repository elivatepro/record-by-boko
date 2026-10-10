"""Settings, read from environment variables (and `.env` locally)."""

from functools import lru_cache
from typing import Annotated

from pydantic import SecretStr, field_validator
from pydantic_settings import BaseSettings, NoDecode, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    environment: str = "development"
    database_url: str = "postgresql+psycopg://localhost/roudio"

    session_secret: SecretStr = SecretStr("dev-only-session-secret-change-me-0123456789")
    access_token_minutes: int = 15
    refresh_token_days: int = 60

    owner_email: str = "bokotomii@gmail.com"
    google_client_ids: Annotated[list[str], NoDecode] = []
    google_client_secret: SecretStr | None = None
    drive_token_key: SecretStr | None = None

    plaud_client_id: str | None = None
    plaud_client_secret: SecretStr | None = None
    plaud_api_base: str = "https://platform-us.plaud.ai/developer/api"
    # Plaud ties the recorder binding to this id. Never change it after linking.
    plaud_user_id: str = "roudio-owner"

    cors_origins: Annotated[list[str], NoDecode] = ["http://localhost:5173"]

    worker_poll_seconds: float = 2.0
    job_lease_minutes: int = 15

    @field_validator("google_client_ids", "cors_origins", mode="before")
    @classmethod
    def _split_csv(cls, value: object) -> object:
        if isinstance(value, str):
            return [part.strip() for part in value.split(",") if part.strip()]
        return value

    @field_validator("database_url")
    @classmethod
    def _use_psycopg(cls, value: str) -> str:
        # Railway hands out postgres:// or postgresql:// URLs; SQLAlchemy needs the driver named.
        for prefix in ("postgres://", "postgresql://"):
            if value.startswith(prefix):
                return "postgresql+psycopg://" + value.removeprefix(prefix)
        return value

    @field_validator("owner_email")
    @classmethod
    def _normalise_email(cls, value: str) -> str:
        return value.strip().lower()

    @property
    def is_production_like(self) -> bool:
        return self.environment in {"staging", "production"}

    def check_for_deploy(self) -> None:
        """Refuse to start a deployed service with development defaults."""
        if not self.is_production_like:
            return
        missing = []
        if self.session_secret.get_secret_value().startswith("dev-only"):
            missing.append("SESSION_SECRET")
        if not self.google_client_ids:
            missing.append("GOOGLE_CLIENT_IDS")
        if missing:
            raise RuntimeError(f"Missing settings for {self.environment}: {', '.join(missing)}")


@lru_cache
def get_settings() -> Settings:
    return Settings()
