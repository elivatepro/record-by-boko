"""Decoding and re-encoding audio with ffmpeg, streamed so a 5-hour file never sits in memory."""

import shutil
import subprocess
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import numpy.typing as npt

RATE = 16_000
# Absolute path, found once (the Docker image installs ffmpeg; Homebrew on a Mac).
FFMPEG = shutil.which("ffmpeg") or "/usr/bin/ffmpeg"
CHUNK_SECONDS = 600


class AudioError(Exception):
    """ffmpeg couldn't read or write the audio."""


def pcm_chunks(path: Path, chunk_seconds: int = CHUNK_SECONDS) -> Iterator[npt.NDArray[np.int16]]:
    """The file as 16 kHz mono int16 samples, a chunk at a time."""
    process = subprocess.Popen(  # noqa: S603 - fixed arguments, our own temp file
        [
            FFMPEG,
            "-nostdin",
            "-v",
            "error",
            "-i",
            str(path),
            "-f",
            "s16le",
            "-ac",
            "1",
            "-ar",
            str(RATE),
            "-",
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    assert process.stdout is not None
    chunk_bytes = RATE * 2 * chunk_seconds
    try:
        while True:
            data = process.stdout.read(chunk_bytes)
            if not data:
                break
            yield np.frombuffer(data, dtype=np.int16)
    finally:
        process.stdout.close()
        error = process.stderr.read().decode(errors="replace") if process.stderr else ""
        if process.wait() != 0:
            raise AudioError(f"ffmpeg couldn't decode the audio: {error.strip()[:500]}")


@dataclass(frozen=True)
class Piece:
    """A stretch of the original file placed at `at_ms` in a speech-only file."""

    at_ms: int
    start_ms: int
    end_ms: int


GAP_MS = 400


def speech_only(
    source: Path, segments: list[tuple[int, int]], raw: Path
) -> tuple[list[Piece], int]:
    """Write just the speech (with short silences between pieces) as raw 16 kHz PCM.

    Returns the time map and the total length in ms. Times in the speech-only file convert back
    with `to_original`.
    """
    pieces: list[Piece] = []
    at_ms = 0
    pending = sorted(segments)
    gap = np.zeros(RATE * GAP_MS // 1000, dtype=np.int16)
    offset = 0  # samples of the original read so far
    buffer = np.zeros(0, dtype=np.int16)
    with raw.open("wb") as out:
        for chunk in pcm_chunks(source):
            buffer = np.concatenate([buffer, chunk])
            window_end = offset + len(buffer)
            while pending:
                start_ms, end_ms = pending[0]
                start, end = start_ms * RATE // 1000, end_ms * RATE // 1000
                if end > window_end:
                    break
                out.write(buffer[max(start - offset, 0) : end - offset].tobytes())
                out.write(gap.tobytes())
                pieces.append(Piece(at_ms, start_ms, end_ms))
                at_ms += end_ms - start_ms + GAP_MS
                pending.pop(0)
            # Keep only what a pending segment could still need.
            keep_from = pending[0][0] * RATE // 1000 if pending else window_end
            drop = max(0, min(keep_from, window_end) - offset)
            buffer = buffer[drop:]
            offset += drop
        # Segments running past the end of the audio.
        for start_ms, _end_ms in pending:
            start = start_ms * RATE // 1000
            if start - offset < len(buffer):
                out.write(buffer[max(start - offset, 0) :].tobytes())
                actual_end = (offset + len(buffer)) * 1000 // RATE
                pieces.append(Piece(at_ms, start_ms, actual_end))
                at_ms += actual_end - start_ms + GAP_MS
    return pieces, at_ms


def to_original(pieces: list[Piece], ms: float) -> int:
    """A time in the speech-only file as a time in the original file."""
    for piece in reversed(pieces):
        if ms >= piece.at_ms:
            return min(piece.start_ms + int(ms - piece.at_ms), piece.end_ms)
    return pieces[0].start_ms if pieces else int(ms)


def encode_opus(raw: Path, out: Path, *, bitrate: str = "32k") -> None:
    """Raw 16 kHz PCM to a small Ogg Opus file for the transcription service."""
    result = subprocess.run(  # noqa: S603
        [
            FFMPEG,
            "-nostdin",
            "-v",
            "error",
            "-y",
            "-f",
            "s16le",
            "-ar",
            str(RATE),
            "-ac",
            "1",
            "-i",
            str(raw),
            "-c:a",
            "libopus",
            "-b:a",
            bitrate,
            str(out),
        ],
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        raise AudioError(f"ffmpeg couldn't encode: {result.stderr.decode(errors='replace')[:500]}")
