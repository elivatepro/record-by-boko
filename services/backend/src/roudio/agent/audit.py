"""The `agent_action` audit log: every agent write, and the owner undoing one."""

from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from roudio.models import AgentAction, AgentKey


def log_action(
    db: AsyncSession,
    key: AgentKey | None,
    endpoint: str,
    target_type: str,
    target_id: object,
    old: Any,
    new: Any,
    confidence: float | None = None,
    reason: str | None = None,
    actor: str = "agent",
) -> None:
    db.add(
        AgentAction(
            key_id=key.id if key else None,
            actor=actor,
            endpoint=endpoint,
            target_type=target_type,
            target_id=str(target_id),
            old_value=old,
            new_value=new,
            confidence=confidence,
            reason=reason,
        )
    )
