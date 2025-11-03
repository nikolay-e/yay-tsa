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

echo "Generated runtime config (sanitized for security):"
echo "$CONFIG_JSON"

# Generate nginx.conf with simplified CSP for script-src
# SvelteKit (with adapter-static) compiles all JavaScript to external files
# Inline scripts should not be present in production builds
echo "Using 'self' for CSP script-src (SvelteKit production build)"
CSP_HASHES="'self'"

# Generate nginx.conf from template with CSP hash substitution
# Use '#' as delimiter instead of '/' to avoid conflicts with slashes in base64 hashes
sed "s#__CSP_SCRIPT_HASHES__#$CSP_HASHES#g" /etc/nginx/nginx.conf.template >/var/cache/nginx/nginx.conf
echo "Generated nginx.conf with runtime CSP hashes"

# Execute the main command (nginx) with generated config
exec nginx -c /var/cache/nginx/nginx.conf -g "daemon off;"
