"""The AI steps (decision 8): tidy, understand, digest and Ask, behind one interface.

Every statement the model makes must cite transcript segments by their index. The callers check
those citations against the real transcript and drop anything that doesn't hold up (spec §7).
Prompts and output shapes live here only, so the model can change without touching the pipeline.
"""

import json
import logging
import re
from dataclasses import dataclass, field
from typing import Any, Protocol

import httpx

log = logging.getLogger(__name__)


class BrainError(Exception):
    """The AI service failed; worth trying again later."""


class BrainNotConfigured(BrainError):
    """No API key yet."""


@dataclass(frozen=True)
class Line:
    """A transcript segment as the model sees it."""

    idx: int
    speaker: str
    start_ms: int
    text: str


@dataclass(frozen=True)
class Correction:
    idx: int
    find: str
    replace: str


@dataclass(frozen=True)
class Item:
    kind: str  # decision, task, key_moment
    text: str
    sources: list[int]


@dataclass(frozen=True)
class Understood:
    start_idx: int
    end_idx: int
    kind: str  # conversation, event, voice_note
    title: str
    summary: str
    summary_sources: list[int]
    items: list[Item]


@dataclass(frozen=True)
class DigestInput:
    """One conversation of the day, with its items numbered for the model to pick moments from."""

    title: str
    summary: str
    items: list[tuple[int, str, str]]  # (item id, kind, text)


@dataclass(frozen=True)
class Digest:
    overview: str
    moment_item_ids: list[int]


@dataclass(frozen=True)
class Answer:
    text: str
    enough: bool
    sources: list[int]


@dataclass
class Usage:
    input_tokens: int = 0
    output_tokens: int = 0

    def add(self, other: "Usage") -> None:
        self.input_tokens += other.input_tokens
        self.output_tokens += other.output_tokens


@dataclass
class BrainResult[T]:
    value: T
    usage: Usage = field(default_factory=Usage)


class Brain(Protocol):
    async def tidy(self, lines: list[Line], words: list[str]) -> BrainResult[list[Correction]]: ...

    async def understand(self, lines: list[Line]) -> BrainResult[list[Understood]]: ...

    async def digest(self, day: str, conversations: list[DigestInput]) -> BrainResult[Digest]: ...

    async def ask(self, lines: list[Line], question: str) -> BrainResult[Answer]: ...


def _clock(ms: int) -> str:
    seconds = ms // 1000
    return f"{seconds // 3600}:{seconds % 3600 // 60:02d}:{seconds % 60:02d}"


def _transcript(lines: list[Line]) -> str:
    return "\n".join(
        f"[{line.idx}] {_clock(line.start_ms)} {line.speaker}: {line.text}" for line in lines
    )


# Prompts. The owner speaks English, often mixed with Nigerian Pidgin; keep their words.

TIDY = """You fix obvious mishearings in a speech-to-text transcript of one person's day.
Use context and the personal word list. Only fix clear errors (a wrong word that sounds alike, a
misspelt name from the word list). Never rephrase, never fix grammar or Pidgin, never translate.
For each fix give the segment index, the exact text to find in that segment, and its replacement.
If nothing needs fixing, return an empty list."""

UNDERSTAND = """You read the transcript of one recording from a wearable recorder and split
it into the conversations and events it contains (a meeting, a phone call, a voice note, an
errand). Skip stretches that are only background talk, TV or noise.
For each one give: the first and last segment index it covers; its kind (conversation, event
or voice_note); a short plain title (max 6 words, no quotes); a summary of one to three
sentences written to the owner as "you"; the segment indexes the summary rests on; and its
items: decisions that were made, tasks someone suggested or agreed to do, and key moments.
Every item must cite the segment indexes it comes from. Use only what is said; never guess.
Keep the speakers' own words for names and places. If nothing worth noting was said, return
an empty list."""

DIGEST = """You write the owner's daily digest from their conversations. Give a one or two sentence
overview of the day written to them as "you" (plain, warm, no hype), and pick up to five of the
numbered items as the day's key moments, in time order. Use only what's given."""

ASK = """Answer the owner's question using only this one recording's transcript. Cite the segment
indexes your answer rests on. If the transcript doesn't contain the answer, say so plainly, set
enough to false, and cite the segments that come closest (if any). Never use outside knowledge.
Answer in one to three short sentences, written to the owner as "you"."""


