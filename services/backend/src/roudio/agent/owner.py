"""The owner's side of Root's writes: undo from Updates, and owner edits that win over hers."""

from uuid import UUID

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from roudio.agent import events
from roudio.agent.audit import log_action
from roudio.models import AgentCorrection, Conversation, Recording


async def undo_correction(db: AsyncSession, correction: AgentCorrection) -> None:
    """Put Roudio's value back (unless the owner has changed it since) and drop the correction."""
    conversation = await db.get(Conversation, correction.conversation_id)
    if (
        conversation is not None
        and str(getattr(conversation, correction.field)) == correction.value
    ):
        setattr(conversation, correction.field, correction.original_value)
        recording = await db.get(Recording, conversation.recording_id)
        if recording:
            recording.results_version += 1
    log_action(
        db,
        None,
        "undo correction",
        "conversation",
        correction.conversation_id,
        {correction.field: correction.value},
        {correction.field: correction.original_value},
        actor="owner",
    )
    await events.conversations_updated(db, [correction.conversation_id])
    await db.delete(correction)


async def owner_renamed(db: AsyncSession, conversation_id: UUID, title: str) -> None:
    """The owner's own title replaces Root's title correction."""
    correction = await db.scalar(
        select(AgentCorrection).where(
            AgentCorrection.conversation_id == conversation_id, AgentCorrection.field == "title"
        )
    )
    if correction is not None:
        log_action(
            db,
            None,
            "owner rename",
            "conversation",
            conversation_id,
            {"title": correction.value},
            {"title": title},
            actor="owner",
        )
        await db.delete(correction)
    await events.conversations_updated(db, [conversation_id])
