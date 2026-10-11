"""Review, the word list, the Updates feed, search and the phone's sync (milestones 3 to 5)."""

from datetime import UTC, datetime, timedelta
from typing import Annotated, Literal
from uuid import UUID

from fastapi import APIRouter, Query, status
from pydantic import BaseModel, Field
from sqlalchemy import func, literal, select, update
from sqlalchemy.exc import IntegrityError

from roudio.api.deps import CurrentSession, Db, error
from roudio.api.library import (
    ConversationSummary,
    RecordingSummary,
    conversation_view,
    recording_view,
    with_people,
)
from roudio.api.schema import INT64, Id64
from roudio.models import (
    Conversation,
    DayDigest,
    GeneratedItem,
    Note,
    Notification,
    Recording,
    ReviewItem,
    ReviewState,
    TranscriptSegment,
    Word,
)

router = APIRouter(tags=["organise"])


# Review (screens 10, 10b, 10c, S6)


class ReviewView(BaseModel):
    id: int = Field(json_schema_extra=INT64)
    state: Literal["waiting", "accepted", "done", "dismissed"]
    text: str
    suggested_text: str
    conversation: ConversationSummary
    at_ms: int = Field(json_schema_extra=INT64)
    sources: list[int]
    updated_at: datetime


@router.get("/review", operation_id="listReview")
async def list_review(
    _: CurrentSession,
    db: Db,
    state: Literal["waiting", "accepted", "done", "dismissed"] | None = None,
) -> list[ReviewView]:
    query = (
        select(ReviewItem, GeneratedItem, Conversation, Recording)
        .join(GeneratedItem, GeneratedItem.id == ReviewItem.generated_item_id)
        .join(Conversation, Conversation.id == GeneratedItem.conversation_id)
        .join(Recording, Recording.id == Conversation.recording_id)
        .order_by(Recording.started_at.desc(), GeneratedItem.at_ms)
    )
    if state:
        query = query.where(ReviewItem.state == state)
    return [_review_view(r, g, c, rec) for r, g, c, rec in (await db.execute(query)).all()]


def _review_view(r: ReviewItem, g: GeneratedItem, c: Conversation, rec: Recording) -> ReviewView:
    return ReviewView(
        id=r.id,
        state=r.state,
        text=r.edited_text or g.text,
        suggested_text=g.text,
        conversation=conversation_view(c, rec),
        at_ms=g.at_ms,
        sources=g.sources,
        updated_at=r.updated_at,
    )


class ReviewChange(BaseModel):
    state: Literal["waiting", "accepted", "done", "dismissed"] | None = None
    text: str | None = Field(
        default=None, max_length=2000, description="The owner's wording; empty resets it"
    )


@router.patch("/review/{review_id}", operation_id="updateReview")
async def update_review(
    review_id: Id64, body: ReviewChange, _: CurrentSession, db: Db
) -> ReviewView:
    row = (
        await db.execute(
            select(ReviewItem, GeneratedItem, Conversation, Recording)
            .join(GeneratedItem, GeneratedItem.id == ReviewItem.generated_item_id)
            .join(Conversation, Conversation.id == GeneratedItem.conversation_id)
            .join(Recording, Recording.id == Conversation.recording_id)
            .where(ReviewItem.id == review_id)
        )
    ).first()
    if row is None:
        raise error(status.HTTP_404_NOT_FOUND, "not_found", "That item isn't there any more.")
    item, generated, conversation, recording = row
    if body.state:
        item.state = body.state
    if body.text is not None:
        item.edited_text = body.text.strip() or None
    item.updated_at = datetime.now(UTC)
    await db.commit()
    return _review_view(item, generated, conversation, recording)


# Word list (screen 15b)


class WordView(BaseModel):
    id: int = Field(json_schema_extra=INT64)
    term: str
    source: Literal["manual", "learned"]
    confirmed: bool


class WordRequest(BaseModel):
    term: str = Field(min_length=1, max_length=49)


class WordChange(BaseModel):
    confirmed: bool


def _word_view(w: Word) -> WordView:
    return WordView(id=w.id, term=w.term, source=w.source, confirmed=w.confirmed)


