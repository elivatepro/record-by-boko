"""The speech detector's tuning tool (spec §5): what was kept, what was dropped, and clips of the
dropped audio to listen to. Run it on a real day before trusting the thresholds.

    uv run roudio-vad-report day/*.ogg --threshold 0.25 --samples 12 --out /tmp/dropped
"""

import argparse
import random
import subprocess
from dataclasses import dataclass
from pathlib import Path

from roudio.pipeline.audio import FFMPEG, AudioError
from roudio.pipeline.speech import Segment, SpeechSettings, build_detector


@dataclass(frozen=True)
class Stretch:
    start_ms: int
    end_ms: int
    kept: bool

    @property
    def length_ms(self) -> int:
        return self.end_ms - self.start_ms


def stretches(segments: list[Segment], total_ms: int) -> list[Stretch]:
    """The whole file as alternating kept (speech) and dropped stretches, in order."""
    out: list[Stretch] = []
    at = 0
    for start, end in sorted(segments):
        start, end = max(start, at), min(end, total_ms)
        if start > at:
            out.append(Stretch(at, start, kept=False))
        if end > start:
            out.append(Stretch(start, end, kept=True))
            at = end
    if at < total_ms:
        out.append(Stretch(at, total_ms, kept=False))
    return out


def sample_dropped(
    parts: list[Stretch], count: int, clip_ms: int, rng: random.Random
) -> list[tuple[int, int]]:
    """Up to `count` clips from the dropped stretches, longer stretches more likely."""
    dropped = [p for p in parts if not p.kept and p.length_ms >= 1000]
    if not dropped:
        return []
    weights = [p.length_ms for p in dropped]
    clips: set[tuple[int, int]] = set()
    for _ in range(count * 4):
        if len(clips) >= count:
            break
        part = rng.choices(dropped, weights)[0]
        length = min(clip_ms, part.length_ms)
        start = part.start_ms + rng.randint(0, part.length_ms - length)
        clips.add((start, start + length))
    return sorted(clips)


def clock(ms: int) -> str:
    s = ms // 1000
    return f"{s // 3600:d}:{s // 60 % 60:02d}:{s % 60:02d}"


def _cut(source: Path, start_ms: int, end_ms: int, target: Path) -> None:
    result = subprocess.run(  # noqa: S603 - fixed arguments, files the owner passed in
        [
            FFMPEG,
            "-nostdin",
            "-v",
            "error",
            "-y",
            "-ss",
            f"{start_ms / 1000:.3f}",
            "-to",
            f"{end_ms / 1000:.3f}",
            "-i",
            str(source),
            "-ac",
            "1",
            "-c:a",
            "libopus",
            str(target),
        ],
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        raise AudioError(result.stderr.decode(errors="replace").strip()[:500])


def main(argv: list[str] | None = None) -> None:
    from roudio.config import get_settings

    settings = get_settings()
    parser = argparse.ArgumentParser(prog="roudio-vad-report", description=__doc__.split("\n")[0])
    parser.add_argument("files", nargs="+", type=Path, help="audio files (a day of recordings)")
    parser.add_argument("--threshold", type=float, default=settings.vad_threshold)
    parser.add_argument("--pad-ms", type=int, default=settings.vad_pad_ms)
    parser.add_argument("--merge-gap-ms", type=int, default=settings.vad_merge_gap_ms)
    parser.add_argument("--min-speech-ms", type=int, default=settings.vad_min_speech_ms)
    parser.add_argument("--samples", type=int, default=10, help="dropped clips per file")
    parser.add_argument("--clip-seconds", type=int, default=10)
    parser.add_argument("--out", type=Path, help="write dropped clips here to listen to")
    parser.add_argument("--list", action="store_true", help="print every stretch, not a summary")
    parser.add_argument("--seed", type=int, default=0)
    args = parser.parse_args(argv)

    speech = SpeechSettings(args.threshold, args.pad_ms, args.merge_gap_ms, args.min_speech_ms)
    detector = build_detector(settings.vad_model_dir, speech)
    print(f"Detector: {type(detector).__name__}, {speech}")
    rng = random.Random(args.seed)  # noqa: S311 - picks clips to listen to
    all_kept = all_total = 0
    for path in args.files:
        segments, total_ms = detector.detect(path)
        parts = stretches(segments, total_ms)
        kept = sum(p.length_ms for p in parts if p.kept)
        all_kept += kept
        all_total += total_ms
        share = kept * 100 / total_ms if total_ms else 0
        print(f"\n{path.name}: {clock(total_ms)} long, kept {clock(kept)} ({share:.0f}%)")
        for p in parts if args.list else []:
            print(f"  {'KEPT   ' if p.kept else 'dropped'} {clock(p.start_ms)}-{clock(p.end_ms)}")
        clips = sample_dropped(parts, args.samples, args.clip_seconds * 1000, rng)
        if args.out and clips:
            args.out.mkdir(parents=True, exist_ok=True)
            for start, end in clips:
                name = f"{path.stem}_{clock(start).replace(':', '-')}.ogg"
                _cut(path, start, end, args.out / name)
            print(f"  {len(clips)} dropped clip(s) in {args.out}")
        elif clips:
            print("  Dropped samples: " + ", ".join(clock(s) for s, _ in clips))
    if len(args.files) > 1 and all_total:
        print(f"\nAll files: kept {clock(all_kept)} of {clock(all_total)}")
