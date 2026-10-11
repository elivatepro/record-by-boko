"""Database tables. Change these together with a migration in `migrations/versions`."""

from datetime import date, datetime
from decimal import Decimal
from enum import StrEnum
from typing import Any
from uuid import UUID, uuid4

from sqlalchemy import (
    BigInteger,
    Boolean,
    CheckConstraint,
    Computed,
    Date,
    DateTime,
    ForeignKey,
    Identity,
    Index,
    Integer,
    Numeric,
    String,
    Text,
    UniqueConstraint,
    func,
    text,
)
from sqlalchemy import text as sql_text  # models below have a column called `text`
from sqlalchemy.dialects.postgresql import ARRAY, JSONB, TSVECTOR
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class Owner(Base):
    """The single person allowed to use Roudio. The Google `sub` is pinned on first sign-in."""

    __tablename__ = "owner"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    email: Mapped[str] = mapped_column(String(320), unique=True)
    google_sub: Mapped[str] = mapped_column(String(255), unique=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class AuthSession(Base):
    """A signed-in device. Only a hash of the refresh token is stored."""

    __tablename__ = "auth_session"

    id: Mapped[UUID] = mapped_column(primary_key=True, default=uuid4)
    owner_id: Mapped[int] = mapped_column(ForeignKey("owner.id", ondelete="CASCADE"))
    client: Mapped[str] = mapped_column(String(16))
    refresh_hash: Mapped[str] = mapped_column(String(64), unique=True)
    # The hash it replaced. Presenting it again means the token leaked, so the session is revoked.
    previous_refresh_hash: Mapped[str | None] = mapped_column(String(64), index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    last_used_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class DriveGrant(Base):
    """The owner's offline Google Drive grant (`drive.file`), refresh token encrypted at rest."""

    __tablename__ = "drive_grant"

    owner_id: Mapped[int] = mapped_column(
        ForeignKey("owner.id", ondelete="CASCADE"), primary_key=True
    )
    encrypted_refresh_token: Mapped[str] = mapped_column(Text)
    scopes: Mapped[str] = mapped_column(Text)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )


class JobStatus(StrEnum):
    QUEUED = "queued"
    RUNNING = "running"
    SUCCEEDED = "succeeded"
    FAILED = "failed"


class Job(Base):
    """A unit of background work. Handlers must be safe to run twice."""

    __tablename__ = "job"
    __table_args__ = (
        CheckConstraint(
            "status IN ('queued', 'running', 'succeeded', 'failed')", name="ck_job_status"
        ),
        Index("ix_job_ready", "run_after", "id", postgresql_where="status = 'queued'"),
        Index("ix_job_running_locked_at", "locked_at", postgresql_where="status = 'running'"),
    )

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    kind: Mapped[str] = mapped_column(String(64))
    payload: Mapped[dict[str, Any]] = mapped_column(JSONB, server_default=text("'{}'::jsonb"))
    # Enqueueing the same key twice returns the existing job instead of adding another.
    dedupe_key: Mapped[str | None] = mapped_column(String(255), unique=True)
    status: Mapped[str] = mapped_column(String(16), server_default=JobStatus.QUEUED.value)
    attempts: Mapped[int] = mapped_column(Integer, server_default="0")
    max_attempts: Mapped[int] = mapped_column(Integer, server_default="8")
    run_after: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    locked_by: Mapped[str | None] = mapped_column(String(128))
    locked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    last_error: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class TransferState(StrEnum):
    """The server's half of the transfer track (spec §3): the phone does the steps before."""

    UPLOADING = "uploading"
    UPLOADED = "uploaded"


class Recording(Base):
    """One file from the recorder. Its audio is in the bucket under `storage_key`.

    The track states are separate columns: "done processing" never implies "backed up".
    """

    __tablename__ = "recording"
    __table_args__ = (
        UniqueConstraint("recorder_serial", "session_id", name="uq_recording_recorder_session"),
        CheckConstraint(
            "transfer_state IN ('uploading', 'uploaded')", name="ck_recording_transfer_state"
        ),
    )

    id: Mapped[UUID] = mapped_column(primary_key=True, default=uuid4)
    recorder_serial: Mapped[str] = mapped_column(String(32))
    # Plaud's id for the file on the recorder: its start in Unix seconds.
    session_id: Mapped[int] = mapped_column(BigInteger)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    # The phone's UTC offset when it was recorded, so days and the dial stay right.
    zone_offset_seconds: Mapped[int] = mapped_column(Integer)
    duration_ms: Mapped[int | None] = mapped_column(BigInteger)
    size_bytes: Mapped[int] = mapped_column(BigInteger)
    # SHA-256 the phone computed. The upload only counts once the stored file matches it.
    sha256: Mapped[str] = mapped_column(String(64))
    storage_key: Mapped[str] = mapped_column(Text)
    # The bucket's multipart upload while parts are arriving.
    upload_id: Mapped[str | None] = mapped_column(Text)
    transfer_state: Mapped[str] = mapped_column(
        String(16), server_default=TransferState.UPLOADING.value
    )
    processing_state: Mapped[str] = mapped_column(String(16), server_default="waiting")
    backup_state: Mapped[str] = mapped_column(String(16), server_default="waiting")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    uploaded_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )

    # Processing (spec §5). The error says what went wrong, in plain words, while failed.
    processing_error: Mapped[str | None] = mapped_column(Text)
    processed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    language: Mapped[str | None] = mapped_column(String(16))
    speech_ms: Mapped[int | None] = mapped_column(BigInteger)
    # Bumped whenever processed results are replaced, so the phone knows to fetch them again.
    results_version: Mapped[int] = mapped_column(Integer, server_default="0")
    # "real" (Scribe and OpenAI) or "stand_in" (the fakes); stand-in results are written up
    # again once the real services are configured.
    written_by: Mapped[str | None] = mapped_column(String(16))

    # Drive backup (spec §8).
    backup_error: Mapped[str | None] = mapped_column(Text)
    backed_up_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    # Clean-up of the recorder copy: blocked / can_clean_up / deleting / cleared.
    cleanup_state: Mapped[str] = mapped_column(String(16), server_default="blocked")


