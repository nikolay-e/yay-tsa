#!/bin/sh
set -e

BACKEND_URL="${BACKEND_URL:-http://backend:8096}"
TEST_USERNAME="${TEST_USERNAME:-admin}"
TEST_PASSWORD="${TEST_PASSWORD:-admin123}"

echo "Authenticating with backend..."
AUTH_RESPONSE=$(wget -qO- \
  --header='Content-Type: application/json' \
  --post-data='{"Username":"'"$TEST_USERNAME"'","Pw":"'"$TEST_PASSWORD"'"}' \
  "$BACKEND_URL/Users/AuthenticateByName")

TOKEN=$(echo "$AUTH_RESPONSE" | sed 's/.*"AccessToken":"\([^"]*\)".*/\1/')
if [ -z "$TOKEN" ]; then
  echo "Failed to extract token from: $AUTH_RESPONSE"
  exit 1
fi
echo "Authenticated"

echo "Triggering library rescan..."
wget -qO- \
  --header='Content-Type: application/json' \
  --post-data='{}' \
  "$BACKEND_URL/Admin/Library/Rescan?api_key=$TOKEN" || true

echo "Waiting for scan to complete..."
for _ in $(seq 1 60); do
  SCAN_STATUS=$(wget -qO- \
    "$BACKEND_URL/Admin/Library/ScanStatus?api_key=$TOKEN" 2>/dev/null) || true
  if echo "$SCAN_STATUS" | grep -q '"scanInProgress":false'; then
    break
  fi
  sleep 2
done

ITEMS=$(wget -qO- \
  "$BACKEND_URL/Items?Recursive=true&IncludeItemTypes=Audio&api_key=$TOKEN" 2>/dev/null) || true
TOTAL=$(echo "$ITEMS" | sed 's/.*"TotalRecordCount":\([0-9]*\).*/\1/')
echo "Library ready: $TOTAL tracks"
