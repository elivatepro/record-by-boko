"""Agent API keys: `rdo_agent_…`, shown once when made; only the SHA-256 is stored."""

import hashlib
import secrets
from collections.abc import Iterable

from sqlalchemy import select
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


async def add_hashed_keys(db: AsyncSession, spec: str) -> list[str]:
    """Store keys given as `name:scopes:sha256;…` that aren't there yet; returns their names.

    A key that's already stored, even revoked, is left alone, so revoking one sticks.
    """
    added = []
    for entry in filter(None, (e.strip() for e in spec.split(";"))):
        name, scopes, digest = entry.split(":")
        digest = digest.strip().lower()
        if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
            raise ValueError(f"{name}: not a SHA-256 hex digest")
        if await db.scalar(select(AgentKey.id).where(AgentKey.key_hash == digest)) is None:
            db.add(
                AgentKey(name=name.strip(), key_hash=digest, scopes=check_scopes(scopes.split(",")))
            )
            added.append(name.strip())
    await db.commit()
    return added


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