@router.get("/words", operation_id="listWords")
async def list_words(_: CurrentSession, db: Db) -> list[WordView]:
    return [_word_view(w) for w in await db.scalars(select(Word).order_by(func.lower(Word.term)))]


@router.post("/words", operation_id="addWord", status_code=201)
async def add_word(body: WordRequest, _: CurrentSession, db: Db) -> WordView:
    term = " ".join(body.term.split())
    existing = await db.scalar(select(Word).where(func.lower(Word.term) == term.lower()))
    if existing:
        existing.confirmed = True
        await db.commit()
        return _word_view(existing)
    word = Word(term=term, source="manual", confirmed=True)
    db.add(word)
    try:
        await db.commit()
    except IntegrityError as exc:
        await db.rollback()
        raise error(
            status.HTTP_409_CONFLICT, "exists", "That word is already on the list."
        ) from exc
    return _word_view(word)


@router.patch("/words/{word_id}", operation_id="updateWord")
async def update_word(word_id: Id64, body: WordChange, _: CurrentSession, db: Db) -> WordView:
    word = await db.get(Word, word_id)
    if word is None:
        raise error(status.HTTP_404_NOT_FOUND, "not_found", "That word isn't on the list.")
    word.confirmed = body.confirmed
    await db.commit()
    return _word_view(word)


@router.delete("/words/{word_id}", operation_id="deleteWord", status_code=204)
async def delete_word(word_id: Id64, _: CurrentSession, db: Db) -> None:
    word = await db.get(Word, word_id)
    if word is not None:
        await db.delete(word)
        await db.commit()


# Updates (screens 03, 03b)


class UpdateView(BaseModel):
    id: int = Field(json_schema_extra=INT64)
    kind: str
    title: str
    body: str
    action: str | None
    recording_id: UUID | None
    conversation_id: UUID | None
    read: bool
    created_at: datetime


@router.get("/updates", operation_id="listUpdates")
async def list_updates(_: CurrentSession, db: Db) -> list[UpdateView]:
    rows = await db.scalars(
        select(Notification).order_by(Notification.created_at.desc()).limit(200)
    )
    return [
        UpdateView(
            id=n.id,
            kind=n.kind,
            title=n.title,
            body=n.body,
            action=n.action,
            recording_id=n.recording_id,
            conversation_id=n.conversation_id,
            read=n.read_at is not None,
            created_at=n.created_at,
        )
        for n in rows
    ]


@router.post("/updates/read", operation_id="markUpdatesRead", status_code=204)
async def mark_updates_read(_: CurrentSession, db: Db) -> None:
    await db.execute(
        update(Notification).where(Notification.read_at.is_(None)).values(read_at=func.now())
    )
    await db.commit()


# Search (screens 05, 05b, S4)


class SearchHit(BaseModel):
    where: Literal["transcript", "summary", "note"]
    snippet: str = Field(description="Matched words are wrapped in [[ ]]")
    at_ms: int = Field(json_schema_extra=INT64)
    segment_id: Annotated[int, Field(json_schema_extra=INT64)] | None = None


class SearchResult(BaseModel):
    conversation: ConversationSummary
    hits: list[SearchHit]


