"""Root's writes outlive a reprocess (docs/architecture/agent-feed.md, "Reprocessing").

Reprocessing replaces a recording's conversations with new ones. Before the old ones go:

- each new conversation lists the old ones it overlaps in time (`replaces`);
- her annotation and corrections move to the new conversation that overlaps the old one most,
  flagged `recheck`, and the corrections are applied to the new conversation;
- item sync states move to the matching new item: the same normalised text, else the same kind
  with text similarity >= 0.8 within two minutes; otherwise `item.sync_lost` is reported.
"""

import re
from dataclasses import dataclass, field
from datetime import datetime
from difflib import SequenceMatcher
from typing import Any
from uuid import UUID

from sqlalchemy import delete, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from roudio.models import (
    AgentAnnotation,
    AgentCorrection,
    AgentItemSync,
    Conversation,
    GeneratedItem,
    Notification,
    ReviewItem,
    ReviewState,
)

SIMILARITY = 0.8
NEAR_MS = 2 * 60 * 1000


@dataclass
class _OldConversation:
    id: UUID
    start_ms: int
    end_ms: int


@dataclass
class _OldSync:
    item_id: int
    conversation_id: UUID
    kind: str
    text: str
    at_ms: int
    state: str
    system: str | None
    external_id: str | None
    updated_at: datetime


@dataclass
class Before:
    """A recording's conversations and synced items before it's written up again."""

    conversations: list[_OldConversation]
    syncs: list[_OldSync]


@dataclass
class Carried:
    new_ids: list[UUID] = field(default_factory=list)
    removed_ids: list[UUID] = field(default_factory=list)
    sync_lost: list[dict[str, Any]] = field(default_factory=list)


def normalise(text: str) -> str:
    """Trimmed, lower-case, punctuation dropped."""
    return " ".join(re.sub(r"[^\w\s]", "", text.lower()).split())


def _overlap(a_start: int, a_end: int, b_start: int, b_end: int) -> int:
    return max(0, min(a_end, b_end) - max(a_start, b_start))


async def snapshot(db: AsyncSession, recording_id: UUID) -> Before:
    conversations = [
        _OldConversation(c.id, c.start_ms, c.end_ms)
        for c in await db.scalars(
            select(Conversation).where(Conversation.recording_id == recording_id)
        )
    ]
    rows = await db.execute(
        select(GeneratedItem, AgentItemSync)
        .join(AgentItemSync, AgentItemSync.generated_item_id == GeneratedItem.id)
        .join(Conversation, Conversation.id == GeneratedItem.conversation_id)
        .where(Conversation.recording_id == recording_id)
    )
    syncs = [
        _OldSync(
            item_id=g.id,
            conversation_id=g.conversation_id,
            kind=g.kind,
            text=g.text,
            at_ms=g.at_ms,
            state=s.state,
            system=s.system,
            external_id=s.external_id,
            updated_at=s.updated_at,
        )
        for g, s in rows.all()
    ]
    return Before(conversations, syncs)


async def carry_over(
    db: AsyncSession,
    before: Before,
    new: list[tuple[Conversation, list[GeneratedItem]]],
) -> Carried:
    """Move Root's writes onto the new conversations, then delete the old ones.

    The new conversations and items must be flushed (they need ids). The caller commits.
    """
    carried = Carried(
        new_ids=[c.id for c, _ in new], removed_ids=[c.id for c in before.conversations]
    )
    old_ids = set(carried.removed_ids)
    new_by_id = {c.id: c for c, _ in new}

    # Which old conversations each new one replaces, and where each old one's notes go.
    target: dict[UUID, UUID] = {}
    for old in before.conversations:
        best, best_overlap = None, 0
        for c, _ in new:
            o = _overlap(old.start_ms, old.end_ms, c.start_ms, c.end_ms)
            if o > 0:
                c.replaces = [*c.replaces, old.id]
            if o > best_overlap:
                best, best_overlap = c.id, o
        if best is not None:
            target[old.id] = best

    if old_ids:
        await _move_annotations(db, old_ids, target)
        await _move_corrections(db, old_ids, target, new_by_id)
        for old_id, new_id in target.items():
            await db.execute(
                update(Notification)
                .where(Notification.conversation_id == old_id, Notification.kind.like("agent_%"))
                .values(conversation_id=new_id)
            )
    await _move_syncs(db, before.syncs, new, target, carried)

    if old_ids:
        await db.execute(delete(Conversation).where(Conversation.id.in_(old_ids)))
    await db.flush()
    return carried


