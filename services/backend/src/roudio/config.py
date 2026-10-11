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

    # The Railway bucket for original audio. Railway provides these as BUCKET, ENDPOINT, …
    # on the bucket; the services get them as AUDIO_* (see .railway/railway.ts).
    audio_bucket: str | None = None
    audio_endpoint: str | None = None
    audio_access_key_id: str | None = None
    audio_secret_access_key: SecretStr | None = None
    audio_region: str = "auto"
    audio_path_style: bool = False
    # Processing (spec §5). Without a key, recordings wait and are tried again every hour.
    elevenlabs_api_key: SecretStr | None = None
    elevenlabs_model: str = "scribe_v2"
    openai_api_key: SecretStr | None = None
    openai_model: str = "gpt-6.1-sol"
    # Testing only: a deployed server uses the stand-in transcriber and AI when keys are missing,
    # so the apps can be checked end to end. Their write-ups are obviously fake. Off by default.
    pipeline_fakes: bool = False
    # FireRedVAD weights (the Docker image downloads them here). Empty: a simple energy detector.
    vad_model_dir: str = ""
    # Tuned to miss as little speech as possible (spec §5): low threshold, padding, gaps merged.
    vad_threshold: float = 0.3
    vad_pad_ms: int = 300
    vad_merge_gap_ms: int = 1500
    vad_min_speech_ms: int = 250
    # Prices for the cost log (Oct 2026, decisions 5 and 8).
    scribe_usd_per_hour: float = 0.22
    scribe_keyterms_usd_per_hour: float = 0.05
    openai_usd_per_million_input: float = 2.0
    openai_usd_per_million_output: float = 10.0
    openai_usd_per_web_search: float = 0.01

    # Uploads arrive in parts of this size (S3 needs at least 5 MiB for all but the last).
    upload_part_bytes: int = 8 * 1024 * 1024

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
        """Refuse to start a deployed service with the development session secret.

        Google and Plaud settings may arrive later; until then the endpoints that need them
        answer "not set up yet" instead of the service crash-looping.
        """
        dev_secret = self.session_secret.get_secret_value().startswith("dev-only")
        if self.is_production_like and dev_secret:
            raise RuntimeError(f"SESSION_SECRET must be set for {self.environment}")


@lru_cache
def get_settings() -> Settings:
    return Settings()
