"""The agent feed for Root (docs/architecture/agent-feed.md), with the stand-in pipeline."""

import shutil
from pathlib import Path
from typing import Any

import pytest
from httpx import AsyncClient
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from roudio.agent.events import decode_cursor, encode_cursor
from roudio.agent.keys import create_key
from roudio.models import (
    AgentEvent,
    Conversation,
    GeneratedItem,
    Job,
    Recording,
    ReviewItem,
    Speaker,
)
from roudio.pipeline.brain import BrainResult, FakeBrain, Item, Line, Understood
from roudio.pipeline.digest import build_digest
from roudio.pipeline.process import DIGEST, process_recording
from tests.conftest import job_for, sign_in, uploaded

pytestmark = pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="needs ffmpeg")


class ScriptedBrain(FakeBrain):
    """One conversation over the whole recording, with the items a test asks for."""

    def __init__(self, items: list[tuple[str, str]], title: str = "Oryx check-in") -> None:
        self.items = items
        self.title = title
        self.empty = False

    async def understand(self, lines: list[Line]) -> BrainResult[list[Understood]]:
        if self.empty:
            return BrainResult([])
        first, last = lines[0].idx, lines[-1].idx
        items = [Item(kind, text, [first]) for kind, text in self.items]
        return BrainResult(
            [Understood(first, last, "conversation", self.title, "A summary.", [first], items)]
        )


@pytest.fixture
async def root(db: AsyncSession) -> dict[str, str]:
    _, key = await create_key(db, "root", ["read", "annotate"])
    return {"Authorization": f"Bearer {key}"}


@pytest.fixture
async def owner(client: AsyncClient) -> dict[str, str]:
    tokens = await sign_in(client)
    return {"Authorization": f"Bearer {tokens['access_token']}"}


@pytest.fixture
def brain(pipeline: Any) -> ScriptedBrain:
    scripted = ScriptedBrain([("task", "Send Andy the build"), ("decision", "Ship on Friday")])
    pipeline.brain = scripted
    return scripted


@pytest.fixture
async def recording(db: AsyncSession, s3: Any, audio_file: Path, brain: ScriptedBrain) -> Recording:
    recording = await uploaded(db, s3, audio_file)
    await process_recording(db, job_for(recording))
    return recording


async def _feed(client: AsyncClient, root: dict[str, str], cursor: str | None = None) -> Any:
    params = {"cursor": cursor} if cursor else {}
    response = await client.get("/agent/v1/feed", headers=root, params=params)
    assert response.status_code == 200, response.text
    return response.json()


async def _conversation(db: AsyncSession) -> Conversation:
    found: Conversation | None = await db.scalar(select(Conversation))
    assert found is not None
    return found


# Access


async def test_only_agent_keys_open_the_agent_api(
    client: AsyncClient, db: AsyncSession, root: dict[str, str], owner: dict[str, str]
) -> None:
    assert (await client.get("/agent/v1/feed")).status_code == 401
    # The owner's app session doesn't work there, and an agent key doesn't work on the app.
    assert (await client.get("/agent/v1/feed", headers=owner)).status_code == 401
    assert (await client.get("/days/2026-10-11", headers=root)).status_code == 401
    bad = {"Authorization": "Bearer rdo_agent_not-a-real-key"}
    assert (await client.get("/agent/v1/feed", headers=bad)).status_code == 401
    assert (await client.get("/agent/v1/feed", headers=root)).status_code == 200


async def test_scopes_and_revoked_keys(client: AsyncClient, db: AsyncSession) -> None:
    row, key = await create_key(db, "reader", ["read"])
    headers = {"Authorization": f"Bearer {key}"}
    assert (await client.get("/agent/v1/feed", headers=headers)).status_code == 200
    put = await client.post(
        "/agent/v1/conversations/00000000-0000-0000-0000-000000000000/corrections",
        headers=headers,
        json={"field": "title", "value": "x"},
    )
    assert put.status_code == 403
    assert put.json()["detail"]["code"] == "missing_scope"
    await db.execute(
        update(type(row)).where(type(row).id == row.id).values(revoked_at=row.created_at)
    )
    await db.commit()
    assert (await client.get("/agent/v1/feed", headers=headers)).status_code == 401