async def _move_annotations(db: AsyncSession, old_ids: set[UUID], target: dict[UUID, UUID]) -> None:
    rows = list(
        await db.scalars(
            select(AgentAnnotation).where(AgentAnnotation.conversation_id.in_(old_ids))
        )
    )
    by_target: dict[UUID, list[AgentAnnotation]] = {}
    for a in rows:
        if a.conversation_id in target:
            by_target.setdefault(target[a.conversation_id], []).append(a)
    moves: list[tuple[UUID, UUID]] = []
    for new_id, group in by_target.items():
        # Two old conversations can merge into one: the most recent annotation wins. The others
        # stay on their old conversation and go with it.
        group.sort(key=lambda a: a.updated_at, reverse=True)
        moves.append((group[0].conversation_id, new_id))
    for old_id, new_id in moves:
        await db.execute(
            update(AgentAnnotation)
            .where(AgentAnnotation.conversation_id == old_id)
            .values(conversation_id=new_id, recheck=True)
        )


async def _move_corrections(
    db: AsyncSession,
    old_ids: set[UUID],
    target: dict[UUID, UUID],
    new_by_id: dict[UUID, Conversation],
) -> None:
    rows = list(
        await db.scalars(
            select(AgentCorrection).where(AgentCorrection.conversation_id.in_(old_ids))
        )
    )
    winners: dict[tuple[UUID, str], AgentCorrection] = {}
    for c in rows:
        new_id = target.get(c.conversation_id)
        if new_id is None:
            continue
        key = (new_id, c.field)
        if key not in winners or c.created_at > winners[key].created_at:
            winners[key] = c
    for (new_id, fieldname), c in winners.items():
        conversation = new_by_id[new_id]
        # Undo now goes back to what the new write-up says.
        original = str(getattr(conversation, fieldname))
        setattr(conversation, fieldname, c.value)
        await db.execute(
            update(AgentCorrection)
            .where(AgentCorrection.id == c.id)
            .values(conversation_id=new_id, recheck=True, original_value=original)
        )


async def _move_syncs(
    db: AsyncSession,
    syncs: list[_OldSync],
    new: list[tuple[Conversation, list[GeneratedItem]]],
    target: dict[UUID, UUID],
    carried: Carried,
) -> None:
    items = [i for _, group in new for i in group]
    claimed: set[int] = set()
    accepted: list[int] = []
    for s in sorted(syncs, key=lambda s: s.updated_at):
        wanted = normalise(s.text)
        match = next(
            (i for i in items if i.id not in claimed and normalise(i.text) == wanted), None
        )
        if match is None:
            best = 0.0
            for i in items:
                if i.id in claimed or i.kind != s.kind or abs(i.at_ms - s.at_ms) > NEAR_MS:
                    continue
                ratio = SequenceMatcher(None, wanted, normalise(i.text)).ratio()
                if ratio >= SIMILARITY and ratio > best:
                    match, best = i, ratio
        if match is None:
            carried.sync_lost.append(
                {
                    "conversation_id": str(target.get(s.conversation_id, s.conversation_id)),
                    "old_item_id": s.item_id,
                    "old_text": s.text,
                    "state": s.state,
                    "system": s.system,
                    "external_id": s.external_id,
                }
            )
            continue
        claimed.add(match.id)
        await db.execute(
            update(AgentItemSync)
            .where(AgentItemSync.generated_item_id == s.item_id)
            .values(generated_item_id=match.id)
        )
        if s.state == "synced":
            accepted.append(match.id)
    if accepted:
        await db.execute(
            update(ReviewItem)
            .where(
                ReviewItem.generated_item_id.in_(accepted),
                ReviewItem.state == ReviewState.WAITING.value,
            )
            .values(state=ReviewState.ACCEPTED.value)
        )
