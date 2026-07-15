# syntax=docker/dockerfile:1.7
ARG VARIANT=gpu
# buildx does NOT auto-populate TARGETARCH in the GLOBAL scope (before the first FROM), only
# inside build stages — so the `FROM base-${VARIANT}-${TARGETARCH}` selector below would resolve
# to `base-gpu-` and fail with "invalid reference format". CI builds these images amd64-only
# (see ci.yml: essentia-tensorflow / CUDA are amd64-only), so default the global copy to amd64.
# The per-stage `ARG TARGETARCH` inside `final` stays dynamic (buildx sets it there) for the
# arch-conditional pip install.
ARG TARGETARCH=amd64

# --- Base image selection per variant + architecture ---
# GPU: CUDA on amd64, CPU-only fallback on arm64
FROM --platform=linux/amd64 nvidia/cuda:12.8.0-runtime-ubuntu24.04 AS base-gpu-amd64
FROM python:3.13-slim AS base-gpu-arm64

# CPU: plain Python on both architectures
FROM python:3.13-slim AS base-cpu-amd64
FROM python:3.13-slim AS base-cpu-arm64

# Essentia: plain Python on both (essentia-tensorflow available only on amd64)
FROM python:3.13-slim AS base-essentia-amd64
FROM python:3.13-slim AS base-essentia-arm64

# --- Select the appropriate base ---
FROM base-${VARIANT}-${TARGETARCH} AS final

WORKDIR /app

# python:3.13-slim bases already ship python3/pip3; only the CUDA base needs them
RUN apt-get update \
    && if ! command -v python3 >/dev/null; then \
         apt-get install -y --no-install-recommends python3 python3-pip; \
       fi \
    && apt-get install -y --no-install-recommends \
    ffmpeg \
    libsndfile1 \
    wget \
    && rm -rf /var/lib/apt/lists/*

ARG VARIANT=gpu
ARG TARGETARCH

COPY requirements/ requirements/
RUN --mount=type=cache,target=/root/.cache/pip \
    apt-get update && apt-get install -y --no-install-recommends python3-dev gcc \
    && set -ex; \
    if [ "$VARIANT" = "gpu" ] && [ "$TARGETARCH" = "arm64" ]; then \
      pip3 install --break-system-packages -r requirements/cpu.lock.txt; \
    elif [ "$VARIANT" = "essentia" ] && [ "$TARGETARCH" = "arm64" ]; then \
      pip3 install --break-system-packages -r requirements/essentia-base.lock.txt; \
    elif [ "$VARIANT" = "essentia" ]; then \
      pip3 install --break-system-packages -r requirements/essentia-base.lock.txt -r requirements/essentia.txt; \
    else \
      pip3 install --break-system-packages -r requirements/${VARIANT}.lock.txt; \
    fi \
    && apt-get purge -y python3-dev gcc \
    && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*

COPY scripts/download_models.sh scripts/download_models.sh
RUN if [ "$VARIANT" = "essentia" ] && [ "$TARGETARCH" != "arm64" ]; then \
      bash scripts/download_models.sh /app/models || \
        echo "WARNING: Model download failed. Models must be volume-mounted at runtime."; \
    fi

COPY patches/ patches/
RUN python3 patches/patch_audio_separator.py

COPY scripts/ scripts/

COPY extractor/ extractor/
COPY radio/ radio/
COPY db.py .
COPY app.py .
COPY extractor_cli.py .
COPY lyrics_sync.py .
COPY taste_clusters.py .

RUN getent group 1000 >/dev/null || groupadd --gid 1000 appuser && \
    id -u 1000 >/dev/null 2>&1 || \
        useradd --uid 1000 --gid 1000 --no-log-init --create-home --shell /bin/bash appuser && \
    mkdir -p /app/models /app/output && \
    chown -R 1000:1000 /app

ENV MODEL_NAME=model_bs_roformer_ep_317_sdr_12.9755.yaml
ENV OUTPUT_FORMAT=mp3
ENV OUTPUT_BITRATE=192k
ENV DEVICE=cpu
ENV ESSENTIA_MODELS_DIR=/app/models

# Anonymity hardening for HuggingFace model downloads (MERT/CLAP): drop the usage-telemetry pings and
# never attach a cached token to anonymous public reads. The download UA carries only library versions,
# no identity — these disable the separate phone-home. DO_NOT_TRACK covers the wider HF ecosystem.
ENV HF_HUB_DISABLE_TELEMETRY=1
ENV HF_HUB_DISABLE_IMPLICIT_TOKEN=1
ENV DO_NOT_TRACK=1

USER 1000

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=3 \
    CMD python3 -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')" || exit 1

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
