ARG VARIANT=gpu

FROM nvidia/cuda:12.1.0-runtime-ubuntu22.04 AS gpu
FROM python:3.11-slim AS cpu
FROM --platform=linux/amd64 python:3.11-slim AS essentia

FROM ${VARIANT} AS final

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
COPY requirements/${VARIANT}.txt requirements.txt
RUN pip3 install --no-cache-dir -r requirements.txt

COPY scripts/ scripts/
RUN if [ "$VARIANT" = "essentia" ]; then bash scripts/download_models.sh /app/models; fi

COPY extractor/ extractor/
COPY app.py .

RUN groupadd --gid 1000 appuser && \
    useradd --uid 1000 --gid 1000 --create-home --shell /bin/bash appuser && \
    mkdir -p /app/models /app/output && \
    chown -R appuser:appuser /app

ENV MODEL_NAME=htdemucs.yaml
ENV OUTPUT_FORMAT=wav
ENV DEVICE=cpu
ENV ESSENTIA_MODELS_DIR=/app/models

USER appuser

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=3 \
    CMD python3 -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')" || exit 1

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
