"""The outside services the pipeline uses, built from settings once; fakes in tests."""

from dataclasses import dataclass
from functools import lru_cache

from roudio.config import Settings, get_settings
from roudio.pipeline.brain import Brain, FakeBrain, OpenAIBrain, UnconfiguredBrain
from roudio.pipeline.speech import SpeechDetector, SpeechSettings, build_detector
from roudio.pipeline.transcribe import (
    FakeTranscriber,
    ScribeTranscriber,
    Transcriber,
    UnconfiguredTranscriber,
)
from roudio.storage import AudioStore, S3AudioStore, UnconfiguredAudioStore


@dataclass
class PipelineServices:
    audio: AudioStore
    detector: SpeechDetector
    transcriber: Transcriber
    brain: Brain
    settings: Settings

    @property
    def real(self) -> bool:
        """Scribe and OpenAI, not the stand-ins: what they write never needs writing again."""
        return isinstance(self.transcriber, ScribeTranscriber) and isinstance(
            self.brain, OpenAIBrain
        )


def audio_store(settings: Settings) -> AudioStore:
    if not settings.audio_bucket:
        return UnconfiguredAudioStore()
    return S3AudioStore(
        settings.audio_bucket,
        endpoint=settings.audio_endpoint,
        access_key_id=settings.audio_access_key_id,
        secret_access_key=(
            settings.audio_secret_access_key.get_secret_value()
            if settings.audio_secret_access_key
            else None
        ),
        region=settings.audio_region,
        path_style=settings.audio_path_style,
    )


def build(settings: Settings) -> PipelineServices:
    transcriber: Transcriber
    brain: Brain
    if settings.elevenlabs_api_key:
        transcriber = ScribeTranscriber(
            settings.elevenlabs_api_key.get_secret_value(), settings.elevenlabs_model
        )
    elif settings.is_production_like and not settings.pipeline_fakes:
        transcriber = UnconfiguredTranscriber()
    else:
        transcriber = FakeTranscriber()
    if settings.openai_api_key:
        brain = OpenAIBrain(settings.openai_api_key.get_secret_value(), settings.openai_model)
    elif settings.is_production_like and not settings.pipeline_fakes:
        brain = UnconfiguredBrain()
    else:
        brain = FakeBrain()
    speech = SpeechSettings(
        settings.vad_threshold,
        settings.vad_pad_ms,
        settings.vad_merge_gap_ms,
        settings.vad_min_speech_ms,
    )
    return PipelineServices(
        audio_store(settings),
        build_detector(settings.vad_model_dir, speech),
        transcriber,
        brain,
        settings,
    )


_override: PipelineServices | None = None


@lru_cache
def _built() -> PipelineServices:
    return build(get_settings())


def get() -> PipelineServices:
    return _override or _built()


def override(services: PipelineServices | None) -> None:
    """Tests: use these services instead of the real ones."""
    global _override
    _override = services
