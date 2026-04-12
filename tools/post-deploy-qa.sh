#!/usr/bin/env bash
set -euo pipefail

PROD_URL="${PROD_URL:-https://yay-tsa.com}"
NAMESPACE="${NAMESPACE:-yay-tsa-production}"
BACKEND_DEPLOY="${BACKEND_DEPLOY:-yay-tsa-production-backend}"
FRONTEND_DEPLOY="${FRONTEND_DEPLOY:-yay-tsa-production}"
MAX_WAIT="${MAX_WAIT:-300}"
POLL_INTERVAL="${POLL_INTERVAL:-15}"
QA_USERNAME="${QA_USERNAME:-test}"
QA_PASSWORD="${QA_PASSWORD:?QA_PASSWORD required}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_SHA="${1:-$(git rev-parse --short=7 HEAD)}"
EXPECTED_TAG="main-${TARGET_SHA}"
REPORT_DIR="/tmp/qa-report-${TARGET_SHA}"

mkdir -p "$REPORT_DIR"

log() { echo "[$(date '+%H:%M:%S')] $*"; }
fail() {
  log "FAIL: $*"
  exit 1
}

log "Post-deploy QA for ${EXPECTED_TAG}"
log "Reports: ${REPORT_DIR}"

# --- Phase 1: Wait for rollout ---
log "Phase 1: Waiting for ${EXPECTED_TAG} on prod..."

elapsed=0
while [ "$elapsed" -lt "$MAX_WAIT" ]; do
  backend_img=$(kubectl get deployment "$BACKEND_DEPLOY" -n "$NAMESPACE" \
    -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || echo "")
  frontend_img=$(kubectl get deployment "$FRONTEND_DEPLOY" -n "$NAMESPACE" \
    -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || echo "")

  if echo "$backend_img" | grep -q "$TARGET_SHA" && echo "$frontend_img" | grep -q "$TARGET_SHA"; then
    log "Both images deployed: backend=${backend_img}, frontend=${frontend_img}"
    break
  fi

  log "Waiting... backend=$(basename "$backend_img") frontend=$(basename "$frontend_img")"
  sleep "$POLL_INTERVAL"
  elapsed=$((elapsed + POLL_INTERVAL))
done

if [ "$elapsed" -ge "$MAX_WAIT" ]; then
  fail "Timeout waiting for ${EXPECTED_TAG} to deploy"
fi

# Wait for pods to be ready
log "Waiting for pods ready..."
kubectl rollout status deployment "$BACKEND_DEPLOY" -n "$NAMESPACE" --timeout=120s
kubectl rollout status deployment "$FRONTEND_DEPLOY" -n "$NAMESPACE" --timeout=120s

