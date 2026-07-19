#!/usr/bin/env bash
# Push this local project directory to the shared copy on the home server.
# Overwrites the server copy's tracked files; does not touch .git on either end.
set -euo pipefail

HOST="${SYNC_HOST:-109.230.156.80}"
PORT="${SYNC_PORT:-2222}"
REMOTE_USER="${SYNC_USER:-ilia}"
REMOTE_BASE="${SYNC_REMOTE_BASE:-/home/ilia/dev-shared}"

cd "$(dirname "${BASH_SOURCE[0]}")/.."
PROJECT_DIR="$(basename "$PWD")"

echo "Pushing $PWD -> $REMOTE_USER@$HOST:$REMOTE_BASE/$PROJECT_DIR ..."
tar czf - --exclude='.git' -C .. "$PROJECT_DIR" \
  | ssh -p "$PORT" "$REMOTE_USER@$HOST" "mkdir -p '$REMOTE_BASE' && tar xzf - -C '$REMOTE_BASE'"
echo "Done."