class ProcessingState(StrEnum):
    WAITING = "waiting"
    FINDING_SPEECH = "finding_speech"
    TRANSCRIBING = "transcribing"
    TIDYING = "tidying"
    UNDERSTANDING = "understanding"
    DONE = "done"
    FAILED = "failed"


class BackupState(StrEnum):
    WAITING = "waiting"
    UPLOADING = "uploading"
    CHECKING = "checking"
    BACKED_UP = "backed_up"
    FAILED = "failed"


class SpeechSegment(Base):
    """Where the speech detector heard speech, as offsets in the original file."""

    __tablename__ = "speech_segment"

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    recording_id: Mapped[UUID] = mapped_column(
        ForeignKey("recording.id", ondelete="CASCADE"), index=True
    )
    start_ms: Mapped[int] = mapped_column(BigInteger)
    end_ms: Mapped[int] = mapped_column(BigInteger)
    # Music is flagged, not dropped (spec §5).
    music: Mapped[bool] = mapped_column(Boolean, server_default="false")


class Speaker(Base):
    """A voice Scribe told apart, and the owner's name for it."""

    __tablename__ = "speaker"
    __table_args__ = (UniqueConstraint("recording_id", "label", name="uq_speaker_label"),)

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    recording_id: Mapped[UUID] = mapped_column(
        ForeignKey("recording.id", ondelete="CASCADE"), index=True
    )
    label: Mapped[str] = mapped_column(String(32))
    name: Mapped[str | None] = mapped_column(String(120))


