"""What the phone and web read and change: days, conversations, transcripts, Ask, notes, edits.

Times: `*_at` are absolute UTC instants; `*_ms` are offsets into the recording's original audio
(what the player seeks to). Every generated statement carries the transcript segments it rests on.
"""

import logging
from datetime import UTC, date, datetime, timedelta
from typing import Annotated, Literal
from uuid import UUID

from fastapi import APIRouter, status
from pydantic import BaseModel, Field
from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from roudio.api.deps import AppServices, CurrentSession, Db, error
from roudio.api.schema import INT64, Id64
from roudio.models import (
    AskTurn,
    Conversation,
    DayDigest,
    GeneratedItem,
    Note,
    Recording,
    ReviewItem,
    Speaker,
    TranscriptSegment,
    Word,
)
from roudio.pipeline import services as pipeline
from roudio.pipeline.brain import BrainError, BrainNotConfigured, Line
from roudio.pipeline.digest import _recordings_on
from roudio.storage import StorageError

log = logging.getLogger(__name__)
router = APIRouter(tags=["library"])


class RecordingSummary(BaseModel):
    id: UUID
    recorder_serial: str
    session_id: int = Field(json_schema_extra=INT64)
    started_at: datetime
    zone_offset_seconds: int
    duration_ms: Annotated[int, Field(json_schema_extra=INT64)] | None
    transfer_state: str
    processing_state: str
    processing_error: str | None
    backup_state: str
    backup_error: str | None
    cleanup_state: str
    results_version: int


class SpeakerView(BaseModel):
    id: int = Field(json_schema_extra=INT64)
    label: str
    name: str | None


class ConversationSummary(BaseModel):
    id: UUID
    recording_id: UUID
    day: date
    kind: str
    title: str
    summary: str
    started_at: datetime
    ended_at: datetime
    start_ms: int = Field(json_schema_extra=INT64)
    end_ms: int = Field(json_schema_extra=INT64)
    # Filled in on the day and recording lists (Today's "Daniel, Priya · 2 decisions").
    speakers: list[SpeakerView] = []
    decisions: int = 0


class DigestMoment(BaseModel):
    conversation_id: UUID
    item_id: int = Field(json_schema_extra=INT64)
    kind: str
    text: str
    at_ms: int = Field(json_schema_extra=INT64)
    at: datetime


class DigestView(BaseModel):
    state: Literal["updating", "finished", "quiet", "failed"]
    overview: str | None
    moments: list[DigestMoment]
    recordings_total: int
    recordings_done: int
    key_moments: int
    decisions: int


class DayView(BaseModel):
    day: date
    recordings: list[RecordingSummary]
    conversations: list[ConversationSummary]
    digest: DigestView | None


class SegmentView(BaseModel):
    id: int = Field(json_schema_extra=INT64)
    idx: int
    speaker: str
    speaker_name: str | None
    start_ms: int = Field(json_schema_extra=INT64)
    end_ms: int = Field(json_schema_extra=INT64)
    text: str
    original_text: str
    corrections: list[dict[str, str]]
    edited: bool


class ItemView(BaseModel):
    id: int = Field(json_schema_extra=INT64)
    kind: Literal["decision", "task", "key_moment"]
    text: str
    at_ms: int = Field(json_schema_extra=INT64)
    sources: list[int]
    review_id: Annotated[int, Field(json_schema_extra=INT64)] | None = None
    review_state: str | None = None


class AskView(BaseModel):
    id: int = Field(json_schema_extra=INT64)
    question: str
    answer: str
    enough: bool
    sources: list[int]
    created_at: datetime


class ConversationDetail(BaseModel):
    conversation: ConversationSummary
    recording: RecordingSummary
    summary_sources: list[int]
    items: list[ItemView]
    speakers: list[SpeakerView]
    segments: list[SegmentView]
    language: str | None
    note: str | None
    asks: list[AskView]


def recording_view(r: Recording) -> RecordingSummary:
    return RecordingSummary(
        id=r.id,
        recorder_serial=r.recorder_serial,
        session_id=r.session_id,
        started_at=r.started_at.astimezone(UTC),
        zone_offset_seconds=r.zone_offset_seconds,
        duration_ms=r.duration_ms,
        transfer_state=r.transfer_state,
        processing_state=r.processing_state,
        processing_error=r.processing_error,
        backup_state=r.backup_state,
        backup_error=r.backup_error,
        cleanup_state=r.cleanup_state,
        results_version=r.results_version,
    )


