import random
from pathlib import Path

import pytest

from roudio.pipeline.tuning import Stretch, clock, main, sample_dropped, stretches


def test_stretches_cover_the_file_in_order() -> None:
    parts = stretches([(5000, 8000), (2000, 3000)], 10_000)
    assert parts == [
        Stretch(0, 2000, kept=False),
        Stretch(2000, 3000, kept=True),
        Stretch(3000, 5000, kept=False),
        Stretch(5000, 8000, kept=True),
        Stretch(8000, 10_000, kept=False),
    ]
    assert sum(p.length_ms for p in parts) == 10_000


def test_no_speech_is_one_dropped_stretch() -> None:
    assert stretches([], 4000) == [Stretch(0, 4000, kept=False)]


def test_dropped_samples_stay_inside_dropped_audio() -> None:
    parts = stretches([(10_000, 50_000)], 120_000)
    clips = sample_dropped(parts, 5, 10_000, random.Random(1))  # noqa: S311
    assert 0 < len(clips) <= 5
    for start, end in clips:
        assert end - start <= 10_000
        assert end <= 10_000 or start >= 50_000


def test_clock() -> None:
    assert clock(3_723_000) == "1:02:03"


def test_report_writes_dropped_clips(
    audio_file: Path, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    out = tmp_path / "dropped"
    main([str(audio_file), "--samples", "2", "--clip-seconds", "1", "--out", str(out), "--list"])
    report = capsys.readouterr().out
    assert "KEPT" in report and "dropped" in report
    assert list(out.glob("*.ogg"))
