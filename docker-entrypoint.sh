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

# Use jq to safely generate JSON configuration (prevents injection)
# This approach ensures proper JSON escaping of all values
# Write to /var/cache/nginx (writable volume) since root filesystem is read-only
jq -n \
  --arg serverUrl "${JELLYFIN_SERVER_URL:-}" \
  --arg clientName "${JELLYFIN_CLIENT_NAME:-Jellyfin Mini Client}" \
  --arg deviceName "${JELLYFIN_DEVICE_NAME:-Jellyfin Web}" \
  --arg version "${APP_VERSION:-0.1.0}" \
  '{
    serverUrl: $serverUrl,
    clientName: $clientName,
    deviceName: $deviceName,
    version: $version
  }' >/var/cache/nginx/config.json

# Generate JavaScript that safely loads the JSON config
cat >/var/cache/nginx/config.js <<'EOF'
// Runtime configuration loaded securely from JSON
// Initialize config object immediately (prevents errors if accessed before async load)
window.__JELLYFIN_CONFIG__ = {
  serverUrl: '',
  clientName: 'Jellyfin Mini Client',
  deviceName: 'Jellyfin Web',
  version: '0.1.0'
};

// Load actual configuration from JSON (asynchronous)
(async function() {
  try {
    const response = await fetch('/config.json');
    if (response.ok) {
      window.__JELLYFIN_CONFIG__ = await response.json();
    }
  } catch (error) {
    console.error('Failed to load configuration:', error);
    // Config already initialized with defaults above
  }
})();
EOF

echo "Generated runtime config (sanitized for security):"
cat /var/cache/nginx/config.json

# Generate nginx.conf with CSP hashes computed from index.html
# Extract all inline scripts and compute their SHA-256 hashes
INDEX_HTML="/usr/share/nginx/html/index.html"
if [ -f "$INDEX_HTML" ]; then
  echo "Computing CSP hashes for inline scripts..."

  # Extract inline scripts between <script> tags (multiline, excluding src= scripts)
  # Then compute SHA-256 hash for each script and format as 'sha256-hash'
  CSP_HASHES=$(awk '
    /<script[^>]*>/ && !/<script[^>]*src=/ {
      in_script = 1
      script = ""
      next
    }
    in_script {
      if (/<\/script>/) {
        in_script = 0
        if (script != "") print script
        script = ""
      } else {
        script = script $0 "\n"
      }
    }
  ' "$INDEX_HTML" |
    while IFS= read -r script; do
      if [ -n "$script" ]; then
        printf '%s' "$script" | openssl dgst -sha256 -binary | openssl base64 |
          awk '{printf "'\''sha256-%s'\'' ", $0}'
      fi
    done | sed 's/ $//') # Remove trailing space

  if [ -z "$CSP_HASHES" ]; then
    echo "WARNING: No inline scripts found in index.html, using 'self' only"
    CSP_HASHES="'self'"
  else
    echo "Computed CSP hashes: $CSP_HASHES"
    CSP_HASHES="'self' $CSP_HASHES"
  fi
else
  echo "WARNING: index.html not found, using 'self' for CSP"
  CSP_HASHES="'self'"
fi

# Generate nginx.conf from template with CSP hash substitution
sed "s/__CSP_SCRIPT_HASHES__/$CSP_HASHES/g" /etc/nginx/nginx.conf.template >/var/cache/nginx/nginx.conf
echo "Generated nginx.conf with runtime CSP hashes"

# Execute the main command (nginx) with generated config
exec nginx -c /var/cache/nginx/nginx.conf -g "daemon off;"