class TranscriptSegment(Base):
    """One speaker turn. Times are offsets in the original file.

    `original_text` is what Scribe heard; `text` is what's shown (tidied, then the owner's edit).
    `corrections` lists the tidy pass's flagged changes, so nothing is silently rewritten.
    """

    __tablename__ = "transcript_segment"
    __table_args__ = (
        UniqueConstraint("recording_id", "idx", name="uq_transcript_segment_idx"),
        Index("ix_transcript_segment_search", "search", postgresql_using="gin"),
    )

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    recording_id: Mapped[UUID] = mapped_column(
        ForeignKey("recording.id", ondelete="CASCADE"), index=True
    )
    idx: Mapped[int] = mapped_column(Integer)
    speaker: Mapped[str] = mapped_column(String(32))
    start_ms: Mapped[int] = mapped_column(BigInteger)
    end_ms: Mapped[int] = mapped_column(BigInteger)
    text: Mapped[str] = mapped_column(Text)
    original_text: Mapped[str] = mapped_column(Text)
    corrections: Mapped[list[dict[str, Any]]] = mapped_column(
        JSONB, server_default=sql_text("'[]'::jsonb")
    )
    words: Mapped[list[dict[str, Any]]] = mapped_column(
        JSONB, server_default=sql_text("'[]'::jsonb")
    )
    language: Mapped[str | None] = mapped_column(String(16))
    edited_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    search: Mapped[str] = mapped_column(
        TSVECTOR, Computed("to_tsvector('simple', coalesce(text, ''))", persisted=True)
    )


class ConversationKind(StrEnum):
    CONVERSATION = "conversation"
    EVENT = "event"
    VOICE_NOTE = "voice_note"


class Conversation(Base):
    """A conversation or event inside one recording: what Today and the Recording screen show."""

    __tablename__ = "conversation"
    __table_args__ = (Index("ix_conversation_search", "search", postgresql_using="gin"),)

    id: Mapped[UUID] = mapped_column(primary_key=True, default=uuid4)
    recording_id: Mapped[UUID] = mapped_column(
        ForeignKey("recording.id", ondelete="CASCADE"), index=True
    )
    # The local day it started on (the recording's start plus its UTC offset).
    day: Mapped[date] = mapped_column(Date, index=True)
    start_ms: Mapped[int] = mapped_column(BigInteger)
    end_ms: Mapped[int] = mapped_column(BigInteger)
    kind: Mapped[str] = mapped_column(String(16))
    title: Mapped[str] = mapped_column(Text)
    summary: Mapped[str] = mapped_column(Text)
    # Transcript segment idx values the summary comes from (its tappable times).
    summary_sources: Mapped[list[int]] = mapped_column(ARRAY(Integer), server_default="{}")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    search: Mapped[str] = mapped_column(
        TSVECTOR,
        Computed(
            "to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(summary, ''))",
            persisted=True,
        ),
    )


class ItemKind(StrEnum):
    DECISION = "decision"
    TASK = "task"
    KEY_MOMENT = "key_moment"


class GeneratedItem(Base):
    """A decision, suggested task or key moment, always pointing at its source segments."""

    __tablename__ = "generated_item"

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    conversation_id: Mapped[UUID] = mapped_column(
        ForeignKey("conversation.id", ondelete="CASCADE"), index=True
    )
    kind: Mapped[str] = mapped_column(String(16))
    text: Mapped[str] = mapped_column(Text)
    at_ms: Mapped[int] = mapped_column(BigInteger)
    sources: Mapped[list[int]] = mapped_column(ARRAY(Integer))
    position: Mapped[int] = mapped_column(Integer, server_default="0")


class ReviewState(StrEnum):
    WAITING = "waiting"
    ACCEPTED = "accepted"
    DONE = "done"
    DISMISSED = "dismissed"


class ReviewItem(Base):
    """A suggested task in the Review list. It outlives reprocessing via (conversation, text)."""

    __tablename__ = "review_item"

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    generated_item_id: Mapped[int] = mapped_column(
        ForeignKey("generated_item.id", ondelete="CASCADE"), unique=True
    )
    state: Mapped[str] = mapped_column(String(16), server_default=ReviewState.WAITING.value)
    edited_text: Mapped[str | None] = mapped_column(Text)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )


class Note(Base):
    """The owner's own note on a conversation."""

    __tablename__ = "note"

    conversation_id: Mapped[UUID] = mapped_column(
        ForeignKey("conversation.id", ondelete="CASCADE"), primary_key=True
    )
    text: Mapped[str] = mapped_column(Text)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )


