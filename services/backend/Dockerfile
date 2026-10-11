# One image for both Railway services: `api` runs roudio-api, `worker` runs roudio-worker.
FROM python:3.12-slim AS base
COPY --from=ghcr.io/astral-sh/uv:0.13 /uv /usr/local/bin/uv
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never PYTHONUNBUFFERED=1
WORKDIR /app

# ffmpeg decodes the recorder's Opus and encodes the speech-only file for transcription.
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# FireRedVAD weights (Apache-2.0), pinned to a Hugging Face commit and checked (decision 11).
ARG FIREREDVAD_REV=7990aaccc6b7aec1e527743bd30201f2c4a03b8c
RUN mkdir -p /models/FireRedVAD/VAD && cd /models/FireRedVAD/VAD \
    && curl -fsSL -o cmvn.ark "https://huggingface.co/FireRedTeam/FireRedVAD/resolve/${FIREREDVAD_REV}/VAD/cmvn.ark" \
    && curl -fsSL -o model.pth.tar "https://huggingface.co/FireRedTeam/FireRedVAD/resolve/${FIREREDVAD_REV}/VAD/model.pth.tar" \
    && echo "c87f6f13edf0f0ec7535ddfc9cc3387d9268cb234b70182d566c5e2edf3ca473  cmvn.ark" | sha256sum -c - \
    && echo "63f4fb1b00a6b8607c118dd48efc18d5e40d67d99b7bf9aa7a8d61540cf23d71  model.pth.tar" | sha256sum -c -
ENV VAD_MODEL_DIR=/models/FireRedVAD

# Dependencies first, so code changes don't reinstall them.
COPY pyproject.toml uv.lock ./
RUN uv sync --locked --no-dev --no-install-project

COPY alembic.ini ./
COPY migrations ./migrations
COPY src ./src
RUN uv sync --locked --no-dev

RUN useradd --create-home roudio
USER roudio
ENV PATH="/app/.venv/bin:$PATH"
CMD ["roudio-api"]
