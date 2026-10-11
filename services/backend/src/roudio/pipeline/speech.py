"""Finding speech (decision 11): FireRedVAD on the phone's original audio, never cut.

The detector only decides what gets transcribed. It's tuned to miss as little speech as
possible: a low threshold, padding around each segment, and short gaps merged (spec §5).
"""

import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

import numpy as np

from roudio.pipeline.audio import RATE, pcm_chunks

log = logging.getLogger(__name__)

Segment = tuple[int, int]  # start_ms, end_ms in the original file


@dataclass(frozen=True)
class SpeechSettings:
    threshold: float = 0.3
    pad_ms: int = 300
    merge_gap_ms: int = 1500
    min_speech_ms: int = 250


class SpeechDetector(Protocol):
    def detect(self, audio: Path) -> tuple[list[Segment], int]:
        """Speech segments and the audio's length in ms."""
        ...


def tidy_segments(raw: list[Segment], total_ms: int, settings: SpeechSettings) -> list[Segment]:
    """Pad each segment, merge ones closer than the gap, drop blips shorter than the minimum."""
    padded = [
        (max(0, start - settings.pad_ms), min(total_ms, end + settings.pad_ms))
        for start, end in sorted(raw)
        if end - start >= settings.min_speech_ms
    ]
    merged: list[Segment] = []
    for start, end in padded:
        if merged and start - merged[-1][1] <= settings.merge_gap_ms:
            merged[-1] = (merged[-1][0], max(merged[-1][1], end))
        else:
            merged.append((start, end))
    return merged


class FireRedDetector:
    """FireRedVAD (Apache-2.0) over 10-minute chunks; post-processing runs on the whole file."""

    def __init__(self, model_dir: str, settings: SpeechSettings) -> None:
        from fireredvad.vad import FireRedVad, FireRedVadConfig

        self._settings = settings
        config = FireRedVadConfig(speech_threshold=settings.threshold, max_speech_frame=180_000)
        self._vad = FireRedVad.from_pretrained(str(Path(model_dir) / "VAD"), config)

    def detect(self, audio: Path) -> tuple[list[Segment], int]:
        import torch

        probs: list[torch.Tensor] = []
        samples = 0
        with torch.inference_mode():
            for chunk in pcm_chunks(audio):
                samples += len(chunk)
                if len(chunk) < RATE // 10:
                    continue
                _, chunk_probs = self._vad.detect((chunk, RATE), do_postprocess=False)
                probs.append(chunk_probs.reshape(-1))
        total_ms = samples * 1000 // RATE
        if not probs:
            return [], total_ms
        post = self._vad.vad_postprocessor
        decisions = post.process(torch.cat(probs).tolist())
        raw = [
            (int(start * 1000), int(end * 1000))
            for start, end in post.decision_to_segment(decisions, total_ms / 1000)
        ]
        return tidy_segments(raw, total_ms, self._settings), total_ms


class EnergyDetector:
    """A plain loudness detector: for development and tests when the model isn't there."""

    def __init__(
        self, settings: SpeechSettings, *, frame_ms: int = 30, db_above_floor: float = 12.0
    ) -> None:
        self._settings = settings
        self._frame = RATE * frame_ms // 1000
        self._frame_ms = frame_ms
        self._above = db_above_floor

    def detect(self, audio: Path) -> tuple[list[Segment], int]:
        levels: list[float] = []
        samples = 0
        for chunk in pcm_chunks(audio):
            samples += len(chunk)
            frames = len(chunk) // self._frame
            if frames == 0:
                continue
            block = chunk[: frames * self._frame].astype(np.float64).reshape(frames, self._frame)
            rms = np.sqrt(np.mean(block**2, axis=1)) + 1e-9
            levels.extend((20 * np.log10(rms)).tolist())
        total_ms = samples * 1000 // RATE
        if not levels:
            return [], total_ms
        floor = float(np.percentile(levels, 10))
        raw: list[Segment] = []
        start: int | None = None
        for i, level in enumerate(levels):
            loud = level > floor + self._above and level > 20
            if loud and start is None:
                start = i
            elif not loud and start is not None:
                raw.append((start * self._frame_ms, i * self._frame_ms))
                start = None
        if start is not None:
            raw.append((start * self._frame_ms, len(levels) * self._frame_ms))
        return tidy_segments(raw, total_ms, self._settings), total_ms


def build_detector(model_dir: str, settings: SpeechSettings) -> SpeechDetector:
    if model_dir and (Path(model_dir) / "VAD" / "model.pth.tar").exists():
        return FireRedDetector(model_dir, settings)
    log.warning("FireRedVAD weights not found at %r; using the energy detector", model_dir)
    return EnergyDetector(settings)