def _schema(name: str, properties: dict[str, Any]) -> dict[str, Any]:
    return {
        "type": "json_schema",
        "name": name,
        "strict": True,
        "schema": {
            "type": "object",
            "additionalProperties": False,
            "properties": properties,
            "required": list(properties),
        },
    }


_INTS = {"type": "array", "items": {"type": "integer"}}

TIDY_SCHEMA = _schema(
    "corrections",
    {
        "corrections": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "properties": {
                    "idx": {"type": "integer"},
                    "find": {"type": "string"},
                    "replace": {"type": "string"},
                },
                "required": ["idx", "find", "replace"],
            },
        }
    },
)

UNDERSTAND_SCHEMA = _schema(
    "conversations",
    {
        "conversations": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "properties": {
                    "start_idx": {"type": "integer"},
                    "end_idx": {"type": "integer"},
                    "kind": {"type": "string", "enum": ["conversation", "event", "voice_note"]},
                    "title": {"type": "string"},
                    "summary": {"type": "string"},
                    "summary_sources": _INTS,
                    "items": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "additionalProperties": False,
                            "properties": {
                                "kind": {
                                    "type": "string",
                                    "enum": ["decision", "task", "key_moment"],
                                },
                                "text": {"type": "string"},
                                "sources": _INTS,
                            },
                            "required": ["kind", "text", "sources"],
                        },
                    },
                },
                "required": [
                    "start_idx",
                    "end_idx",
                    "kind",
                    "title",
                    "summary",
                    "summary_sources",
                    "items",
                ],
            },
        }
    },
)

DIGEST_SCHEMA = _schema("digest", {"overview": {"type": "string"}, "moments": _INTS})

ASK_SCHEMA = _schema(
    "answer", {"answer": {"type": "string"}, "enough": {"type": "boolean"}, "sources": _INTS}
)


class OpenAIBrain:
    """OpenAI's Responses API with structured outputs."""

    URL = "https://api.openai.com/v1/responses"

    def __init__(
        self, api_key: str, model: str, *, client: httpx.AsyncClient | None = None
    ) -> None:
        self._key = api_key
        self._model = model
        self._client = client or httpx.AsyncClient(timeout=httpx.Timeout(600.0, connect=30.0))

    async def _call(
        self, instructions: str, text: str, schema: dict[str, Any]
    ) -> tuple[dict[str, Any], Usage]:
        try:
            response = await self._client.post(
                self.URL,
                headers={"Authorization": f"Bearer {self._key}"},
                json={
                    "model": self._model,
                    "instructions": instructions,
                    "input": text,
                    "text": {"format": schema},
                },
            )
        except httpx.HTTPError as exc:
            raise BrainError(f"Couldn't reach OpenAI: {exc}") from exc
        if response.status_code >= 400:
            raise BrainError(f"OpenAI answered {response.status_code}: {response.text[:300]}")
        body = response.json()
        output = ""
        for item in body.get("output", []):
            for part in item.get("content", []) or []:
                if part.get("type") == "output_text":
                    output += part.get("text", "")
        usage = body.get("usage") or {}
        try:
            parsed: dict[str, Any] = json.loads(output)
        except json.JSONDecodeError as exc:
            raise BrainError("OpenAI returned something that isn't the expected JSON") from exc
        return parsed, Usage(int(usage.get("input_tokens", 0)), int(usage.get("output_tokens", 0)))

    async def tidy(self, lines: list[Line], words: list[str]) -> BrainResult[list[Correction]]:
        text = f"Word list: {', '.join(words) or '(empty)'}\n\nTranscript:\n{_transcript(lines)}"
        data, usage = await self._call(TIDY, text, TIDY_SCHEMA)
        return BrainResult(
            [Correction(c["idx"], c["find"], c["replace"]) for c in data["corrections"]], usage
        )

    async def understand(self, lines: list[Line]) -> BrainResult[list[Understood]]:
        data, usage = await self._call(UNDERSTAND, _transcript(lines), UNDERSTAND_SCHEMA)
        return BrainResult(
            [
                Understood(
                    c["start_idx"],
                    c["end_idx"],
                    c["kind"],
                    c["title"],
                    c["summary"],
                    c["summary_sources"],
                    [Item(i["kind"], i["text"], i["sources"]) for i in c["items"]],
                )
                for c in data["conversations"]
            ],
            usage,
        )

    async def digest(self, day: str, conversations: list[DigestInput]) -> BrainResult[Digest]:
        parts = []
        for c in conversations:
            items = "\n".join(f"  ({item_id}) {kind}: {text}" for item_id, kind, text in c.items)
            parts.append(f"- {c.title}: {c.summary}\n{items}")
        data, usage = await self._call(DIGEST, f"Day: {day}\n\n" + "\n".join(parts), DIGEST_SCHEMA)
        return BrainResult(Digest(data["overview"], data["moments"]), usage)

    async def ask(self, lines: list[Line], question: str) -> BrainResult[Answer]:
        text = f"Transcript:\n{_transcript(lines)}\n\nQuestion: {question}"
        data, usage = await self._call(ASK, text, ASK_SCHEMA)
        return BrainResult(Answer(data["answer"], data["enough"], data["sources"]), usage)