def conversation_view(c: Conversation, r: Recording) -> ConversationSummary:
    return ConversationSummary(
        id=c.id,
        recording_id=c.recording_id,
        day=c.day,
        kind=c.kind,
        title=c.title,
        summary=c.summary,
        started_at=(r.started_at + timedelta(milliseconds=c.start_ms)).astimezone(UTC),
        ended_at=(r.started_at + timedelta(milliseconds=c.end_ms)).astimezone(UTC),
        start_ms=c.start_ms,
        end_ms=c.end_ms,
    )


async def with_people(
    db: AsyncSession, views: list[ConversationSummary]
) -> list[ConversationSummary]:
    """Add who spoke (in order of first words) and the decision count to each summary."""
    if not views:
        return views
    ids = [v.id for v in views]
    decisions = dict(
        (
            await db.execute(
                select(GeneratedItem.conversation_id, func.count())
                .where(GeneratedItem.conversation_id.in_(ids), GeneratedItem.kind == "decision")
                .group_by(GeneratedItem.conversation_id)
            )
        ).all()
    )
    recording_ids = {v.recording_id for v in views}
    speakers = {
        (s.recording_id, s.label): s
        for s in await db.scalars(select(Speaker).where(Speaker.recording_id.in_(recording_ids)))
    }
    segments = (
        await db.execute(
            select(
                TranscriptSegment.recording_id,
                TranscriptSegment.speaker,
                TranscriptSegment.start_ms,
            )
            .where(TranscriptSegment.recording_id.in_(recording_ids))
            .order_by(TranscriptSegment.start_ms)
        )
    ).all()
    for view in views:
        labels: list[str] = []
        for recording_id, label, start_ms in segments:
            if recording_id == view.recording_id and view.start_ms <= start_ms < view.end_ms:
                if label not in labels:
                    labels.append(label)
        view.speakers = [
            SpeakerView(id=s.id, label=s.label, name=s.name)
            for label in labels
            if (s := speakers.get((view.recording_id, label))) is not None
        ]
        view.decisions = decisions.get(view.id, 0)
    return views


@router.get("/days/{day}", operation_id="getDay")
async def get_day(day: date, _: CurrentSession, db: Db) -> DayView:
    recordings = sorted(await _recordings_on(db, day), key=lambda r: r.started_at)
    by_id = {r.id: r for r in recordings}
    conversations = [
        conversation_view(c, by_id[c.recording_id])
        for c in await db.scalars(select(Conversation).where(Conversation.day == day))
        if c.recording_id in by_id
    ]
    conversations.sort(key=lambda c: c.started_at)
    await with_people(db, conversations)
    digest = await db.get(DayDigest, day)
    return DayView(
        day=day,
        recordings=[recording_view(r) for r in recordings],
        conversations=conversations,
        digest=await _digest_view(db, digest, by_id) if digest else None,
    )


async def _digest_view(
    db: AsyncSession, digest: DayDigest, recordings: dict[UUID, Recording]
) -> DigestView:
    conversation_ids = [
        c for c in (await db.scalars(select(Conversation.id).where(Conversation.day == digest.day)))
    ]
    kinds = list(
        await db.scalars(
            select(GeneratedItem.kind).where(GeneratedItem.conversation_id.in_(conversation_ids))
        )
    )
    starts = {
        c.id: recordings[c.recording_id].started_at
        for c in await db.scalars(select(Conversation).where(Conversation.id.in_(conversation_ids)))
        if c.recording_id in recordings
    }
    moments = [
        DigestMoment(
            conversation_id=UUID(m["conversation_id"]),
            item_id=m["item_id"],
            kind=m.get("kind", "key_moment"),
            text=m["text"],
            at_ms=m["at_ms"],
            at=starts.get(UUID(m["conversation_id"]), datetime.now(UTC))
            + timedelta(milliseconds=m["at_ms"]),
        )
        for m in digest.moments
    ]
    return DigestView(
        state=digest.state,
        overview=digest.overview,
        moments=moments,
        recordings_total=digest.recordings_total,
        recordings_done=digest.recordings_done,
        key_moments=kinds.count("key_moment"),
        decisions=kinds.count("decision"),
    )


async def _conversation(db: AsyncSession, conversation_id: UUID) -> tuple[Conversation, Recording]:
    conversation = await db.get(Conversation, conversation_id)
    if conversation is None:
        raise error(
            status.HTTP_404_NOT_FOUND, "not_found", "That conversation isn't there any more."
        )
    recording = await db.get(Recording, conversation.recording_id)
    assert recording is not None
    return conversation, recording


async def _segments(db: AsyncSession, c: Conversation) -> list[TranscriptSegment]:
    return list(
        await db.scalars(
            select(TranscriptSegment)
            .where(
                TranscriptSegment.recording_id == c.recording_id,
                TranscriptSegment.start_ms >= c.start_ms,
                TranscriptSegment.start_ms <= c.end_ms,
            )
            .order_by(TranscriptSegment.idx)
        )
    )


