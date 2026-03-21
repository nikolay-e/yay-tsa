ARG VARIANT=gpu
ARG TARGETARCH

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

RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 \
    python3-pip \
    python3-dev \
    gcc \
    ffmpeg \
    libsndfile1 \
    wget \
    && rm -rf /var/lib/apt/lists/*

ARG VARIANT=gpu
ARG TARGETARCH

COPY requirements/ requirements/
RUN set -ex; \
    if [ "$VARIANT" = "gpu" ] && [ "$TARGETARCH" = "arm64" ]; then \
      pip3 install --no-cache-dir --break-system-packages -r requirements/cpu.txt; \
    elif [ "$VARIANT" = "essentia" ] && [ "$TARGETARCH" = "arm64" ]; then \
      pip3 install --no-cache-dir --break-system-packages -r requirements/essentia-base.txt; \
    else \
      pip3 install --no-cache-dir --break-system-packages -r requirements/${VARIANT}.txt; \
    fi

# Patch audio-separator for PyTorch 2.10+ (torch.load weights_only default changed)
RUN find / -path "*/audio_separator/separator/roformer/roformer_loader.py" -exec \
    sed -i 's/torch\.load(model_path, map_location=device)/torch.load(model_path, map_location=device, weights_only=False)/g; s/torch\.load(model_path, map_location=.cpu.)/torch.load(model_path, map_location="cpu", weights_only=False)/g' {} + \
    2>/dev/null || true

COPY scripts/ scripts/
RUN if [ "$VARIANT" = "essentia" ] && [ "$TARGETARCH" != "arm64" ]; then \
      bash scripts/download_models.sh /app/models || \
        echo "WARNING: Model download failed. Models must be volume-mounted at runtime."; \
    fi

COPY extractor/ extractor/
COPY app.py .

RUN getent group 1000 >/dev/null || groupadd --gid 1000 appuser && \
    id -u 1000 >/dev/null 2>&1 || \
        useradd --uid 1000 --gid 1000 --no-log-init --create-home --shell /bin/bash appuser && \
    mkdir -p /app/models /app/output && \
    chown -R 1000:1000 /app

ENV MODEL_NAME=model_bs_roformer_ep_317_sdr_12.9755.yaml
ENV OUTPUT_FORMAT=wav
ENV DEVICE=cpu
ENV ESSENTIA_MODELS_DIR=/app/models

USER 1000

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=3 \
    CMD python3 -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')" || exit 1

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