async def test_sixty_requests_a_minute(client: AsyncClient, root: dict[str, str]) -> None:
    for _ in range(60):
        assert (await client.get("/agent/v1/actions", headers=root)).status_code == 200
    limited = await client.get("/agent/v1/actions", headers=root)
    assert limited.status_code == 429
    assert int(limited.headers["Retry-After"]) >= 1


def test_the_cursor_is_opaque_and_checked() -> None:
    assert decode_cursor(encode_cursor(1042)) == 1042
    assert decode_cursor(None) == 0
    for bad in ("1042", "djI6MTA0Mg", "!!"):
        with pytest.raises(ValueError):
            decode_cursor(bad)


# The feed and reads


async def test_the_feed_has_finished_conversations_and_pages(
    client: AsyncClient, db: AsyncSession, root: dict[str, str], recording: Recording
) -> None:
    body = await _feed(client, root)
    ready = [e for e in body["events"] if e["type"] == "conversation.ready"]
    assert len(ready) == 1
    conversation = ready[0]["conversation"]
    assert conversation["recording_id"] == str(recording.id)
    assert conversation["title"] == "Oryx check-in"
    assert conversation["replaces"] == []
    assert conversation["annotation"] is None and conversation["annotation_version"] == 0
    assert {i["text"] for i in conversation["items"]} == {"Send Andy the build", "Ship on Friday"}
    task = next(i for i in conversation["items"] if i["kind"] == "task")
    assert task["review_state"] == "waiting" and task["sync"] is None
    assert conversation["speakers"] and all(s["name"] is None for s in conversation["speakers"])
    assert "segments" not in conversation  # summary first: the transcript is its own read
    assert body["has_more"] is False

    # Nothing new after the cursor; a bad cursor is refused.
    again = await _feed(client, root, body["next_cursor"])
    assert again["events"] == [] and again["next_cursor"] == body["next_cursor"]
    bad = await client.get("/agent/v1/feed", headers=root, params={"cursor": "nope"})
    assert bad.status_code == 400

    # Paging: has_more until the end.
    for _ in range(3):
        db.add(
            AgentEvent(
                type="digest.ready",
                data={"day": "2026-10-11", "updated_at": "2026-10-12T00:00:00Z"},
            )
        )
    await db.commit()
    first = (await client.get("/agent/v1/feed", headers=root, params={"limit": 2})).json()
    assert len(first["events"]) == 2 and first["has_more"] is True
    rest = await _feed(client, root, first["next_cursor"])
    assert len(rest["events"]) == 2 and rest["has_more"] is False


async def test_conversation_transcript_and_digest_reads(
    client: AsyncClient, db: AsyncSession, root: dict[str, str], recording: Recording
) -> None:
    c = await _conversation(db)
    one = (await client.get(f"/agent/v1/conversations/{c.id}", headers=root)).json()
    assert one["id"] == str(c.id) and one["results_version"] == 1
    transcript = (
        await client.get(f"/agent/v1/conversations/{c.id}/transcript", headers=root)
    ).json()
    assert transcript["segments"]
    assert {"idx", "speaker", "speaker_name", "start_ms", "end_ms", "text", "edited"} == set(
        transcript["segments"][0]
    )
    missing = await client.get(
        "/agent/v1/conversations/00000000-0000-0000-0000-000000000000", headers=root
    )
    assert missing.status_code == 404

    assert (await client.get("/agent/v1/days/2026-10-11/digest", headers=root)).status_code == 404
    await build_digest(db, Job(id=2, kind=DIGEST, payload={"day": "2026-10-11"}, attempts=1))
    digest = await client.get("/agent/v1/days/2026-10-11/digest", headers=root)
    assert digest.status_code == 200, digest.text
    assert digest.json()["digest"]["state"] == "finished"
    types = [e["type"] for e in (await _feed(client, root))["events"]]
    assert types[-1] == "digest.ready"


async def test_an_empty_recording_says_so(
    client: AsyncClient,
    db: AsyncSession,
    s3: Any,
    audio_file: Path,
    brain: ScriptedBrain,
    root: dict[str, str],
) -> None:
    brain.empty = True
    recording = await uploaded(db, s3, audio_file)
    await process_recording(db, job_for(recording))
    events = (await _feed(client, root))["events"]
    assert [e["type"] for e in events] == ["recording.empty"]
    assert events[0]["recording_id"] == str(recording.id)
    assert events[0]["speech_ms"] > 0


async def test_owner_edits_come_through_but_roots_own_writes_dont(
    client: AsyncClient,
    db: AsyncSession,
    root: dict[str, str],
    owner: dict[str, str],
    recording: Recording,
) -> None:
    c = await _conversation(db)
    cursor = (await _feed(client, root))["next_cursor"]
    put = await client.put(
        f"/agent/v1/conversations/{c.id}/annotation",
        headers={**root, "If-Match": "0"},
        json={"category": "client_work"},
    )
    assert put.status_code == 200
    assert (await _feed(client, root, cursor))["events"] == []

    await client.put(f"/conversations/{c.id}/note", headers=owner, json={"text": "Call back"})
    events = (await _feed(client, root, cursor))["events"]
    assert [e["type"] for e in events] == ["conversation.updated"]
    assert events[0]["conversation"]["note"] == "Call back"
    cursor = (await _feed(client, root, cursor))["next_cursor"]

    review = await db.scalar(select(ReviewItem))
    assert review is not None
    await client.patch(f"/review/{review.id}", headers=owner, json={"state": "done"})
    await client.patch(f"/conversations/{c.id}", headers=owner, json={"title": "Owner title"})
    events = (await _feed(client, root, cursor))["events"]
    assert [e["type"] for e in events] == ["conversation.updated", "conversation.updated"]
    assert events[-1]["conversation"]["title"] == "Owner title"


# Writes


async def test_annotation_needs_the_current_version(
    client: AsyncClient,
    db: AsyncSession,
    root: dict[str, str],
    owner: dict[str, str],
    recording: Recording,
) -> None:
    c = await _conversation(db)
    url = f"/agent/v1/conversations/{c.id}/annotation"
    annotation = {
        "category": "client_work",
        "tags": ["oryx", "sprint"],
        "links": [
            {
                "system": "notion",
                "kind": "project",
                "external_id": "n1",
                "url": "https://n",
                "label": "Oryx App",
            },
            {
                "system": "ticktick",
                "kind": "task",
                "external_id": "t1",
                "url": None,
                "label": "Call mum",
            },
        ],
        "needs_review": [{"what": "speaker_2 may be Sam or Samuel", "options": ["Sam", "Samuel"]}],
        "confidence": 0.86,
        "reason": "Andy named; matches Oryx client in Notion",
    }
    assert (await client.put(url, headers=root, json=annotation)).status_code == 428
    first = await client.put(url, headers={**root, "If-Match": "0"}, json=annotation)
    assert first.status_code == 200, first.text
    body = first.json()
    assert body["annotation_version"] == 1
    assert body["annotation"]["tags"] == ["oryx", "sprint"]
    assert body["annotation"]["updated_at"] and body["annotation"]["recheck"] is False

    stale = await client.put(url, headers={**root, "If-Match": "0"}, json={"category": "x"})
    assert stale.status_code == 409
    detail = stale.json()["detail"]
    assert detail["code"] == "stale_annotation"
    assert detail["annotation_version"] == 1 and detail["annotation"]["category"] == "client_work"

    second = await client.put(
        url, headers={**root, "If-Match": '"1"'}, json={"category": "personal"}
    )
    assert second.json()["annotation_version"] == 2
    assert second.json()["annotation"]["links"] == []

    bad = await client.put(
        url, headers={**root, "If-Match": "2"}, json={"links": [{"system": "jira", "kind": "task"}]}
    )
    assert bad.status_code == 422

    # The owner's app shows her links and questions.
    await client.put(url, headers={**root, "If-Match": "2"}, json=annotation)
    detail = (await client.get(f"/conversations/{c.id}", headers=owner)).json()
    assert detail["root"]["links"][0]["label"] == "Oryx App"
    assert detail["root"]["needs_review"][0]["options"] == ["Sam", "Samuel"]


async def test_a_title_correction_shows_in_updates_and_can_be_undone(
    client: AsyncClient,
    db: AsyncSession,
    root: dict[str, str],
    owner: dict[str, str],
    recording: Recording,
) -> None:
    c = await _conversation(db)
    url = f"/agent/v1/conversations/{c.id}/corrections"
    bad = await client.post(url, headers=root, json={"field": "kind", "value": "meeting"})
    assert bad.status_code == 422
    assert (
        await client.post(url, headers=root, json={"field": "summary", "value": "x"})
    ).status_code == 422

    fixed = await client.post(
        url,
        headers=root,
        json={
            "field": "title",
            "value": "Oryx sprint check-in (existing client)",
            "confidence": 0.95,
            "reason": "Andy is an existing Oryx client",
        },
    )
    assert fixed.status_code == 200, fixed.text
    body = fixed.json()
    assert body["title"] == "Oryx sprint check-in (existing client)"
    assert body["annotation"]["corrections"][0]["original_value"] == "Oryx check-in"
    owner_view = (await client.get(f"/conversations/{c.id}", headers=owner)).json()
    assert owner_view["conversation"]["title"] == "Oryx sprint check-in (existing client)"

    updates = (await client.get("/updates", headers=owner)).json()
    renamed = next(u for u in updates if u["kind"] == "agent_correction")
    assert (
        renamed["title"]
        == "Root renamed “Oryx check-in” to “Oryx sprint check-in (existing client)”"
    )
    assert renamed["action"] == "undo"

    cursor = (await _feed(client, root))["next_cursor"]
    assert (await client.post(f"/updates/{renamed['id']}/undo", headers=owner)).status_code == 204
    after = (await client.get(f"/agent/v1/conversations/{c.id}", headers=root)).json()
    assert after["title"] == "Oryx check-in" and after["annotation"] is None
    assert (await client.post(f"/updates/{renamed['id']}/undo", headers=owner)).status_code == 404
    events = (await _feed(client, root, cursor))["events"]
    assert [e["type"] for e in events] == ["conversation.updated"]

    log = (await client.get("/agent/v1/actions", headers=root)).json()["actions"]
    assert [(a["actor"], a["endpoint"]) for a in log] == [
        ("agent", "POST corrections"),
        ("owner", "undo correction"),
    ]
    assert log[0]["old_value"] == {"title": "Oryx check-in"}
    assert log[0]["confidence"] == 0.95 and log[0]["reason"] == "Andy is an existing Oryx client"


async def test_a_kind_correction_and_the_owners_rename_wins(
    client: AsyncClient,
    db: AsyncSession,
    root: dict[str, str],
    owner: dict[str, str],
    recording: Recording,
) -> None:
    c = await _conversation(db)
    url = f"/agent/v1/conversations/{c.id}/corrections"
    kind = await client.post(url, headers=root, json={"field": "kind", "value": "voice_note"})
    assert kind.json()["kind"] == "voice_note"
    await client.post(url, headers=root, json={"field": "title", "value": "Root's title"})
    await client.patch(f"/conversations/{c.id}", headers=owner, json={"title": "Mine"})
    after = (await client.get(f"/agent/v1/conversations/{c.id}", headers=root)).json()
    assert after["title"] == "Mine"
    assert [x["field"] for x in after["annotation"]["corrections"]] == ["kind"]


async def test_root_names_speakers_but_never_over_the_owner(
    client: AsyncClient,
    db: AsyncSession,
    root: dict[str, str],
    owner: dict[str, str],
    recording: Recording,
) -> None:
    speakers = list(await db.scalars(select(Speaker).order_by(Speaker.label)))
    assert len(speakers) == 2
    first, second = speakers
    named = await client.patch(
        f"/agent/v1/speakers/{second.id}",
        headers=root,
        json={"name": "Andy", "confidence": 0.9, "reason": "addressed by name at 13:20"},
    )
    assert named.status_code == 200, named.text
    assert named.json() == {
        "id": second.id,
        "label": second.label,
        "name": "Andy",
        "name_source": "root",
    }
    updates = (await client.get("/updates", headers=owner)).json()
    assert updates[0]["title"] == "Root named speaker 2 Andy"

    await client.patch(f"/speakers/{first.id}", headers=owner, json={"name": "Priya"})
    refused = await client.patch(
        f"/agent/v1/speakers/{first.id}", headers=root, json={"name": "Sam"}
    )
    assert refused.status_code == 409
    assert refused.json()["detail"]["code"] == "owner_set"
    # The owner may rename one Root named.
    await client.patch(f"/speakers/{second.id}", headers=owner, json={"name": "Andrew"})
    c = await _conversation(db)
    seen = (await client.get(f"/agent/v1/conversations/{c.id}", headers=root)).json()["speakers"]
    assert {(s["name"], s["name_source"]) for s in seen} == {
        ("Priya", "owner"),
        ("Andrew", "owner"),
    }


async def test_item_sync_accepts_the_review_item(
    client: AsyncClient, db: AsyncSession, root: dict[str, str], recording: Recording
) -> None:
    task = await db.scalar(select(GeneratedItem).where(GeneratedItem.kind == "task"))
    assert task is not None
    url = f"/agent/v1/items/{task.id}/sync"
    assert (await client.put(url, headers=root, json={"state": "synced"})).status_code == 422
    synced = await client.put(
        url,
        headers=root,
        json={
            "state": "synced",
            "system": "clickup",
            "external_id": "86c1",
            "url": "https://c/86c1",
        },
    )
    assert synced.status_code == 200, synced.text
    assert synced.json()["review_state"] == "accepted"
    assert synced.json()["sync"]["external_id"] == "86c1"
    assert (
        await client.put("/agent/v1/items/999999/sync", headers=root, json={"state": "dismissed"})
    ).status_code == 404


# Reprocessing