@router.get("/conversations/{conversation_id}", operation_id="getConversation")
async def get_conversation(conversation_id: UUID, _: CurrentSession, db: Db) -> ConversationDetail:
    c, r = await _conversation(db, conversation_id)
    speakers = list(await db.scalars(select(Speaker).where(Speaker.recording_id == r.id)))
    names = {s.label: s.name for s in speakers}
    segments = await _segments(db, c)
    items = list(
        await db.execute(
            select(GeneratedItem, ReviewItem)
            .outerjoin(ReviewItem, ReviewItem.generated_item_id == GeneratedItem.id)
            .where(GeneratedItem.conversation_id == c.id)
            .order_by(GeneratedItem.position)
        )
    )
    note = await db.get(Note, c.id)
    asks = list(
        await db.scalars(
            select(AskTurn).where(AskTurn.conversation_id == c.id).order_by(AskTurn.id)
        )
    )
    used = {s.speaker for s in segments}
    return ConversationDetail(
        conversation=conversation_view(c, r),
        recording=recording_view(r),
        summary_sources=c.summary_sources,
        items=[
            ItemView(
                id=i.id,
                kind=i.kind,
                text=(rv.edited_text if rv and rv.edited_text else i.text),
                at_ms=i.at_ms,
                sources=i.sources,
                review_id=rv.id if rv else None,
                review_state=rv.state if rv else None,
            )
            for i, rv in items
        ],
        speakers=[
            SpeakerView(id=s.id, label=s.label, name=s.name) for s in speakers if s.label in used
        ],
        segments=[segment_view(s, names) for s in segments],
        language=r.language,
        note=note.text if note else None,
        asks=[
            AskView(
                id=a.id,
                question=a.question,
                answer=a.answer,
                enough=a.enough,
                sources=a.sources,
                created_at=a.created_at,
            )
            for a in asks
        ],
    )


def segment_view(s: TranscriptSegment, names: dict[str, str | None]) -> SegmentView:
    return SegmentView(
        id=s.id,
        idx=s.idx,
        speaker=s.speaker,
        speaker_name=names.get(s.speaker),
        start_ms=s.start_ms,
        end_ms=s.end_ms,
        text=s.text,
        original_text=s.original_text,
        corrections=s.corrections,
        edited=s.edited_at is not None,
    )


class AudioLink(BaseModel):
    url: str = Field(description="Short-lived link to the original Ogg Opus file")
    expires_at: datetime


@router.get("/recordings/{recording_id}/audio", operation_id="getRecordingAudio")
async def recording_audio(
    recording_id: UUID, _: CurrentSession, db: Db, services: AppServices
) -> AudioLink:
    recording = await db.get(Recording, recording_id)
    if recording is None or recording.transfer_state != "uploaded":
        raise error(
            status.HTTP_404_NOT_FOUND, "not_found", "That recording's audio isn't on the server."
        )
    try:
        url = await services.audio.presigned_url(recording.storage_key, seconds=3600)
    except StorageError as exc:
        raise error(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "storage_unavailable",
            "Audio can't be reached just now.",
        ) from exc
    return AudioLink(url=url, expires_at=datetime.now(UTC) + timedelta(seconds=3600))


class ConversationRename(BaseModel):
    title: str = Field(min_length=1, max_length=120)


@router.patch("/conversations/{conversation_id}", operation_id="renameConversation")
async def rename_conversation(
    conversation_id: UUID, body: ConversationRename, _: CurrentSession, db: Db
) -> ConversationSummary:
    """06c "Rename recording": the owner's title replaces the generated one."""
    c, r = await _conversation(db, conversation_id)
    c.title = body.title.strip()
    r.results_version += 1
    await db.commit()
    return conversation_view(c, r)


class AskRequest(BaseModel):
    question: str = Field(min_length=1, max_length=500)


NOT_ENOUGH = "There isn't enough in this recording to answer that."


