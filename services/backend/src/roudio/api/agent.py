"""`/agent/v1`: the feed Root reads and the few fields she writes back.

The contract is docs/architecture/agent-feed.md. Agent keys work only here and app sessions don't;
her writes live in the agent_* tables and every one is logged in `agent_action`. Her own writes
don't come back to her as `conversation.updated`; only the owner's changes do.
"""

import time
from collections import deque
from datetime import UTC, date, datetime
from typing import Annotated, Any, Literal
from uuid import UUID

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, Field
from sqlalchemy import select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from roudio.agent import events
from roudio.agent.audit import log_action as _log
from roudio.agent.keys import PREFIX, hash_key
from roudio.api.deps import Db, error
from roudio.api.library import DigestView, _digest_view, _segments, conversation_view
from roudio.api.schema import INT64, Id64
from roudio.models import (
    AgentAction,
    AgentAnnotation,
    AgentCorrection,
    AgentEvent,
    AgentItemSync,
    AgentKey,
    Conversation,
    DayDigest,
    GeneratedItem,
    Note,
    Notification,
    Recording,
    ReviewItem,
    ReviewState,
    Speaker,
    TranscriptSegment,
)
from roudio.pipeline.digest import _recordings_on

router = APIRouter(prefix="/agent/v1", tags=["agent"])


# Access: a hashed key with scopes, 60 requests a minute.


class RateLimiter:
    """A sliding one-minute window per key. One api instance, so memory is enough."""

    def __init__(self, per_minute: int) -> None:
        self.per_minute = per_minute
        self._hits: dict[int, deque[float]] = {}

    def retry_after(self, key_id: int) -> int:
        """0 when the request may go ahead (and counts it), else seconds to wait."""
        now = time.monotonic()
        hits = self._hits.setdefault(key_id, deque())
        while hits and now - hits[0] >= 60:
            hits.popleft()
        if len(hits) >= self.per_minute:
            return max(1, int(60 - (now - hits[0])) + 1)
        hits.append(now)
        return 0


_bearer = HTTPBearer(auto_error=False)


async def _agent_key(
    request: Request,
    db: Db,
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(_bearer)],
) -> AgentKey:
    if credentials is None or not credentials.credentials.startswith(PREFIX):
        raise error(status.HTTP_401_UNAUTHORIZED, "bad_key", "Send an agent key.")
    key = await db.scalar(
        select(AgentKey).where(
            AgentKey.key_hash == hash_key(credentials.credentials), AgentKey.revoked_at.is_(None)
        )
    )
    if key is None:
        raise error(status.HTTP_401_UNAUTHORIZED, "bad_key", "That key isn't valid.")
    limiter: RateLimiter = request.app.state.agent_limiter
    wait = limiter.retry_after(key.id)
    if wait:
        raise HTTPException(
            status.HTTP_429_TOO_MANY_REQUESTS,
            detail={"code": "rate_limited", "message": "Too many requests; slow down."},
            headers={"Retry-After": str(wait)},
        )
    now = datetime.now(UTC)
    if key.last_used_at is None or (now - key.last_used_at).total_seconds() > 60:
        key.last_used_at = now
        await db.commit()
    return key


def _scope(scope: str) -> Any:
    async def check(key: Annotated[AgentKey, Depends(_agent_key)]) -> AgentKey:
        if scope not in key.scopes:
            raise error(
                status.HTTP_403_FORBIDDEN, "missing_scope", f"This key can't {scope}.", scope=scope
            )
        return key

    return Depends(check)


ReadKey = Annotated[AgentKey, _scope("read")]
AnnotateKey = Annotated[AgentKey, _scope("annotate")]


# The conversation as Root sees it.


class AgentSpeaker(BaseModel):
    id: int = Field(json_schema_extra=INT64)
    label: str
    name: str | None
    name_source: Literal["owner", "root", "carried"] | None = Field(
        description="Who set the name. Re-check `carried` names after a `replaces`."
    )


class AgentSync(BaseModel):
    state: Literal["synced", "dismissed"]
    system: Literal["notion", "clickup", "ticktick"] | None
    external_id: str | None
    url: str | None
    updated_at: datetime


