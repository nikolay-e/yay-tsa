#!/bin/bash
# Upload a track to the yay-tsa server.
#
# Usage:
#   ./test-upload.sh /path/to/track.mp3
#   FILE_PATH=/path/to/track.mp3 ./test-upload.sh
#
# Required env vars:
#   YAYTSA_TOKEN    — API token (get from browser DevTools → Application → sessionStorage)
#
# Optional env vars:
#   YAYTSA_SERVER_URL  — server URL (default: http://localhost:8096)
#   YAYTSA_DEVICE_ID   — device ID (default: auto-generated)

set -euo pipefail

FILE_PATH="${1:-${FILE_PATH:-}}"
TOKEN="${YAYTSA_TOKEN:-}"
SERVER_URL="${YAYTSA_SERVER_URL:-http://localhost:8096}"
DEVICE_ID="${YAYTSA_DEVICE_ID:-test-device-$(hostname)}"

if [[ -z "$FILE_PATH" ]]; then
  echo "Error: no file specified."
  echo "Usage: $0 /path/to/track.mp3"
  exit 1
fi

if [[ ! -f "$FILE_PATH" ]]; then
  echo "Error: file not found: $FILE_PATH"
  exit 1
fi

if [[ -z "$TOKEN" ]]; then
  echo "Error: YAYTSA_TOKEN is required."
  echo "  Get it from the browser → DevTools → Application → sessionStorage → yaytsa-token"
  exit 1
fi

echo "Uploading: $FILE_PATH → $SERVER_URL"

curl -X POST "${SERVER_URL}/tracks/upload" \
  -H "X-Emby-Authorization: MediaBrowser Token=\"${TOKEN}\", DeviceId=\"${DEVICE_ID}\", Device=\"Shell\", Client=\"Yay-Tsa\", Version=\"1.0.0\"" \
  -F "file=@${FILE_PATH}" \
  -s | python3 -m json.tool 2>/dev/null || true
