"""Agent API keys: `rdo_agent_…`, shown once when made; only the SHA-256 is stored."""

import hashlib
import secrets
from collections.abc import Iterable

from sqlalchemy.ext.asyncio import AsyncSession

from roudio.models import AgentKey

PREFIX = "rdo_agent_"
SCOPES = ("read", "annotate")


def new_key() -> str:
    return PREFIX + secrets.token_urlsafe(32)


def hash_key(key: str) -> str:
    return hashlib.sha256(key.encode()).hexdigest()


def check_scopes(scopes: Iterable[str]) -> list[str]:
    chosen = sorted({s.strip() for s in scopes if s.strip()})
    unknown = [s for s in chosen if s not in SCOPES]
    if unknown or not chosen:
        raise ValueError(f"scopes must be some of {', '.join(SCOPES)}; got {chosen}")
    return chosen


async def create_key(
    db: AsyncSession, name: str, scopes: Iterable[str], key: str | None = None
) -> tuple[AgentKey, str]:
    """Store a new key and return it with its plain text (the only time that exists)."""
    plain = key or new_key()
    if not plain.startswith(PREFIX):
        raise ValueError(f"agent keys start with {PREFIX}")
    row = AgentKey(name=name, key_hash=hash_key(plain), scopes=check_scopes(scopes))
    db.add(row)
    await db.commit()
    return row, plain
