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

for model_path in "${MODELS[@]}"; do
  filename=$(basename "$model_path")
  target="$MODELS_DIR/$filename"

  if [ -f "$target" ] && [ -s "$target" ]; then
    echo "Already exists: $filename"
    continue
  fi

  echo "Downloading: $filename"
  wget --timeout=60 --tries=3 --waitretry=5 -q --show-progress -O "$target" "$BASE_URL/$model_path"

  if [ ! -s "$target" ]; then
    echo "ERROR: Downloaded file is empty: $filename" >&2
    rm -f "$target"
    exit 1
  fi
done

echo "All models downloaded to $MODELS_DIR"
ls -lh "$MODELS_DIR"