@router.post("/conversations/{conversation_id}/ask", operation_id="askConversation")
async def ask(conversation_id: UUID, body: AskRequest, _: CurrentSession, db: Db) -> AskView:
    c, _r = await _conversation(db, conversation_id)
    segments = await _segments(db, c)
    if not segments:
        raise error(
            status.HTTP_409_CONFLICT, "not_written_up", "This recording hasn't been written up yet."
        )
    brain = pipeline.get().brain
    try:
        result = await brain.ask(
            [Line(s.idx, s.speaker, s.start_ms, s.text) for s in segments], body.question.strip()
        )
    except BrainNotConfigured as exc:
        raise error(
            status.HTTP_503_SERVICE_UNAVAILABLE, "ask_unavailable", "Ask isn't set up yet."
        ) from exc
    except BrainError as exc:
        log.warning("ask failed: %s", exc)
        raise error(
            status.HTTP_502_BAD_GATEWAY, "ask_failed", "Couldn't answer just now. Try again."
        ) from exc
    # Only citations inside this conversation count; without one there's no answer (spec §7).
    inside = {s.idx for s in segments}
    sources = sorted({i for i in result.value.sources if i in inside})
    text = result.value.text.strip()
    enough = result.value.enough and bool(sources)
    # Claimed an answer but cited nothing real: that's not an answer. An honest "not enough"
    # keeps its explanation (and any closest sources).
    answer = text if text and (enough or not result.value.enough) else NOT_ENOUGH
    turn = AskTurn(
        conversation_id=c.id,
        question=body.question.strip(),
        answer=answer,
        enough=enough,
        sources=sources,
    )
    db.add(turn)
    await db.commit()
    return AskView(
        id=turn.id,
        question=turn.question,
        answer=turn.answer,
        enough=turn.enough,
        sources=turn.sources,
        created_at=turn.created_at,
    )


@router.delete("/conversations/{conversation_id}/ask", operation_id="clearAsk", status_code=204)
async def clear_ask(conversation_id: UUID, _: CurrentSession, db: Db) -> None:
    await _conversation(db, conversation_id)
    await db.execute(delete(AskTurn).where(AskTurn.conversation_id == conversation_id))
    await db.commit()


class NoteRequest(BaseModel):
    text: str = Field(max_length=20_000)


@router.put("/conversations/{conversation_id}/note", operation_id="setNote", status_code=204)
async def set_note(conversation_id: UUID, body: NoteRequest, _: CurrentSession, db: Db) -> None:
    await _conversation(db, conversation_id)
    note = await db.get(Note, conversation_id)
    if not body.text.strip():
        if note:
            await db.delete(note)
    elif note:
        note.text = body.text
    else:
        db.add(Note(conversation_id=conversation_id, text=body.text))
    await db.commit()


class SegmentEdit(BaseModel):
    text: str = Field(min_length=1, max_length=10_000)
    edited_at: datetime = Field(description="When the owner made the edit; the latest edit wins")


@router.patch("/segments/{segment_id}", operation_id="editSegment")
async def edit_segment(
    segment_id: Id64, body: SegmentEdit, _: CurrentSession, db: Db
) -> SegmentView:
    segment = await db.get(TranscriptSegment, segment_id)
    if segment is None:
        raise error(
            status.HTTP_404_NOT_FOUND,
            "not_found",
            "That part of the transcript isn't there any more.",
        )
    if segment.edited_at is None or body.edited_at >= segment.edited_at:
        before = segment.text
        segment.text = body.text.strip()
        segment.edited_at = body.edited_at
        await learn_from_edit(db, before, segment.text)
        recording = await db.get(Recording, segment.recording_id)
        if recording:
            recording.results_version += 1
        await db.commit()
    names = {
        s.label: s.name
        for s in await db.scalars(
            select(Speaker).where(Speaker.recording_id == segment.recording_id)
        )
    }
    return segment_view(segment, names)


async def learn_from_edit(db: AsyncSession, before: str, after: str) -> None:
    """A word the owner keeps fixing the same way becomes a word-list suggestion (spec §5.6).

    Here, a capitalised word the edit introduced that the transcript lacked becomes an unconfirmed
    "learned" entry; the owner confirms it in the word list (Settings) before it's sent to Scribe.
    """
    old = set(before.split())
    for word in after.split():
        clean = word.strip(".,!?;:\"'()")
        if len(clean) > 2 and clean[0].isupper() and clean not in old and not clean.isupper():
            exists = await db.scalar(select(Word).where(Word.term.ilike(clean)))
            if exists is None:
                db.add(Word(term=clean, source="learned", confirmed=False))


class SpeakerRename(BaseModel):
    name: str = Field(min_length=1, max_length=120)


@router.patch("/speakers/{speaker_id}", operation_id="renameSpeaker")
async def rename_speaker(
    speaker_id: Id64, body: SpeakerRename, _: CurrentSession, db: Db
) -> SpeakerView:
    speaker = await db.get(Speaker, speaker_id)
    if speaker is None:
        raise error(status.HTTP_404_NOT_FOUND, "not_found", "That speaker isn't there any more.")
    speaker.name = body.name.strip()
    recording = await db.get(Recording, speaker.recording_id)
    if recording:
        recording.results_version += 1
    await db.commit()
    return SpeakerView(id=speaker.id, label=speaker.label, name=speaker.name)