class AskTurn(Base):
    """A question about one conversation and its checked answer (spec §7)."""

    __tablename__ = "ask_turn"

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    conversation_id: Mapped[UUID] = mapped_column(
        ForeignKey("conversation.id", ondelete="CASCADE"), index=True
    )
    question: Mapped[str] = mapped_column(Text)
    answer: Mapped[str] = mapped_column(Text)
    # False means "not enough in this recording".
    enough: Mapped[bool] = mapped_column(Boolean)
    sources: Mapped[list[int]] = mapped_column(ARRAY(Integer), server_default="{}")
    # From the web or general knowledge, kept apart from the cited answer (owner's choice, 11 Oct).
    beyond: Mapped[str | None] = mapped_column(Text)
    links: Mapped[list[dict[str, str]]] = mapped_column(JSONB, server_default=text("'[]'::jsonb"))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class DigestState(StrEnum):
    UPDATING = "updating"
    FINISHED = "finished"
    QUIET = "quiet"
    FAILED = "failed"


class DayDigest(Base):
    """The daily digest (spec §5.5)."""

    __tablename__ = "day_digest"

    day: Mapped[date] = mapped_column(Date, primary_key=True)
    state: Mapped[str] = mapped_column(String(16))
    overview: Mapped[str | None] = mapped_column(Text)
    # [{"conversation_id", "at_ms", "text"}]
    moments: Mapped[list[dict[str, Any]]] = mapped_column(
        JSONB, server_default=sql_text("'[]'::jsonb")
    )
    recordings_total: Mapped[int] = mapped_column(Integer, server_default="0")
    recordings_done: Mapped[int] = mapped_column(Integer, server_default="0")
    error: Mapped[str | None] = mapped_column(Text)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )


class Word(Base):
    """The personal word list, sent to Scribe as keyterms (spec §5.2, §5.6)."""

    __tablename__ = "word"
    __table_args__ = (Index("uq_word_term", sql_text("lower(term)"), unique=True),)

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    term: Mapped[str] = mapped_column(String(120))
    # manual, or learned from repeated fixes (then waiting for the owner to confirm).
    source: Mapped[str] = mapped_column(String(16), server_default="manual")
    confirmed: Mapped[bool] = mapped_column(Boolean, server_default="true")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class Notification(Base):
    """The Updates feed (screens 03, 03b): what happened and the way to recover."""

    __tablename__ = "notification"

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    kind: Mapped[str] = mapped_column(String(32))
    recording_id: Mapped[UUID | None] = mapped_column(
        ForeignKey("recording.id", ondelete="CASCADE")
    )
    conversation_id: Mapped[UUID | None] = mapped_column(
        ForeignKey("conversation.id", ondelete="SET NULL")
    )
    title: Mapped[str] = mapped_column(Text)
    body: Mapped[str] = mapped_column(Text, server_default="")
    action: Mapped[str | None] = mapped_column(String(32))
    read_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), index=True
    )


class DriveFile(Base):
    """A file Roudio put on Drive and when it was last checked (MD5 + read back)."""

    __tablename__ = "drive_file"
    __table_args__ = (UniqueConstraint("recording_id", "kind", name="uq_drive_file_kind"),)

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    recording_id: Mapped[UUID] = mapped_column(
        ForeignKey("recording.id", ondelete="CASCADE"), index=True
    )
    # audio, transcript_txt, transcript_json, notes
    kind: Mapped[str] = mapped_column(String(32))
    drive_file_id: Mapped[str] = mapped_column(String(128))
    md5: Mapped[str] = mapped_column(String(32))
    size_bytes: Mapped[int] = mapped_column(BigInteger)
    verified_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class CostEntry(Base):
    """What an outside service charged for a recording (cost dashboard, milestone 7)."""

    __tablename__ = "cost_entry"

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    recording_id: Mapped[UUID | None] = mapped_column(
        ForeignKey("recording.id", ondelete="SET NULL"), index=True
    )
    service: Mapped[str] = mapped_column(String(32))
    units: Mapped[Decimal] = mapped_column(Numeric(14, 3))
    unit: Mapped[str] = mapped_column(String(16))
    usd: Mapped[Decimal] = mapped_column(Numeric(12, 6))
    # What it was for: transcribe, tidy, understand, digest, ask, web_search.
    purpose: Mapped[str | None] = mapped_column(String(16))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class AppSetting(Base):
    """Small owner settings and remembered ids (the deleting switch, Drive folder ids)."""

    __tablename__ = "app_setting"

    key: Mapped[str] = mapped_column(String(64), primary_key=True)
    value: Mapped[Any] = mapped_column(JSONB)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )
