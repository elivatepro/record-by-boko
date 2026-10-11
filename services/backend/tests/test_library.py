import shutil
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import pytest
from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from roudio.models import Conversation, ReviewItem, TranscriptSegment, Word
from roudio.pipeline.brain import Answer, BrainResult, FakeBrain, Line
from roudio.pipeline.process import process_recording
from tests.conftest import job_for, sign_in, uploaded

pytestmark = pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="needs ffmpeg")


@pytest.fixture
async def auth(client: AsyncClient) -> dict[str, str]:
    tokens = await sign_in(client)
    return {"Authorization": f"Bearer {tokens['access_token']}"}


@pytest.fixture
async def processed(db: AsyncSession, s3: Any, audio_file: Path, pipeline: Any) -> Conversation:
    recording = await uploaded(db, s3, audio_file)
    await process_recording(db, job_for(recording))
    conversation: Conversation | None = await db.scalar(select(Conversation))
    assert conversation is not None
    return conversation


async def test_the_library_needs_a_session(client: AsyncClient) -> None:
    assert (await client.get("/days/2026-10-11")).status_code == 401
    assert (await client.get("/search", params={"q": "x"})).status_code == 401


async def test_a_day_lists_its_recordings_and_conversations(
    client: AsyncClient, auth: dict[str, str], processed: Conversation
) -> None:
    body = (await client.get("/days/2026-10-11", headers=auth)).json()
    assert len(body["recordings"]) == 1
    assert body["recordings"][0]["processing_state"] == "done"
    assert body["conversations"][0]["id"] == str(processed.id)
    assert body["conversations"][0]["started_at"].startswith("2026-10-11T09:00")
    # Who spoke and how many decisions, matching the conversation's own detail.
    summary = body["conversations"][0]
    detail = (await client.get(f"/conversations/{processed.id}", headers=auth)).json()
    decided = [i for i in detail["items"] if i["kind"] == "decision"]
    assert summary["decisions"] == len(decided)
    assert summary["speakers"]
    assert {s["label"] for s in summary["speakers"]} <= {s["label"] for s in detail["speakers"]}


async def test_a_conversation_has_its_notes_transcript_and_sources(
    client: AsyncClient, auth: dict[str, str], processed: Conversation
) -> None:
    body = (await client.get(f"/conversations/{processed.id}", headers=auth)).json()
    assert body["segments"], "transcript"
    indexes = {s["idx"] for s in body["segments"]}
    assert all(set(item["sources"]) <= indexes for item in body["items"])
    assert set(body["summary_sources"]) <= indexes
    assert body["recording"]["transfer_state"] == "uploaded"


async def test_audio_is_a_short_lived_link(
    client: AsyncClient, auth: dict[str, str], processed: Conversation
) -> None:
    body = (await client.get(f"/recordings/{processed.recording_id}/audio", headers=auth)).json()
    assert body["url"].startswith("https://")
    assert ".play.opus" in body["url"]  # the Opus-only copy players get


async def test_ask_answers_from_the_recording_with_checked_sources(
    client: AsyncClient, auth: dict[str, str], processed: Conversation
) -> None:
    answered = await client.post(
        f"/conversations/{processed.id}/ask",
        json={"question": "Who will pull the sample files together?"},
        headers=auth,
    )
    assert answered.status_code == 200, answered.text
    assert answered.json()["enough"] is True and answered.json()["sources"]

    nothing = (
        await client.post(
            f"/conversations/{processed.id}/ask",
            json={"question": "What's the budget?"},
            headers=auth,
        )
    ).json()
    assert nothing["enough"] is False

    body = (await client.get(f"/conversations/{processed.id}", headers=auth)).json()
    assert [a["question"] for a in body["asks"]] == [
        "Who will pull the sample files together?",
        "What's the budget?",
    ]
    assert (
        await client.delete(f"/conversations/{processed.id}/ask", headers=auth)
    ).status_code == 204


async def test_an_answer_citing_nothing_real_becomes_not_enough(
    client: AsyncClient,
    auth: dict[str, str],
    processed: Conversation,
    pipeline: Any,
) -> None:
    class Overconfident(FakeBrain):
        async def ask(self, lines: list[Line], question: str) -> BrainResult[Answer]:
            return BrainResult(Answer("The budget is 5,000 naira.", True, [999]))

    pipeline.brain = Overconfident()
    body = (
        await client.post(
            f"/conversations/{processed.id}/ask", json={"question": "Budget?"}, headers=auth
        )
    ).json()
    assert body == body | {
        "enough": False,
        "sources": [],
        "answer": "There isn't enough in this recording to answer that.",
    }