class AgentItem(BaseModel):
    id: int = Field(json_schema_extra=INT64)
    kind: Literal["decision", "task", "key_moment"]
    text: str
    at_ms: int = Field(json_schema_extra=INT64)
    review_state: Literal["waiting", "accepted", "done", "dismissed"] | None
    sync: AgentSync | None


class AgentLink(BaseModel):
    system: Literal["notion", "clickup", "ticktick"]
    kind: str = Field(max_length=32)
    external_id: str | None = Field(default=None, max_length=200)
    url: str | None = Field(default=None, max_length=2000)
    label: str | None = Field(default=None, max_length=200)


class NeedsReview(BaseModel):
    what: str = Field(min_length=1, max_length=500)
    options: list[Annotated[str, Field(max_length=200)]] = Field(default=[], max_length=10)


class AgentCorrectionView(BaseModel):
    field: Literal["title", "kind"]
    value: str
    original_value: str
    confidence: float | None
    reason: str | None
    recheck: bool
    created_at: datetime


class AnnotationView(BaseModel):
    category: str | None = None
    tags: list[str] = []
    links: list[AgentLink] = []
    needs_review: list[NeedsReview] = []
    confidence: float | None = None
    reason: str | None = None
    updated_at: datetime | None = None
    recheck: bool = Field(
        default=False,
        description="Moved here by a reprocess: confirm by sending it again, or drop it",
    )
    corrections: list[AgentCorrectionView] = []


class AgentConversation(BaseModel):
    id: UUID
    recording_id: UUID
    results_version: int
    replaces: list[UUID]
    day: date
    started_at: datetime
    ended_at: datetime
    kind: Literal["conversation", "event", "voice_note"]
    title: str
    summary: str
    language: str | None
    speakers: list[AgentSpeaker]
    items: list[AgentItem]
    note: str | None
    annotation: AnnotationView | None
    annotation_version: int


def _sync_view(s: AgentItemSync | None) -> AgentSync | None:
    if s is None:
        return None
    return AgentSync(
        state=s.state,
        system=s.system,
        external_id=s.external_id,
        url=s.url,
        updated_at=s.updated_at,
    )


def _annotation_view(
    a: AgentAnnotation | None, corrections: list[AgentCorrection]
) -> AnnotationView | None:
    if a is None and not corrections:
        return None
    data = a.data if a else {}
    return AnnotationView(
        **data,
        updated_at=a.updated_at if a else None,
        recheck=bool(a and a.recheck) or any(c.recheck for c in corrections),
        corrections=[
            AgentCorrectionView(
                field=c.field,
                value=c.value,
                original_value=c.original_value,
                confidence=c.confidence,
                reason=c.reason,
                recheck=c.recheck,
                created_at=c.created_at,
            )
            for c in sorted(corrections, key=lambda c: c.field)
        ],
    )