@router.get("/search", operation_id="search")
async def search(
    _: CurrentSession, db: Db, q: str = Query(min_length=1, max_length=200)
) -> list[SearchResult]:
    query = func.websearch_to_tsquery("simple", q)
    headline = "StartSel=[[, StopSel=]], MaxWords=22, MinWords=8, ShortWord=2"
    results: dict[UUID, SearchResult] = {}
    conversations: dict[UUID, tuple[Conversation, Recording]] = {}

    async def conversation_for(
        recording_id: UUID, at_ms: int
    ) -> tuple[Conversation, Recording] | None:
        c = await db.scalar(
            select(Conversation)
            .where(
                Conversation.recording_id == recording_id,
                Conversation.start_ms <= at_ms,
                Conversation.end_ms >= at_ms,
            )
            .limit(1)
        )
        if c is None:
            return None
        r = await db.get(Recording, recording_id)
        return (c, r) if r else None

    def add(c: Conversation, r: Recording, hit: SearchHit) -> None:
        if c.id not in results:
            results[c.id] = SearchResult(conversation=conversation_view(c, r), hits=[])
            conversations[c.id] = (c, r)
        if len(results[c.id].hits) < 5:
            results[c.id].hits.append(hit)

    summaries = await db.execute(
        select(
            Conversation,
            Recording,
            func.ts_headline(
                "simple",
                Conversation.title + literal(" — ") + Conversation.summary,
                query,
                headline,
            ),
        )
        .join(Recording, Recording.id == Conversation.recording_id)
        .where(Conversation.search.op("@@")(query))
        .order_by(Recording.started_at.desc())
        .limit(50)
    )
    for c, r, snippet in summaries.all():
        add(c, r, SearchHit(where="summary", snippet=snippet, at_ms=c.start_ms))

    notes = await db.execute(
        select(Note, func.ts_headline("simple", Note.text, query, headline))
        .where(func.to_tsvector("simple", Note.text).op("@@")(query))
        .limit(50)
    )
    for note, snippet in notes.all():
        c = await db.get(Conversation, note.conversation_id)
        r = await db.get(Recording, c.recording_id) if c else None
        if c and r:
            add(c, r, SearchHit(where="note", snippet=snippet, at_ms=c.start_ms))

    segments = await db.execute(
        select(
            TranscriptSegment, func.ts_headline("simple", TranscriptSegment.text, query, headline)
        )
        .where(TranscriptSegment.search.op("@@")(query))
        .order_by(TranscriptSegment.recording_id, TranscriptSegment.idx)
        .limit(300)
    )
    for segment, snippet in segments.all():
        found = await conversation_for(segment.recording_id, segment.start_ms)
        if found:
            add(
                *found,
                SearchHit(
                    where="transcript",
                    snippet=snippet,
                    at_ms=segment.start_ms,
                    segment_id=segment.id,
                ),
            )

    return sorted(results.values(), key=lambda result: result.conversation.started_at, reverse=True)


# Sync (the phone's offline cache)


class SyncDigest(BaseModel):
    day: str
    state: str
    updated_at: datetime


class SyncView(BaseModel):
    server_time: datetime
    recordings: list[RecordingSummary]
    digests: list[SyncDigest]
    unread_updates: int
    waiting_review: int


@router.get("/sync", operation_id="sync")
async def sync(_: CurrentSession, db: Db, since: datetime | None = None) -> SyncView:
    """What changed since `since` (the last answer's `server_time`). Recordings whose
    `results_version` moved should have their conversations fetched again."""
    now = datetime.now(UTC)
    cutoff = (since or datetime(2000, 1, 1, tzinfo=UTC)) - timedelta(seconds=5)
    recordings = await db.scalars(
        select(Recording).where(Recording.updated_at >= cutoff).order_by(Recording.started_at)
    )
    digests = await db.scalars(select(DayDigest).where(DayDigest.updated_at >= cutoff))
    unread = await db.scalar(
        select(func.count()).select_from(Notification).where(Notification.read_at.is_(None))
    )
    waiting = await db.scalar(
        select(func.count()).select_from(ReviewItem).where(ReviewItem.state == ReviewState.WAITING)
    )
    return SyncView(
        server_time=now,
        recordings=[recording_view(r) for r in recordings],
        digests=[
            SyncDigest(day=d.day.isoformat(), state=d.state, updated_at=d.updated_at)
            for d in digests
        ],
        unread_updates=unread or 0,
        waiting_review=waiting or 0,
    )


class RecordingConversations(BaseModel):
    recording: RecordingSummary
    conversations: list[ConversationSummary]


@router.get("/recordings/{recording_id}/conversations", operation_id="getRecordingConversations")
async def recording_conversations(
    recording_id: UUID, _: CurrentSession, db: Db
) -> RecordingConversations:
    recording = await db.get(Recording, recording_id)
    if recording is None:
        raise error(status.HTTP_404_NOT_FOUND, "not_found", "No such recording.")
    rows = await db.scalars(
        select(Conversation)
        .where(Conversation.recording_id == recording_id)
        .order_by(Conversation.start_ms)
    )
    return RecordingConversations(
        recording=recording_view(recording),
        conversations=await with_people(db, [conversation_view(c, recording) for c in rows]),
    )
