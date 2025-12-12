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

# Detect runtime environment
detect_environment() {
  # Check for Kubernetes
  if [ -f /run/secrets/kubernetes.io/serviceaccount/token ] || [ -n "$KUBERNETES_SERVICE_HOST" ]; then
    echo "kubernetes"
    return
  fi

  # Check for Docker Compose
  if [ -n "$COMPOSE_PROJECT_NAME" ] || [ -f /.dockerenv ]; then
    echo "docker-compose"
    return
  fi

  echo "unknown"
}

# Determine server URL with smart fallback logic
# If YAYTSA_SERVER_URL is set -> use it (Jellyfin or custom backend)
# If NOT set -> auto-detect local backend based on environment
ENVIRONMENT=$(detect_environment)
echo "Detected environment: $ENVIRONMENT"

if [ -n "$YAYTSA_SERVER_URL" ]; then
  # Explicitly configured server URL (Jellyfin or custom backend)
  echo "INFO: Using configured server: $YAYTSA_SERVER_URL"
else
  # Auto-detect local backend based on environment
  echo "INFO: YAYTSA_SERVER_URL not set, auto-detecting local backend..."

  case "$ENVIRONMENT" in
  "kubernetes")
    # Kubernetes service discovery
    YAYTSA_SERVER_URL="http://jellyfin.default.svc.cluster.local:8096"
    echo "INFO: Using Kubernetes service discovery: $YAYTSA_SERVER_URL"
    ;;
  "docker-compose")
    # Check if media-server service is available (local backend)
    if wget --spider --quiet --timeout=2 --tries=1 http://media-server:8096/health 2>/dev/null ||
      wget --spider --quiet --timeout=2 --tries=1 http://media-server:8096 2>/dev/null; then
      YAYTSA_SERVER_URL="http://media-server:8096"
      echo "INFO: Using local media-server backend: $YAYTSA_SERVER_URL"
    else
      echo "ERROR: Local backend mode but media-server service not reachable"
      echo "Either:"
      echo "  1. Set YAYTSA_SERVER_URL to use external server"
      echo "  2. Start local backend: docker compose --profile local-backend up"
      exit 1
    fi
    ;;
  *)
    echo "ERROR: Cannot determine backend configuration"
    echo "Please set YAYTSA_SERVER_URL for external server"
    exit 1
    ;;
  esac
fi

# Validate server URL
validate_url "${YAYTSA_SERVER_URL}" || {
  echo "SECURITY: Server URL must be a valid HTTP/HTTPS URL: $YAYTSA_SERVER_URL"
  exit 1
}

# Generate JavaScript configuration directly (synchronous, prevents race condition)
# Use jq to safely escape JSON values and embed them in JavaScript
CONFIG_JSON=$(jq -n \
  --arg serverUrl "${YAYTSA_SERVER_URL:-}" \
  --arg clientName "${YAYTSA_CLIENT_NAME:-Yaytsa}" \
  --arg deviceName "${YAYTSA_DEVICE_NAME:-Yaytsa Web}" \
  --arg version "${APP_VERSION:-0.1.0}" \
  '{
    serverUrl: $serverUrl,
    clientName: $clientName,
    deviceName: $deviceName,
    version: $version
  }')

cat >/var/cache/nginx/config.js <<EOF
// Runtime configuration injected by docker-entrypoint.sh (synchronous)
window.__YAYTSA_CONFIG__ = ${CONFIG_JSON};
EOF

# Also create config.json for async loading (fallback)
echo "$CONFIG_JSON" >/var/cache/nginx/config.json

echo "Generated runtime config (sanitized for security):"
echo "$CONFIG_JSON"

# Load CSP hashes from build-time generated JSON file
# Vite plugin computes hashes during build and stores them in .csp-hashes.json
CSP_HASHES_FILE="/usr/share/nginx/html/.csp-hashes.json"

if [ -f "$CSP_HASHES_FILE" ]; then
  echo "Loading CSP hashes from build artifacts: $CSP_HASHES_FILE"

  # Extract hashes from JSON and format for CSP directive
  SCRIPT_HASHES=$(jq -r '.scriptHashes | map("'"'"'" + . + "'"'"'") | join(" ")' "$CSP_HASHES_FILE")

  if [ -n "$SCRIPT_HASHES" ]; then
    CSP_SCRIPT_HASHES="'self' $SCRIPT_HASHES"
    echo "Loaded CSP script hashes: $CSP_SCRIPT_HASHES"
  else
    echo "WARNING: No script hashes found in $CSP_HASHES_FILE, using 'unsafe-inline'"
    CSP_SCRIPT_HASHES="'self' 'unsafe-inline'"
  fi
else
  echo "WARNING: $CSP_HASHES_FILE not found, falling back to 'unsafe-inline'"
  echo "This reduces CSP security but allows the app to function"
  CSP_SCRIPT_HASHES="'self' 'unsafe-inline'"
fi

# Extract Jellyfin server domain for CSP connect-src
# CSP_MODE controls security policy strictness:
#   - "strict": Only allow pre-configured YAYTSA_SERVER_URL domain
#   - "relaxed": Allow all domains (for private deployments with multiple servers)
#   - "auto" (default): strict if YAYTSA_SERVER_URL is set, otherwise relaxed
CSP_MODE="${CSP_MODE:-auto}"

if [ "$CSP_MODE" = "relaxed" ]; then
  CSP_CONNECT_SRC_DOMAINS="*"
  echo "CSP Mode: RELAXED - connect-src allows all domains (private deployment mode)"
elif [ "$CSP_MODE" = "strict" ]; then
  if [ -n "${YAYTSA_SERVER_URL}" ]; then
    SERVER_DOMAIN=$(echo "${YAYTSA_SERVER_URL}" | sed -E 's#^(https?://[^/]+).*#\1#')
    CSP_CONNECT_SRC_DOMAINS="$SERVER_DOMAIN"
    echo "CSP Mode: STRICT - connect-src restricted to: $SERVER_DOMAIN"
  else
    echo "ERROR: CSP_MODE=strict requires YAYTSA_SERVER_URL to be set"
    exit 1
  fi
elif [ "$CSP_MODE" = "auto" ]; then
  if [ -n "${YAYTSA_SERVER_URL}" ]; then
    SERVER_DOMAIN=$(echo "${YAYTSA_SERVER_URL}" | sed -E 's#^(https?://[^/]+).*#\1#')
    CSP_CONNECT_SRC_DOMAINS="$SERVER_DOMAIN"
    echo "CSP Mode: AUTO → STRICT - connect-src restricted to: $SERVER_DOMAIN"
  else
    CSP_CONNECT_SRC_DOMAINS="*"
    echo "CSP Mode: AUTO → RELAXED - connect-src allows all domains (YAYTSA_SERVER_URL not set)"
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
