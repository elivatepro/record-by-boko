"""Transcription with ElevenLabs Scribe v2 (decision 5), behind an interface with a fake."""

import asyncio
import logging
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

import httpx

log = logging.getLogger(__name__)


class TranscriptionError(Exception):
    """The transcription service failed; worth trying again later."""


class TranscriptionNotConfigured(TranscriptionError):
    """No API key yet; recordings wait until there is one."""


@dataclass(frozen=True)
class Word:
    text: str
    start_ms: int
    end_ms: int
    speaker: str


@dataclass
class Transcript:
    words: list[Word]
    language: str | None = None
    # Seconds of audio billed, for the cost log.
    billed_seconds: float = 0.0
    keyterms_used: bool = False
    extra: dict[str, Any] = field(default_factory=dict)


class Transcriber(Protocol):
    async def transcribe(self, audio: Path, *, keyterms: list[str]) -> Transcript:
        """Words with times in `audio` (the speech-only file) and speaker labels."""
        ...


SCRIBE_URL = "https://api.elevenlabs.io/v1/speech-to-text"
# Keyterms: up to 1000, each under 50 characters and five words, none of these characters.
_FORBIDDEN = set("<>{}[]\\")


def clean_keyterms(terms: list[str]) -> list[str]:
    cleaned: list[str] = []
    for term in terms:
        term = term.strip()
        if 0 < len(term) < 50 and len(term.split()) <= 5 and not (_FORBIDDEN & set(term)):
            cleaned.append(term)
    return cleaned[:1000]


class ScribeTranscriber:
    def __init__(
        self, api_key: str, model: str, *, client: httpx.AsyncClient | None = None
    ) -> None:
        self._key = api_key
        self._model = model
        # Long audio is transcribed synchronously; give it time.
        self._client = client or httpx.AsyncClient(timeout=httpx.Timeout(1800.0, connect=30.0))

    async def transcribe(self, audio: Path, *, keyterms: list[str]) -> Transcript:
        terms = clean_keyterms(keyterms)
        data: dict[str, str | list[str]] = {
            "model_id": self._model,
            "diarize": "true",
            "timestamps_granularity": "word",
            "tag_audio_events": "false",
        }
        if terms:
            data["keyterms"] = terms
        # Bytes, not the open file: an AsyncClient can't stream a sync file in a multipart body.
        # The speech-only audio is small (Opus at 32 kbit/s is about 14 MB an hour).
        content = await asyncio.to_thread(audio.read_bytes)
        try:
            response = await self._client.post(
                SCRIBE_URL,
                headers={"xi-api-key": self._key},
                data=data,
                files={"file": (audio.name, content, "audio/ogg")},
            )
        except httpx.HTTPError as exc:
            raise TranscriptionError(f"Couldn't reach ElevenLabs: {exc}") from exc
        if response.status_code >= 400:
            # The body can echo request details; keep only the status and a short message.
            raise TranscriptionError(
                f"ElevenLabs answered {response.status_code}: {response.text[:300]}"
            )
        body = response.json()
        words = [
            Word(
                text=w["text"],
                start_ms=int(float(w.get("start") or 0) * 1000),
                end_ms=int(float(w.get("end") or 0) * 1000),
                speaker=str(w.get("speaker_id") or "speaker_0"),
            )
            for w in body.get("words", [])
            if w.get("type") == "word"
        ]
        duration = max((w.end_ms for w in words), default=0) / 1000
        return Transcript(words, body.get("language_code"), duration, bool(terms))


class UnconfiguredTranscriber:
    async def transcribe(self, audio: Path, *, keyterms: list[str]) -> Transcript:
        raise TranscriptionNotConfigured("Transcription isn't set up yet (ELEVENLABS_API_KEY).")


class FakeTranscriber:
    """Development and tests: one sentence per second of audio, alternating two speakers."""

    def __init__(self, sentences: list[str] | None = None) -> None:
        self.sentences = sentences or [
            "Can we keep the provider shortlist to three?",
            "Three is fine, same five recordings for each.",
            "I'll pull the sample files together by Monday.",
            "Then cost comes last.",
        ]
        self.calls: list[tuple[Path, list[str]]] = []

    async def transcribe(self, audio: Path, *, keyterms: list[str]) -> Transcript:
        from roudio.pipeline.audio import pcm_chunks

        self.calls.append((audio, keyterms))
        total_ms = sum(len(chunk) for chunk in pcm_chunks(audio)) * 1000 // 16_000
        words: list[Word] = []
        at, n = 0, 0
        while at + 1000 <= max(total_ms, 1000):
            sentence = self.sentences[n % len(self.sentences)].split()
            step = 1000 // len(sentence)
            for i, text in enumerate(sentence):
                words.append(
                    Word(text, at + i * step, at + (i + 1) * step - 20, f"speaker_{n % 2}")
                )
            at += 1000
            n += 1
        return Transcript(words, "en", total_ms / 1000, bool(keyterms))


@dataclass(frozen=True)
class Turn:
    speaker: str
    start_ms: int
    end_ms: int
    text: str
    words: list[Word]


def turns(words: list[Word], *, gap_ms: int = 1500, max_ms: int = 60_000) -> list[Turn]:
    """Group words into speaker turns: a new turn on a change of speaker, a long pause, or when a
    turn runs past `max_ms` and a sentence has just ended."""
    result: list[Turn] = []
    current: list[Word] = []

    def flush() -> None:
        if current:
            result.append(
                Turn(
                    current[0].speaker,
                    current[0].start_ms,
                    current[-1].end_ms,
                    _join(current),
                    list(current),
                )
            )
            current.clear()

    for word in words:
        if current:
            last = current[-1]
            sentence_end = last.text.endswith((".", "?", "!"))
            if (
                word.speaker != last.speaker
                or word.start_ms - last.end_ms > gap_ms
                or (word.end_ms - current[0].start_ms > max_ms and sentence_end)
            ):
                flush()
        current.append(word)
    flush()
    return result


def _join(words: list[Word]) -> str:
    text = ""
    for word in words:
        if text and not word.text.startswith((",", ".", "?", "!", ";", ":", "'")):
            text += " "
        text += word.text
    return text.strip()
