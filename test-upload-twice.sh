#!/bin/bash
# Test duplicate detection by uploading the same file twice.
#
# Usage:
#   ./test-upload-twice.sh /path/to/track.mp3
#
# Optional env vars:
#   YAYTSA_SERVER_URL — default: http://localhost:8096
#   YAYTSA_USERNAME   — default: admin
#   YAYTSA_PASSWORD   — default: admin123

set -euo pipefail

FILE_PATH="${1:-${FILE_PATH:-}}"
SERVER_URL="${YAYTSA_SERVER_URL:-http://localhost:8096}"
USERNAME="${YAYTSA_USERNAME:-admin}"
PASSWORD="${YAYTSA_PASSWORD:-admin123}"

if [[ -z "$FILE_PATH" ]]; then
  echo "Usage: $0 /path/to/track.mp3"
  exit 1
fi

if [[ ! -f "$FILE_PATH" ]]; then
  echo "Error: file not found: $FILE_PATH"
  exit 1
fi

echo "=== Getting auth token ==="
TOKEN=$(curl -s -X POST "${SERVER_URL}/Users/AuthenticateByName" \
  -H "Content-Type: application/json" \
  -H 'X-Emby-Authorization: MediaBrowser DeviceId="test-device", Device="Test", Client="Test", Version="1.0.0"' \
  -d "{\"Username\":\"${USERNAME}\",\"Pw\":\"${PASSWORD}\"}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['AccessToken'])" 2>/dev/null)

echo "Token obtained: ${TOKEN:0:20}..."

AUTH_HEADER="X-Emby-Authorization: MediaBrowser Token=\"${TOKEN}\", DeviceId=\"test-device\", Device=\"Test\", Client=\"Test\", Version=\"1.0.0\""

echo ""
echo "=== Upload #1 ==="
curl -X POST "${SERVER_URL}/tracks/upload" \
  -H "$AUTH_HEADER" \
  -F "file=@${FILE_PATH}" \
  -w "\nHTTP Status: %{http_code}\n" 2>&1 | head -30

echo ""
echo "Waiting 2 seconds..."
sleep 2

echo ""
echo "=== Upload #2 (should detect duplicate) ==="
curl -X POST "${SERVER_URL}/tracks/upload" \
  -H "$AUTH_HEADER" \
  -F "file=@${FILE_PATH}" \
  -w "\nHTTP Status: %{http_code}\n" 2>&1

echo ""
echo "=== Done! ==="
