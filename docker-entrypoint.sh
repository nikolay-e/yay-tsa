#!/bin/sh
set -e

# Validate environment variables to prevent injection attacks
validate_url() {
  # Basic URL validation - must start with http:// or https://
  echo "$1" | grep -qE '^https?://' || {
    echo "ERROR: Invalid URL format: $1" >&2
    return 1
  }
}

# Validate server URL if provided
if [ -n "${JELLYFIN_SERVER_URL}" ]; then
  validate_url "${JELLYFIN_SERVER_URL}" || {
    echo "SECURITY: JELLYFIN_SERVER_URL must be a valid HTTP/HTTPS URL"
    exit 1
  }
fi

# Generate JavaScript configuration directly (synchronous, prevents race condition)
# Use jq to safely escape JSON values and embed them in JavaScript
CONFIG_JSON=$(jq -n \
  --arg serverUrl "${JELLYFIN_SERVER_URL:-}" \
  --arg clientName "${JELLYFIN_CLIENT_NAME:-Jellyfin Mini Client}" \
  --arg deviceName "${JELLYFIN_DEVICE_NAME:-Jellyfin Web}" \
  --arg version "${APP_VERSION:-0.1.0}" \
  '{
    serverUrl: $serverUrl,
    clientName: $clientName,
    deviceName: $deviceName,
    version: $version
  }')

cat >/var/cache/nginx/config.js <<EOF
// Runtime configuration injected by docker-entrypoint.sh (synchronous)
window.__JELLYFIN_CONFIG__ = ${CONFIG_JSON};
EOF

# Also create config.json for async loading (fallback)
echo "$CONFIG_JSON" >/var/cache/nginx/config.json

echo "Generated runtime config (sanitized for security):"
echo "$CONFIG_JSON"

# Extract CSP hashes from index.html inline scripts
# SvelteKit generates inline initialization scripts that need CSP hashes
echo "Extracting CSP hashes from index.html inline scripts..."

# Extract script content between <script> tags and generate CSP hash
# Use sed to extract content, then hash it
SCRIPT_HASH=$(sed -n '/<script>/,/<\/script>/p' /usr/share/nginx/html/index.html |
  sed '/<script>/d;/<\/script>/d' |
  openssl dgst -sha256 -binary |
  openssl base64)

# Build CSP script-src directive with extracted hash
CSP_SCRIPT_HASHES="'self' 'sha256-$SCRIPT_HASH'"

echo "Generated CSP script-src with inline script hash: sha256-$SCRIPT_HASH"

# Extract Jellyfin server domain for CSP connect-src
# CSP_MODE controls security policy strictness:
#   - "strict": Only allow pre-configured JELLYFIN_SERVER_URL domain
#   - "relaxed": Allow all domains (for private deployments with multiple servers)
#   - "auto" (default): strict if JELLYFIN_SERVER_URL is set, otherwise relaxed
CSP_MODE="${CSP_MODE:-auto}"

if [ "$CSP_MODE" = "relaxed" ]; then
  CSP_CONNECT_SRC_DOMAINS="*"
  echo "CSP Mode: RELAXED - connect-src allows all domains (private deployment mode)"
elif [ "$CSP_MODE" = "strict" ]; then
  if [ -n "${JELLYFIN_SERVER_URL}" ]; then
    JELLYFIN_DOMAIN=$(echo "${JELLYFIN_SERVER_URL}" | sed -E 's#^(https?://[^/]+).*#\1#')
    CSP_CONNECT_SRC_DOMAINS="$JELLYFIN_DOMAIN"
    echo "CSP Mode: STRICT - connect-src restricted to: $JELLYFIN_DOMAIN"
  else
    echo "ERROR: CSP_MODE=strict requires JELLYFIN_SERVER_URL to be set"
    exit 1
  fi
elif [ "$CSP_MODE" = "auto" ]; then
  if [ -n "${JELLYFIN_SERVER_URL}" ]; then
    JELLYFIN_DOMAIN=$(echo "${JELLYFIN_SERVER_URL}" | sed -E 's#^(https?://[^/]+).*#\1#')
    CSP_CONNECT_SRC_DOMAINS="$JELLYFIN_DOMAIN"
    echo "CSP Mode: AUTO → STRICT - connect-src restricted to: $JELLYFIN_DOMAIN"
  else
    CSP_CONNECT_SRC_DOMAINS="*"
    echo "CSP Mode: AUTO → RELAXED - connect-src allows all domains (JELLYFIN_SERVER_URL not set)"
  fi
else
  echo "ERROR: Invalid CSP_MODE='$CSP_MODE'. Must be 'strict', 'relaxed', or 'auto'"
  exit 1
fi

# Generate nginx.conf from template with CSP hash and domain substitution
# Use '#' as delimiter instead of '/' to avoid conflicts with slashes in base64 hashes and URLs
sed -e "s#__CSP_SCRIPT_HASHES__#$CSP_SCRIPT_HASHES#g" \
  -e "s#__CSP_CONNECT_SRC_DOMAINS__#$CSP_CONNECT_SRC_DOMAINS#g" \
  /etc/nginx/nginx.conf.template >/var/cache/nginx/nginx.conf
echo "Generated nginx.conf with runtime CSP hashes and domains"

# Execute the main command (nginx) with generated config
exec nginx -c /var/cache/nginx/nginx.conf -g "daemon off;"
