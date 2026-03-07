#!/bin/bash
set -euo pipefail

MODELS_DIR="${1:-/app/models}"
BASE_URL="https://essentia.upf.edu/models"

mkdir -p "$MODELS_DIR"

MODELS=(
  "feature-extractors/discogs-effnet/discogs-effnet-bs64-1.pb"
  "autotagging/msd/msd-musicnn-1.pb"
  "classification-heads/mood_happy/mood_happy-discogs-effnet-1.pb"
  "classification-heads/mood_aggressive/mood_aggressive-discogs-effnet-1.pb"
  "classification-heads/voice_instrumental/voice_instrumental-msd-musicnn-1.pb"
  "classification-heads/danceability/danceability-discogs-effnet-1.pb"
)

download_with_backoff() {
  local url="$1"
  local target="$2"
  local max_retries=5
  local wait=5

  for attempt in $(seq 1 "$max_retries"); do
    echo "  Attempt $attempt/$max_retries..."
    if wget --timeout=120 --tries=1 -q -O "$target" "$url" && [ -s "$target" ]; then
      return 0
    fi
    rm -f "$target"
    if [ "$attempt" -lt "$max_retries" ]; then
      echo "  Retrying in ${wait}s..."
      sleep "$wait"
      wait=$((wait * 2))
    fi
  done

  return 1
}

FAILED=0
for model_path in "${MODELS[@]}"; do
  filename=$(basename "$model_path")
  target="$MODELS_DIR/$filename"

  if [ -f "$target" ] && [ -s "$target" ]; then
    echo "Already exists: $filename"
    continue
  fi

  echo "Downloading: $filename"
  if ! download_with_backoff "$BASE_URL/$model_path" "$target"; then
    echo "WARNING: Failed to download $filename after retries" >&2
    FAILED=$((FAILED + 1))
  fi
done

if [ "$FAILED" -gt 0 ]; then
  echo "WARNING: $FAILED model(s) failed to download. Models must be provided at runtime." >&2
  exit 1
fi

echo "All models downloaded to $MODELS_DIR"
ls -lh "$MODELS_DIR"