async def render(db: AsyncSession, ids: list[UUID]) -> dict[UUID, AgentConversation]:
    """The conversations that still exist, as of now (feed events point here)."""
    if not ids:
        return {}
    rows = (
        await db.execute(
            select(Conversation, Recording)
            .join(Recording, Recording.id == Conversation.recording_id)
            .where(Conversation.id.in_(set(ids)))
        )
    ).all()
    if not rows:
        return {}
    conversation_ids = [c.id for c, _ in rows]
    recording_ids = {r.id for _, r in rows}
    speakers = {
        (s.recording_id, s.label): s
        for s in await db.scalars(select(Speaker).where(Speaker.recording_id.in_(recording_ids)))
    }
    turns = (
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
    items: dict[UUID, list[AgentItem]] = {}
    for g, review, sync in (
        await db.execute(
            select(GeneratedItem, ReviewItem, AgentItemSync)
            .outerjoin(ReviewItem, ReviewItem.generated_item_id == GeneratedItem.id)
            .outerjoin(AgentItemSync, AgentItemSync.generated_item_id == GeneratedItem.id)
            .where(GeneratedItem.conversation_id.in_(conversation_ids))
            .order_by(GeneratedItem.position, GeneratedItem.id)
        )
    ).all():
        items.setdefault(g.conversation_id, []).append(
            AgentItem(
                id=g.id,
                kind=g.kind,
                text=(review.edited_text if review and review.edited_text else g.text),
                at_ms=g.at_ms,
                review_state=review.state if review else None,
                sync=_sync_view(sync),
            )
        )
    notes = {
        n.conversation_id: n.text
        for n in await db.scalars(select(Note).where(Note.conversation_id.in_(conversation_ids)))
    }
    annotations = {
        a.conversation_id: a
        for a in await db.scalars(
            select(AgentAnnotation).where(AgentAnnotation.conversation_id.in_(conversation_ids))
        )
    }
    corrections: dict[UUID, list[AgentCorrection]] = {}
    for fix in await db.scalars(
        select(AgentCorrection).where(AgentCorrection.conversation_id.in_(conversation_ids))
    ):
        corrections.setdefault(fix.conversation_id, []).append(fix)

    out: dict[UUID, AgentConversation] = {}
    for c, r in rows:
        labels: list[str] = []
        for recording_id, label, start_ms in turns:
            if recording_id == r.id and c.start_ms <= start_ms < c.end_ms and label not in labels:
                labels.append(label)
        people = [s for label in labels if (s := speakers.get((r.id, label))) is not None]
        times = conversation_view(c, r)
        annotation = annotations.get(c.id)
        out[c.id] = AgentConversation(
            id=c.id,
            recording_id=r.id,
            results_version=r.results_version,
            replaces=c.replaces,
            day=c.day,
            started_at=times.started_at,
            ended_at=times.ended_at,
            kind=c.kind,
            title=c.title,
            summary=c.summary,
            language=r.language,
            speakers=[
                AgentSpeaker(
                    id=s.id,
                    label=s.label,
                    name=s.name,
                    name_source=(s.name_source or "owner") if s.name else None,
                )
                for s in people
            ],
            items=items.get(c.id, []),
            note=notes.get(c.id),
            annotation=_annotation_view(annotation, corrections.get(c.id, [])),
            annotation_version=annotation.version if annotation else 0,
        )
    return out


async def _one(db: AsyncSession, conversation_id: UUID) -> AgentConversation:
    found = (await render(db, [conversation_id])).get(conversation_id)
    if found is None:
        raise error(
            status.HTTP_404_NOT_FOUND, "not_found", "That conversation isn't there any more."
        )
    return found


# The feed.


class ConversationEvent(BaseModel):
    seq: int = Field(json_schema_extra=INT64)
    type: Literal["conversation.ready", "conversation.updated"]
    conversation: AgentConversation


class RemovedEvent(BaseModel):
    seq: int = Field(json_schema_extra=INT64)
    type: Literal["conversation.removed"]
    id: UUID
    reason: Literal["reprocessed", "deleted"]


class DigestReadyEvent(BaseModel):
    seq: int = Field(json_schema_extra=INT64)
    type: Literal["digest.ready"]
    day: date
    updated_at: datetime


class RecordingEmptyEvent(BaseModel):
    seq: int = Field(json_schema_extra=INT64)
    type: Literal["recording.empty"]
    recording_id: UUID
    speech_ms: int = Field(json_schema_extra=INT64)


class SyncLostEvent(BaseModel):
    seq: int = Field(json_schema_extra=INT64)
    type: Literal["item.sync_lost"]
    conversation_id: UUID
    old_item_id: int = Field(json_schema_extra=INT64)
    old_text: str
    state: Literal["synced", "dismissed"]
    system: str | None
    external_id: str | None


FeedEvent = Annotated[
    ConversationEvent | RemovedEvent | DigestReadyEvent | RecordingEmptyEvent | SyncLostEvent,
    Field(discriminator="type"),
]


class Feed(BaseModel):
    events: list[FeedEvent]
    next_cursor: str
    has_more: bool = Field(description="Call again straight away")


def _cursor(cursor: str | None) -> int:
    try:
        return events.decode_cursor(cursor)
    except ValueError as exc:
        raise error(
            status.HTTP_400_BAD_REQUEST, "bad_cursor", "Use a cursor from an earlier answer."
        ) from exc


@router.get("/feed", operation_id="agentFeed")
async def feed(
    _: ReadKey,
    db: Db,
    cursor: str | None = None,
    limit: Annotated[int, Query(ge=1, le=200)] = 50,
) -> Feed:
    """What changed after `cursor` (leave it out to start from the beginning)."""
    after = _cursor(cursor)
    rows = list(
        await db.scalars(
            select(AgentEvent)
            .where(AgentEvent.seq > after)
            .order_by(AgentEvent.seq)
            .limit(limit + 1)
        )
    )
    has_more = len(rows) > limit
    rows = rows[:limit]
    conversations = await render(
        db,
        [
            e.conversation_id
            for e in rows
            if e.type.startswith("conversation.")
            and e.type != "conversation.removed"
            and e.conversation_id
        ],
    )
    out: list[Any] = []
    for e in rows:
        if e.type in {"conversation.ready", "conversation.updated"}:
            # Gone since: its `conversation.removed` comes later in the feed.
            if e.conversation_id in conversations:
                out.append(
                    ConversationEvent(
                        seq=e.seq,
                        type=e.type,
                        conversation=conversations[e.conversation_id],
                    )
                )
        elif e.type == "conversation.removed":
            out.append(RemovedEvent(seq=e.seq, type=e.type, **e.data))
        elif e.type == "digest.ready":
            out.append(DigestReadyEvent(seq=e.seq, type=e.type, **e.data))
        elif e.type == "recording.empty":
            out.append(RecordingEmptyEvent(seq=e.seq, type=e.type, **e.data))
        elif e.type == "item.sync_lost":
            out.append(SyncLostEvent(seq=e.seq, type=e.type, **e.data))
    return Feed(
        events=out,
        next_cursor=events.encode_cursor(rows[-1].seq if rows else after),
        has_more=has_more,
    )


# Reads.


@router.get("/conversations/{conversation_id}", operation_id="agentGetConversation")
async def get_conversation(conversation_id: UUID, _: ReadKey, db: Db) -> AgentConversation:
    return await _one(db, conversation_id)


class AgentSegment(BaseModel):
    idx: int
    speaker: str
    speaker_name: str | None
    start_ms: int = Field(json_schema_extra=INT64)
    end_ms: int = Field(json_schema_extra=INT64)
    text: str
    edited: bool


class AgentTranscript(BaseModel):
    conversation_id: UUID
    segments: list[AgentSegment]


@router.get("/conversations/{conversation_id}/transcript", operation_id="agentGetTranscript")
async def get_transcript(conversation_id: UUID, _: ReadKey, db: Db) -> AgentTranscript:
    c = await db.get(Conversation, conversation_id)
    if c is None:
        raise error(
            status.HTTP_404_NOT_FOUND, "not_found", "That conversation isn't there any more."
        )
    names = {
        s.label: s.name
        for s in await db.scalars(select(Speaker).where(Speaker.recording_id == c.recording_id))
    }
    return AgentTranscript(
        conversation_id=c.id,
        segments=[
            AgentSegment(
                idx=s.idx,
                speaker=s.speaker,
                speaker_name=names.get(s.speaker),
                start_ms=s.start_ms,
                end_ms=s.end_ms,
                text=s.text,
                edited=s.edited_at is not None,
            )
            for s in await _segments(db, c)
        ],
    )


class AgentDigest(BaseModel):
    day: date
    updated_at: datetime
    digest: DigestView


@router.get("/days/{day}/digest", operation_id="agentGetDigest")
async def get_digest(day: date, _: ReadKey, db: Db) -> AgentDigest:
    """The day's digest as the app shows it; 404 until it's finished (or the day was quiet)."""
    digest = await db.get(DayDigest, day)
    if digest is None or digest.state not in {"finished", "quiet"}:
        raise error(status.HTTP_404_NOT_FOUND, "not_ready", "That day's digest isn't ready.")
    recordings = {r.id: r for r in await _recordings_on(db, day)}
    return AgentDigest(
        day=day, updated_at=digest.updated_at, digest=await _digest_view(db, digest, recordings)
    )


# Writes. Each one is logged, with her confidence and reason.


class AnnotationRequest(BaseModel):
    category: str | None = Field(default=None, max_length=64)
    tags: list[Annotated[str, Field(max_length=64)]] = Field(default=[], max_length=50)
    links: list[AgentLink] = Field(default=[], max_length=100)
    needs_review: list[NeedsReview] = Field(default=[], max_length=20)
    confidence: float | None = Field(default=None, ge=0, le=1)
    reason: str | None = Field(default=None, max_length=2000)


def _version(if_match: str | None) -> int:
    value = (if_match or "").strip().removeprefix("W/").strip('"')
    if not value.isdigit():
        raise error(
            status.HTTP_428_PRECONDITION_REQUIRED,
            "if_match_required",
            "Send If-Match with the annotation_version you last read (0 for none).",
        )
    return int(value)


@router.put("/conversations/{conversation_id}/annotation", operation_id="agentPutAnnotation")
async def put_annotation(
    conversation_id: UUID,
    body: AnnotationRequest,
    key: AnnotateKey,
    db: Db,
    if_match: Annotated[str | None, Header()] = None,
) -> AgentConversation:
    """Replace her annotation. A stale If-Match gets 409 `stale_annotation` with the current one."""
    expected = _version(if_match)
    if await db.get(Conversation, conversation_id) is None:
        raise error(
            status.HTTP_404_NOT_FOUND, "not_found", "That conversation isn't there any more."
        )
    row = await db.scalar(
        select(AgentAnnotation)
        .where(AgentAnnotation.conversation_id == conversation_id)
        .with_for_update()
    )
    current = row.version if row else 0
    if expected != current:
        await db.rollback()
        latest = await _one(db, conversation_id)
        raise HTTPException(
            status.HTTP_409_CONFLICT,
            detail={
                "code": "stale_annotation",
                "message": "The annotation changed since you read it. Merge and send it again.",
                "annotation": latest.annotation.model_dump(mode="json")
                if latest.annotation
                else None,
                "annotation_version": latest.annotation_version,
            },
        )
    data = body.model_dump(mode="json")
    old = row.data if row else None
    if row is None:
        row = AgentAnnotation(conversation_id=conversation_id, data=data, version=1)
        db.add(row)
    else:
        row.data = data
        row.version += 1
        row.recheck = False
    row.updated_at = datetime.now(UTC)
    _log(
        db,
        key,
        "PUT annotation",
        "conversation",
        conversation_id,
        old,
        data,
        body.confidence,
        body.reason,
    )
    try:
        await db.commit()
    except IntegrityError as exc:  # another write made the first annotation at the same time
        await db.rollback()
        raise error(
            status.HTTP_409_CONFLICT, "stale_annotation", "The annotation changed; read it again."
        ) from exc
    return await _one(db, conversation_id)


KINDS = ("conversation", "event", "voice_note")
_KIND_WORDS = {"conversation": "a conversation", "event": "an event", "voice_note": "a voice note"}


class CorrectionRequest(BaseModel):
    field: Literal["title", "kind"]
    value: str = Field(min_length=1, max_length=120)
    confidence: float | None = Field(default=None, ge=0, le=1)
    reason: str | None = Field(default=None, max_length=2000)


@router.post("/conversations/{conversation_id}/corrections", operation_id="agentCorrect")
async def correct(
    conversation_id: UUID, body: CorrectionRequest, key: AnnotateKey, db: Db
) -> AgentConversation:
    """Override the title or kind. Applied now, shown in Updates with undo, kept on reprocess.
    Sending the same correction again confirms one that came back with `recheck`."""
    conversation = await db.get(Conversation, conversation_id)
    if conversation is None:
        raise error(
            status.HTTP_404_NOT_FOUND, "not_found", "That conversation isn't there any more."
        )
    value = body.value.strip()
    if body.field == "kind" and value not in KINDS:
        raise error(
            status.HTTP_422_UNPROCESSABLE_CONTENT,
            "bad_kind",
            f"kind is one of {', '.join(KINDS)}.",
        )
    current = str(getattr(conversation, body.field))
    correction = await db.scalar(
        select(AgentCorrection).where(
            AgentCorrection.conversation_id == conversation_id,
            AgentCorrection.field == body.field,
        )
    )
    if correction is not None and value == correction.original_value:
        # Sending Roudio's own value back drops the correction (e.g. after a recheck).
        setattr(conversation, body.field, value)
        _log(
            db,
            key,
            "POST corrections (drop)",
            "conversation",
            conversation_id,
            {body.field: current},
            {body.field: value},
            body.confidence,
            body.reason,
        )
        await db.execute(
            update(Notification)
            .where(Notification.agent_correction_id == correction.id)
            .values(action=None)
        )
        await db.delete(correction)
        recording = await db.get(Recording, conversation.recording_id)
        if recording and value != current:
            recording.results_version += 1
        await db.commit()
        return await _one(db, conversation_id)
    changed = value != current
    if correction is None:
        correction = AgentCorrection(
            conversation_id=conversation_id, field=body.field, original_value=current
        )
        db.add(correction)
    correction.value = value
    correction.confidence = body.confidence
    correction.reason = body.reason
    correction.recheck = False
    correction.created_at = datetime.now(UTC)
    setattr(conversation, body.field, value)
    _log(
        db,
        key,
        "POST corrections",
        "conversation",
        conversation_id,
        {body.field: current},
        {body.field: value},
        body.confidence,
        body.reason,
    )
    if changed:
        recording = await db.get(Recording, conversation.recording_id)
        if recording:
            recording.results_version += 1  # the phone fetches it again
        await db.flush()
        if body.field == "title":
            title = f"Root renamed “{current}” to “{value}”"
        else:
            title = f"Root marked “{conversation.title}” as {_KIND_WORDS[value]}"
        db.add(
            Notification(
                kind="agent_correction",
                recording_id=conversation.recording_id,
                conversation_id=conversation_id,
                title=title,
                body=body.reason or "",
                action="undo",
                agent_correction_id=correction.id,
            )
        )
    await db.commit()
    return await _one(db, conversation_id)


def speaker_words(label: str) -> str:
    """speaker_0 → "speaker 1", as the apps number them."""
    _, _, n = label.rpartition("speaker_")
    return f"speaker {int(n) + 1}" if n.isdigit() else label.replace("_", " ")


class SpeakerRequest(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    confidence: float | None = Field(default=None, ge=0, le=1)
    reason: str | None = Field(default=None, max_length=2000)


@router.patch("/speakers/{speaker_id}", operation_id="agentNameSpeaker")
async def name_speaker(
    speaker_id: Id64, body: SpeakerRequest, key: AnnotateKey, db: Db
) -> AgentSpeaker:
    """Name a speaker for their whole recording. A name the owner set is never overwritten."""
    speaker = await db.get(Speaker, speaker_id)
    if speaker is None:
        raise error(status.HTTP_404_NOT_FOUND, "not_found", "That speaker isn't there any more.")
    name = body.name.strip()
    owner_set = speaker.name is not None and (speaker.name_source or "owner") == "owner"
    if owner_set and speaker.name != name:
        raise error(
            status.HTTP_409_CONFLICT,
            "owner_set",
            "The owner named this speaker.",
            name=speaker.name or "",
        )
    if not owner_set and (speaker.name != name or speaker.name_source != "root"):
        old = speaker.name
        speaker.name = name
        speaker.name_source = "root"
        _log(
            db,
            key,
            "PATCH speakers",
            "speaker",
            speaker.id,
            {"name": old},
            {"name": name},
            body.confidence,
            body.reason,
        )
        recording = await db.get(Recording, speaker.recording_id)
        if recording:
            recording.results_version += 1
        first = await db.scalar(
            select(Conversation.id)
            .join(
                TranscriptSegment,
                (TranscriptSegment.recording_id == Conversation.recording_id)
                & (TranscriptSegment.start_ms >= Conversation.start_ms)
                & (TranscriptSegment.start_ms < Conversation.end_ms),
            )
            .where(
                Conversation.recording_id == speaker.recording_id,
                TranscriptSegment.speaker == speaker.label,
            )
            .order_by(TranscriptSegment.start_ms)
            .limit(1)
        )
        if old != name:
            db.add(
                Notification(
                    kind="agent_speaker",
                    recording_id=speaker.recording_id,
                    conversation_id=first,
                    title=f"Root named {speaker_words(speaker.label)} {name}",
                    body=body.reason or "",
                )
            )
        await db.commit()
    return AgentSpeaker(
        id=speaker.id,
        label=speaker.label,
        name=speaker.name,
        name_source=(speaker.name_source or "owner") if speaker.name else None,
    )


class SyncRequest(BaseModel):
    state: Literal["synced", "dismissed"]
    system: Literal["notion", "clickup", "ticktick"] | None = None
    external_id: str | None = Field(default=None, max_length=200)
    url: str | None = Field(default=None, max_length=2000)


@router.put("/items/{item_id}/sync", operation_id="agentSyncItem")
async def sync_item(item_id: Id64, body: SyncRequest, key: AnnotateKey, db: Db) -> AgentItem:
    """Where an item went (ClickUp for project tasks, TickTick for personal ones), or that she
    dismissed it. `synced` also accepts its Review item, so it stops asking the owner."""
    item = await db.get(GeneratedItem, item_id)
    if item is None:
        raise error(status.HTTP_404_NOT_FOUND, "not_found", "That item isn't there any more.")
    if body.state == "synced" and not (body.system and body.external_id):
        raise error(
            status.HTTP_422_UNPROCESSABLE_CONTENT,
            "missing_target",
            "A synced item needs its system and external_id.",
        )
    sync = await db.get(AgentItemSync, item_id)
    old = (
        {
            "state": sync.state,
            "system": sync.system,
            "external_id": sync.external_id,
            "url": sync.url,
        }
        if sync
        else None
    )
    if sync is None:
        sync = AgentItemSync(generated_item_id=item_id)
        db.add(sync)
    sync.state = body.state
    sync.system = body.system
    sync.external_id = body.external_id
    sync.url = body.url
    sync.updated_at = datetime.now(UTC)
    review = await db.scalar(select(ReviewItem).where(ReviewItem.generated_item_id == item_id))
    if body.state == "synced" and review and review.state == ReviewState.WAITING:
        review.state = ReviewState.ACCEPTED.value
        review.updated_at = datetime.now(UTC)
    _log(db, key, "PUT items sync", "item", item_id, old, body.model_dump(mode="json"))
    await db.commit()
    return AgentItem(
        id=item.id,
        kind=item.kind,
        text=(review.edited_text if review and review.edited_text else item.text),
        at_ms=item.at_ms,
        review_state=review.state if review else None,
        sync=_sync_view(sync),
    )


# Her audit log.


class ActionView(BaseModel):
    id: int = Field(json_schema_extra=INT64)
    at: datetime
    actor: Literal["agent", "owner"]
    endpoint: str
    target_type: str
    target_id: str
    old_value: Any
    new_value: Any
    confidence: float | None
    reason: str | None


class ActionPage(BaseModel):
    actions: list[ActionView]
    next_cursor: str
    has_more: bool


@router.get("/actions", operation_id="agentActions")
async def actions(
    _: ReadKey,
    db: Db,
    cursor: str | None = None,
    limit: Annotated[int, Query(ge=1, le=500)] = 100,
) -> ActionPage:
    """Every write, oldest first; `actor` "owner" is the owner undoing one of hers."""
    after = _cursor(cursor)
    rows = list(
        await db.scalars(
            select(AgentAction)
            .where(AgentAction.id > after)
            .order_by(AgentAction.id)
            .limit(limit + 1)
        )
    )
    has_more = len(rows) > limit
    rows = rows[:limit]
    return ActionPage(
        actions=[
            ActionView(
                id=a.id,
                at=a.created_at,
                actor=a.actor,
                endpoint=a.endpoint,
                target_type=a.target_type,
                target_id=a.target_id,
                old_value=a.old_value,
                new_value=a.new_value,
                confidence=a.confidence,
                reason=a.reason,
            )
            for a in rows
        ],
        next_cursor=events.encode_cursor(rows[-1].id if rows else after),
        has_more=has_more,
    )