async def test_reprocessing_carries_roots_writes_over(
    client: AsyncClient,
    db: AsyncSession,
    root: dict[str, str],
    owner: dict[str, str],
    recording: Recording,
    brain: ScriptedBrain,
) -> None:
    old = await _conversation(db)
    task = await db.scalar(select(GeneratedItem).where(GeneratedItem.kind == "task"))
    decision = await db.scalar(select(GeneratedItem).where(GeneratedItem.kind == "decision"))
    assert task and decision
    await client.put(
        f"/agent/v1/conversations/{old.id}/annotation",
        headers={**root, "If-Match": "0"},
        json={"category": "client_work", "tags": ["oryx"]},
    )
    await client.post(
        f"/agent/v1/conversations/{old.id}/corrections",
        headers=root,
        json={"field": "title", "value": "Oryx sprint (existing client)"},
    )
    await client.put(
        f"/agent/v1/items/{task.id}/sync",
        headers=root,
        json={"state": "synced", "system": "clickup", "external_id": "86c1"},
    )
    await client.put(
        f"/agent/v1/items/{decision.id}/sync",
        headers=root,
        json={"state": "synced", "system": "notion", "external_id": "n9"},
    )
    cursor = (await _feed(client, root))["next_cursor"]

    # Written up again: the task reworded slightly, the decision gone, a new title.
    brain.items = [("task", "Send Andy the builds."), ("key_moment", "Something else entirely")]
    brain.title = "Sprint check-in"
    await process_recording(db, job_for(recording))
    db.expire_all()
    new = await _conversation(db)
    assert new.id != old.id

    events = (await _feed(client, root, cursor))["events"]
    assert [e["type"] for e in events] == [
        "conversation.ready",
        "item.sync_lost",
        "conversation.removed",
    ]
    ready = events[0]["conversation"]
    assert ready["replaces"] == [str(old.id)]
    assert ready["title"] == "Oryx sprint (existing client)"  # her correction, applied again
    annotation = ready["annotation"]
    assert annotation["category"] == "client_work" and annotation["recheck"] is True
    assert annotation["corrections"][0]["original_value"] == "Sprint check-in"
    assert annotation["corrections"][0]["recheck"] is True
    moved = next(i for i in ready["items"] if i["kind"] == "task")
    assert moved["sync"]["external_id"] == "86c1"
    assert moved["review_state"] == "accepted"
    lost = events[1]
    assert lost["conversation_id"] == str(new.id)
    assert (lost["old_text"], lost["system"], lost["external_id"]) == (
        "Ship on Friday",
        "notion",
        "n9",
    )
    assert events[2] == {
        "seq": events[2]["seq"],
        "type": "conversation.removed",
        "id": str(old.id),
        "reason": "reprocessed",
    }

    # Confirming the correction clears the recheck; the undo in Updates follows it.
    confirmed = await client.post(
        f"/agent/v1/conversations/{new.id}/corrections",
        headers=root,
        json={"field": "title", "value": "Oryx sprint (existing client)"},
    )
    assert confirmed.json()["annotation"]["corrections"][0]["recheck"] is False
    # Sending Roudio's own value drops a correction; this one's kind goes, the title stays.
    await client.post(
        f"/agent/v1/conversations/{new.id}/corrections",
        headers=root,
        json={"field": "kind", "value": "event"},
    )
    dropped = await client.post(
        f"/agent/v1/conversations/{new.id}/corrections",
        headers=root,
        json={"field": "kind", "value": "conversation"},
    )
    assert dropped.json()["kind"] == "conversation"
    assert [x["field"] for x in dropped.json()["annotation"]["corrections"]] == ["title"]
    put = await client.put(
        f"/agent/v1/conversations/{new.id}/annotation",
        headers={**root, "If-Match": str(ready["annotation_version"])},
        json={"category": "client_work"},
    )
    assert put.json()["annotation"]["recheck"] is False
    undo = next(
        u for u in (await client.get("/updates", headers=owner)).json() if u["action"] == "undo"
    )
    assert undo["conversation_id"] == str(new.id)
    assert (await client.post(f"/updates/{undo['id']}/undo", headers=owner)).status_code == 204
    after = (await client.get(f"/agent/v1/conversations/{new.id}", headers=root)).json()
    assert after["title"] == "Sprint check-in"


async def test_speaker_names_carry_over_marked_carried(
    client: AsyncClient,
    db: AsyncSession,
    root: dict[str, str],
    recording: Recording,
) -> None:
    speaker = await db.scalar(select(Speaker).order_by(Speaker.label))
    assert speaker is not None
    await client.patch(f"/agent/v1/speakers/{speaker.id}", headers=root, json={"name": "Andy"})
    job = job_for(recording)
    db.expire_all()  # the worker reads with a fresh session
    await process_recording(db, job)
    c = await _conversation(db)
    seen = (await client.get(f"/agent/v1/conversations/{c.id}", headers=root)).json()["speakers"]
    andy = next(s for s in seen if s["name"] == "Andy")
    assert andy["name_source"] == "carried" and andy["id"] != speaker.id