async def test_edits_win_by_time_and_learn_words(
    client: AsyncClient, auth: dict[str, str], processed: Conversation, db: AsyncSession
) -> None:
    segment = await db.scalar(select(TranscriptSegment).order_by(TranscriptSegment.idx))
    assert segment is not None
    now = datetime.now(UTC)
    edited = await client.patch(
        f"/segments/{segment.id}",
        json={"text": "We asked Osei about it.", "edited_at": now.isoformat()},
        headers=auth,
    )
    assert edited.json()["text"] == "We asked Osei about it." and edited.json()["edited"] is True
    older = await client.patch(
        f"/segments/{segment.id}",
        json={"text": "Older edit", "edited_at": (now - timedelta(minutes=1)).isoformat()},
        headers=auth,
    )
    assert older.json()["text"] == "We asked Osei about it."
    learned = await db.scalar(select(Word).where(Word.term == "Osei"))
    assert learned is not None and learned.source == "learned" and learned.confirmed is False


async def test_review_moves_and_keeps_the_owners_wording(
    client: AsyncClient, auth: dict[str, str], processed: Conversation, db: AsyncSession
) -> None:
    waiting = (await client.get("/review", params={"state": "waiting"}, headers=auth)).json()
    assert waiting
    item = waiting[0]
    changed = (
        await client.patch(
            f"/review/{item['id']}",
            json={"state": "accepted", "text": "Send Daniel the files"},
            headers=auth,
        )
    ).json()
    assert changed["state"] == "accepted" and changed["text"] == "Send Daniel the files"
    assert changed["suggested_text"] == item["suggested_text"]
    row = await db.get(ReviewItem, item["id"])
    assert row is not None


async def test_the_word_list(client: AsyncClient, auth: dict[str, str]) -> None:
    added = (await client.post("/words", json={"term": "Kumasi"}, headers=auth)).json()
    again = (await client.post("/words", json={"term": "kumasi"}, headers=auth)).json()
    assert again["id"] == added["id"]
    assert [w["term"] for w in (await client.get("/words", headers=auth)).json()] == ["Kumasi"]
    assert (await client.delete(f"/words/{added['id']}", headers=auth)).status_code == 204


async def test_search_finds_transcript_and_summary_words(
    client: AsyncClient, auth: dict[str, str], processed: Conversation
) -> None:
    results = (await client.get("/search", params={"q": "shortlist"}, headers=auth)).json()
    assert results and results[0]["conversation"]["id"] == str(processed.id)
    assert any("[[shortlist]]" in hit["snippet"] for hit in results[0]["hits"])
    assert (await client.get("/search", params={"q": "zebra"}, headers=auth)).json() == []


async def test_sync_reports_changes_since_last_time(
    client: AsyncClient, auth: dict[str, str], processed: Conversation
) -> None:
    first = (await client.get("/sync", headers=auth)).json()
    assert first["recordings"][0]["results_version"] == 1
    later = (await client.get("/sync", params={"since": first["server_time"]}, headers=auth)).json()
    assert later["recordings"] == [] or later["recordings"][0]["results_version"] == 1


async def test_a_conversation_can_be_renamed(
    client: AsyncClient, auth: dict[str, str], processed: Conversation
) -> None:
    renamed = await client.patch(
        f"/conversations/{processed.id}", json={"title": "Studio call"}, headers=auth
    )
    assert renamed.status_code == 200 and renamed.json()["title"] == "Studio call"


def test_the_playback_copy_drops_plauds_metadata_stream(tmp_path: Path) -> None:
    from roudio.pipeline.audio import opus_only

    def page(serial: int, body: bytes) -> bytes:
        header = b"OggS" + bytes(10) + serial.to_bytes(4, "little") + bytes(8) + bytes([1])
        return header + bytes([len(body)]) + body

    head, tags, audio = (
        page(0x3E9, b"OpusHead" + bytes(11)),
        page(0x3E9, b"OpusTags"),
        page(0x3E9, b"\xb8\x01"),
    )
    plaud, meta = page(0x3EA, b"PLAUD.AI"), page(0x3EA, bytes(23))
    source = tmp_path / "rec.opus"
    source.write_bytes(head + plaud + tags + audio + meta)
    target = tmp_path / "play.opus"
    opus_only(source, target)
    assert target.read_bytes() == head + tags + audio
