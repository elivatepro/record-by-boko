"""The change feed's events and its opaque cursor.

Events are written under a transaction-scoped advisory lock, so they commit in `seq` order and a
reader never skips one that commits late. Conversations are rendered when the feed is read, so an
event only names the conversation.
"""

import base64
import binascii
from collections.abc import Iterable
from typing import Any
from uuid import UUID

from sqlalchemy import select, text
from sqlalchemy.ext.asyncio import AsyncSession

from roudio.models import AgentEvent, Conversation

_FEED_LOCK = 0x524F4F54  # "ROOT"


async def emit(db: AsyncSession, type: str, about: UUID | None = None, **data: Any) -> None:
    """Add an event to the current transaction (it shows once the caller commits)."""
    await db.execute(text("SELECT pg_advisory_xact_lock(:key)"), {"key": _FEED_LOCK})
    db.add(AgentEvent(type=type, conversation_id=about, data=data))


async def conversations_updated(db: AsyncSession, ids: Iterable[UUID]) -> None:
    """`conversation.updated` for each conversation the owner changed."""
    for conversation_id in dict.fromkeys(ids):
        await emit(db, "conversation.updated", conversation_id)


async def recording_updated(db: AsyncSession, recording_id: UUID) -> None:
    """`conversation.updated` for every conversation in a recording (a speaker was renamed)."""
    ids = await db.scalars(select(Conversation.id).where(Conversation.recording_id == recording_id))
    await conversations_updated(db, list(ids))


def encode_cursor(position: int) -> str:
    return base64.urlsafe_b64encode(f"v1:{position}".encode()).rstrip(b"=").decode()


def decode_cursor(cursor: str | None) -> int:
    """0 for no cursor; ValueError for one Roudio didn't hand out."""
    if not cursor:
        return 0
    try:
        raw = base64.urlsafe_b64decode(cursor + "=" * (-len(cursor) % 4)).decode()
    except (binascii.Error, UnicodeDecodeError) as exc:
        raise ValueError("bad cursor") from exc
    version, _, position = raw.partition(":")
    if version != "v1" or not position.isdigit():
        raise ValueError("bad cursor")
    return int(position)