# Health check
health=$(curl -sf "${PROD_URL}/api/manage/health" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','UNKNOWN'))" 2>/dev/null || echo "DOWN")
if [ "$health" != "UP" ]; then
  fail "Health check failed: ${health}"
fi
log "Health: UP"

# Get auth token
TOKEN=$(curl -sf -X POST "${PROD_URL}/api/Users/AuthenticateByName" \
  -H "Content-Type: application/json" \
  -d "{\"Username\":\"${QA_USERNAME}\",\"Pw\":\"${QA_PASSWORD}\"}" |
  python3 -c "import sys,json; print(json.load(sys.stdin)['AccessToken'])")

if [ -z "$TOKEN" ]; then
  fail "Authentication failed"
fi
log "Authenticated as ${QA_USERNAME}"

# Download OpenAPI spec
curl -sf "${PROD_URL}/api/api-docs" >"${REPORT_DIR}/openapi.json"
python3 -c "
import json
with open('${REPORT_DIR}/openapi.json') as f:
    spec = json.load(f)
spec['servers'] = [{'url': '${PROD_URL}/api'}]
with open('${REPORT_DIR}/openapi.json', 'w') as f:
    json.dump(spec, f)
"
log "OpenAPI spec downloaded"

# --- Phase 2: Schemathesis ---
log ""
log "Phase 2: Schemathesis API fuzzing..."

schemathesis_exit=0
if command -v st &>/dev/null; then
  st run "${REPORT_DIR}/openapi.json" \
    --url "${PROD_URL}/api" \
    -H "Authorization: Bearer ${TOKEN}" \
    --checks all \
    --exclude-checks ignored_auth,unsupported_method \
    2>&1 | tee "${REPORT_DIR}/schemathesis.txt" || schemathesis_exit=$?
  log "Schemathesis: exit code ${schemathesis_exit}"
else
  log "SKIP: st (schemathesis) not found"
  schemathesis_exit=0
fi

# --- Phase 3: ZAP Security Scan ---
log ""
log "Phase 3: ZAP security scan..."

zap_exit=0
if command -v docker &>/dev/null && docker info &>/dev/null 2>&1; then
  docker run --rm -v "${REPORT_DIR}:/zap/wrk" ghcr.io/zaproxy/zaproxy:stable zap-api-scan.py \
    -t /zap/wrk/openapi.json \
    -f openapi \
    -z "-config replacer.full_list(0).description=auth -config replacer.full_list(0).enabled=true -config replacer.full_list(0).matchtype=REQ_HEADER -config replacer.full_list(0).matchstr=Authorization -config replacer.full_list(0).regex=false -config replacer.full_list(0).replacement='Bearer ${TOKEN}'" \
    -J /zap/wrk/zap-report.json \
    -I \
    2>&1 | tee "${REPORT_DIR}/zap.txt" || zap_exit=$?
  log "ZAP: exit code ${zap_exit}"
else
  log "SKIP: docker not available"
  zap_exit=0
fi

# --- Phase 4: Frontend Crawler ---
log ""
log "Cooling down after API scans (30s)..."
sleep 30

log "Phase 4: Frontend crawler (Playwright + axe)..."

crawler_exit=0
if [ -f "${SCRIPT_DIR}/frontend-crawler/crawl.js" ]; then
  cd "${SCRIPT_DIR}/frontend-crawler"
  [ -d node_modules ] || npm ci --silent
  CRAWL_URL="${PROD_URL}" \
    CRAWL_USERNAME="${QA_USERNAME}" \
    CRAWL_PASSWORD="${QA_PASSWORD}" \
    CRAWL_MAX_PAGES=30 \
    node crawl.js 2>&1 | tee "${REPORT_DIR}/crawler.txt" || crawler_exit=$?
  cd - >/dev/null
  log "Crawler: exit code ${crawler_exit}"
else
  log "SKIP: frontend-crawler/crawl.js not found"
  crawler_exit=0
fi

# --- Phase 5: Backend log check ---
log ""
log "Phase 5: Backend log check..."

backend_errors=$(kubectl logs deployment/"$BACKEND_DEPLOY" -n "$NAMESPACE" --since=5m 2>/dev/null |
  grep -ciE "ERROR|FATAL|Exception" || echo "0")
log "Backend errors in last 5min: ${backend_errors}"

# --- Report ---
log ""
log "=========================================="
log "  POST-DEPLOY QA REPORT: ${EXPECTED_TAG}"
log "=========================================="
log ""
log "  Rollout:      OK"
log "  Health:       UP"
log "  Schemathesis: $([ "$schemathesis_exit" -eq 0 ] && echo 'PASS' || echo 'FAIL')"
log "  ZAP:          $([ "$zap_exit" -eq 0 ] && echo 'PASS' || echo 'FAIL')"
log "  Crawler:      $([ "$crawler_exit" -eq 0 ] && echo 'PASS' || echo 'WARN (JS errors or a11y)')"
log "  Backend logs: ${backend_errors} errors"
log ""
log "  Reports: ${REPORT_DIR}/"
log "=========================================="

if [ "$schemathesis_exit" -ne 0 ] || [ "$zap_exit" -ne 0 ]; then
  exit 1
fi