class UnconfiguredBrain:
    async def tidy(self, lines: list[Line], words: list[str]) -> BrainResult[list[Correction]]:
        raise BrainNotConfigured("Writing up isn't set up yet (OPENAI_API_KEY).")

    async def understand(self, lines: list[Line]) -> BrainResult[list[Understood]]:
        raise BrainNotConfigured("Writing up isn't set up yet (OPENAI_API_KEY).")

    async def digest(self, day: str, conversations: list[DigestInput]) -> BrainResult[Digest]:
        raise BrainNotConfigured("Writing up isn't set up yet (OPENAI_API_KEY).")

    async def ask(self, lines: list[Line], question: str) -> BrainResult[Answer]:
        raise BrainNotConfigured("Ask isn't set up yet (OPENAI_API_KEY).")


_WORD = re.compile(r"[a-z']+")
_STOP = {
    "the",
    "a",
    "an",
    "is",
    "are",
    "to",
    "of",
    "and",
    "what",
    "who",
    "did",
    "we",
    "i",
    "you",
    "it",
    "in",
    "for",
    "on",
    "do",
}


class FakeBrain:
    """Rule-based stand-in for development and tests. Same shapes, no network."""

    async def tidy(self, lines: list[Line], words: list[str]) -> BrainResult[list[Correction]]:
        fixes = []
        for line in lines:
            for word in words:
                # Fix a word-list term heard in lower case ("roudio" → "Roudio").
                if word.lower() in line.text and word not in line.text:
                    fixes.append(Correction(line.idx, word.lower(), word))
        return BrainResult(fixes)

    async def understand(self, lines: list[Line]) -> BrainResult[list[Understood]]:
        groups: list[list[Line]] = []
        for line in lines:
            if groups and line.start_ms - groups[-1][-1].start_ms <= 5 * 60_000:
                groups[-1].append(line)
            else:
                groups.append([line])
        result = []
        for group in groups:
            title = " ".join(group[0].text.rstrip("?.!").split()[:5])
            items = [Item("key_moment", group[0].text, [group[0].idx])]
            for line in group:
                lower = line.text.lower()
                if any(k in lower for k in ("fine", "agree", "decid", "let's")):
                    items.append(Item("decision", line.text, [line.idx]))
                if any(k in lower for k in ("i'll", "i will", "by monday", "need to")):
                    items.append(Item("task", line.text, [line.idx]))
            summary = " ".join(line.text for line in group[:2])
            kind = "voice_note" if len({line.speaker for line in group}) == 1 else "conversation"
            result.append(
                Understood(group[0].idx, group[-1].idx, kind, title, summary, [group[0].idx], items)
            )
        return BrainResult(result)

    async def digest(self, day: str, conversations: list[DigestInput]) -> BrainResult[Digest]:
        overview = "; ".join(c.title for c in conversations) + "." if conversations else ""
        moments = [c.items[0][0] for c in conversations if c.items][:5]
        return BrainResult(Digest(overview, moments))

    async def ask(self, lines: list[Line], question: str) -> BrainResult[Answer]:
        wanted = set(_WORD.findall(question.lower())) - _STOP
        scored = sorted(
            ((len(wanted & set(_WORD.findall(line.text.lower()))), line) for line in lines),
            key=lambda pair: -pair[0],
        )
        if scored and scored[0][0] >= 2:
            best = scored[0][1]
            return BrainResult(Answer(best.text, True, [best.idx]))
        return BrainResult(Answer("Nothing in this recording answers that.", False, []))
