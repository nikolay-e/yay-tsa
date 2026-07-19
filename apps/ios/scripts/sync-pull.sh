#!/usr/bin/env bash
# Pull the shared copy from the home server over this local project directory.
# Overwrites local tracked files with the server's version; does not touch .git.
# Note: files deleted on the server are NOT deleted locally by this script.
set -euo pipefail

HOST="${SYNC_HOST:-109.230.156.80}"
PORT="${SYNC_PORT:-2222}"
REMOTE_USER="${SYNC_USER:-ilia}"
REMOTE_BASE="${SYNC_REMOTE_BASE:-/home/ilia/dev-shared}"

cd "$(dirname "${BASH_SOURCE[0]}")/.."
PROJECT_DIR="$(basename "$PWD")"

echo "Pulling $REMOTE_USER@$HOST:$REMOTE_BASE/$PROJECT_DIR -> $PWD ..."
ssh -p "$PORT" "$REMOTE_USER@$HOST" "tar czf - --exclude='.git' -C '$REMOTE_BASE' '$PROJECT_DIR'" \
  | tar xzf - --strip-components=1 -C .
echo "Done."
