#!/bin/sh
set -e

# Reject unsafe characters that could enable nginx config injection
# Returns 0 if safe, 1 if unsafe characters detected
is_safe_for_config() {
  # Reject newlines, control characters, quotes, semicolons, and other dangerous chars
  # Also reject # and & which break sed substitution (# is delimiter, & expands to matched text)
  if printf '%s\n' "$1" | grep -qE '[[:cntrl:]]|[[:space:]]|[";'\''`\\$#&]'; then
    printf 'ERROR: Unsafe characters detected in value: %s\n' "$1" >&2
    return 1
  fi
  return 0
}

# Validate URL for browser use (allows relative paths like /api)
validate_browser_url() {
  is_safe_for_config "$1" || return 1

  # Allow absolute URLs (http/https) OR relative paths (/path)
  if echo "$1" | grep -qE '^https?://[A-Za-z0-9._:-]+'; then
    return 0
  elif echo "$1" | grep -qE '^/[A-Za-z0-9._~/%+-]*$'; then
    echo "INFO: Using relative path for browser: $1"
    return 0
  else
    echo "ERROR: Invalid browser URL format: $1 (must be http(s):// or /path)" >&2
    return 1
  fi
}

# Validate URL for nginx proxy_pass (MUST be absolute http/https URL)
validate_backend_url() {
  is_safe_for_config "$1" || return 1

  if echo "$1" | grep -qE '^https?://[A-Za-z0-9._:-]+'; then
    return 0
  else
    echo "ERROR: Backend URL must be absolute http(s):// URL, got: $1" >&2
    return 1
  fi
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
# If YAYTSA_SERVER_URL is set -> use it (configured backend)
# If NOT set -> auto-detect local backend based on environment
ENVIRONMENT=$(detect_environment)
echo "Detected environment: $ENVIRONMENT"

if [ -n "$YAYTSA_SERVER_URL" ]; then
  # Explicitly configured server URL
  echo "INFO: Using configured server: $YAYTSA_SERVER_URL"
else
  # Auto-detect local backend based on environment
  echo "INFO: YAYTSA_SERVER_URL not set, auto-detecting local backend..."

  case "$ENVIRONMENT" in
  "kubernetes")
    # Kubernetes: use relative /api path (same as docker-compose)
    # Browser can't resolve internal cluster DNS, nginx proxies to backend
    YAYTSA_SERVER_URL="/api"
    echo "INFO: Using relative path for browser API requests: $YAYTSA_SERVER_URL"
    ;;
  "docker-compose")
    # For Docker Compose: use relative path /api for browser requests
    # Nginx will proxy /api/* to backend (configured separately via YAYTSA_BACKEND_URL)
    # This allows browser to work without knowing internal Docker hostnames
    YAYTSA_SERVER_URL="/api"
    echo "INFO: Using relative path for browser API requests: $YAYTSA_SERVER_URL"
    echo "INFO: Nginx will proxy to backend (configure YAYTSA_BACKEND_URL for nginx)"
    ;;
  *)
    echo "ERROR: Cannot determine backend configuration"
    echo "Please set YAYTSA_SERVER_URL for external server"
    exit 1
    ;;
  esac
fi

# Validate server URL (browser-facing, allows relative paths)
validate_browser_url "${YAYTSA_SERVER_URL}" || {
  echo "SECURITY: Server URL must be a valid HTTP/HTTPS URL or relative path: $YAYTSA_SERVER_URL"
  exit 1
}

# Validate log level
YAYTSA_LOG_LEVEL="${YAYTSA_LOG_LEVEL:-error}"
case "$YAYTSA_LOG_LEVEL" in
debug | info | warn | error | silent) ;;
*)
  echo "WARNING: Invalid YAYTSA_LOG_LEVEL='$YAYTSA_LOG_LEVEL', using 'error'"
  YAYTSA_LOG_LEVEL="error"
  ;;
esac
echo "INFO: Log level set to: $YAYTSA_LOG_LEVEL"

# Generate JavaScript configuration directly (synchronous, prevents race condition)
# Use jq to safely escape JSON values and embed them in JavaScript
# Note: version is baked into static files at Docker build time via sed
CONFIG_JSON=$(jq -n \
  --arg serverUrl "${YAYTSA_SERVER_URL:-}" \
  --arg clientName "${YAYTSA_CLIENT_NAME:-Yaytsa}" \
  --arg deviceName "${YAYTSA_DEVICE_NAME:-Yaytsa Web}" \
  --arg logLevel "${YAYTSA_LOG_LEVEL}" \
  '{
    serverUrl: $serverUrl,
    clientName: $clientName,
    deviceName: $deviceName,
    logLevel: $logLevel
  }')

cat >/var/cache/nginx/config.js <<EOF
// Runtime configuration injected by docker-entrypoint.sh (synchronous)
window.__YAYTSA_CONFIG__ = ${CONFIG_JSON};
EOF

# Also create config.json for async loading (fallback)
printf '%s\n' "$CONFIG_JSON" >/var/cache/nginx/config.json

printf 'Generated runtime config (sanitized for security):\n'
printf '%s\n' "$CONFIG_JSON"

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
    # No inline scripts is valid for React apps - all scripts are external
    CSP_SCRIPT_HASHES="'self'"
    echo "No inline scripts found - using 'self' only for script-src"
  fi
else
  echo "ERROR: $CSP_HASHES_FILE not found"
  echo "CSP hashes must be generated during build - this is a build configuration error"
  exit 1
fi

# Extract server domain for CSP connect-src
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
    # Check if it's a relative path or absolute URL
    if echo "${YAYTSA_SERVER_URL}" | grep -qE '^https?://'; then
      SERVER_DOMAIN=$(echo "${YAYTSA_SERVER_URL}" | sed -E 's#^(https?://[^/]+).*#\1#')
      CSP_CONNECT_SRC_DOMAINS="$SERVER_DOMAIN"
      echo "CSP Mode: AUTO → STRICT - connect-src restricted to: $SERVER_DOMAIN"
    else
      # Relative path - allow same origin only
      CSP_CONNECT_SRC_DOMAINS="'self'"
      echo "CSP Mode: AUTO → SAME-ORIGIN - connect-src restricted to 'self' (relative path)"
    fi
  else
    CSP_CONNECT_SRC_DOMAINS="*"
    echo "CSP Mode: AUTO → RELAXED - connect-src allows all domains (YAYTSA_SERVER_URL not set)"
  fi
else
  echo "ERROR: Invalid CSP_MODE='$CSP_MODE'. Must be 'strict', 'relaxed', or 'auto'"
  exit 1
fi

# Media path for X-Accel-Redirect (must match volume mount in deployment)
YAYTSA_MEDIA_PATH="${YAYTSA_MEDIA_PATH:-/media}"
echo "INFO: Media path for X-Accel-Redirect: $YAYTSA_MEDIA_PATH"

# Backend URL for nginx proxying (MUST be absolute http/https URL)
# In docker-compose: defaults to http://backend:8096 (yaytsa-backend service)
# In Kubernetes: use internal service discovery URL
# NEVER falls back to YAYTSA_SERVER_URL (which can be relative /api)
if [ -z "$YAYTSA_BACKEND_URL" ]; then
  case "$ENVIRONMENT" in
  "docker-compose")
    YAYTSA_BACKEND_URL="http://backend:8096"
    echo "INFO: Using default docker-compose backend URL: $YAYTSA_BACKEND_URL"
    ;;
  "kubernetes")
    K8S_NAMESPACE="${KUBERNETES_NAMESPACE:-yaytsa-production}"
    YAYTSA_BACKEND_URL="http://yaytsa-server.${K8S_NAMESPACE}.svc.cluster.local:8080"
    echo "INFO: Using default Kubernetes backend URL: $YAYTSA_BACKEND_URL"
    ;;
  *)
    echo "ERROR: YAYTSA_BACKEND_URL is required for nginx proxy configuration"
    exit 1
    ;;
  esac
fi

BACKEND_URL="$YAYTSA_BACKEND_URL"
echo "INFO: Backend URL for nginx: $BACKEND_URL"

# Validate backend URL (MUST be absolute http/https for nginx proxy_pass)
validate_backend_url "${BACKEND_URL}" || {
  echo "SECURITY: Backend URL must be an absolute http(s):// URL for nginx proxy_pass"
  exit 1
}

# Generate nginx.conf from template with CSP hash, domain, backend URL, and media path substitution
# Use '#' as delimiter instead of '/' to avoid conflicts with slashes in base64 hashes and URLs
sed -e "s#__CSP_SCRIPT_HASHES__#$CSP_SCRIPT_HASHES#g" \
  -e "s#__CSP_CONNECT_SRC_DOMAINS__#$CSP_CONNECT_SRC_DOMAINS#g" \
  -e "s#__BACKEND_URL__#$BACKEND_URL#g" \
  -e "s#__MEDIA_PATH__#$YAYTSA_MEDIA_PATH#g" \
  /etc/nginx/nginx.conf.template >/var/cache/nginx/nginx.conf
echo "Generated nginx.conf with runtime CSP hashes, domains, backend URL, and media path"

# Execute the main command (nginx) with generated config
exec nginx -c /var/cache/nginx/nginx.conf -g "daemon off;"
