#!/usr/bin/env bash
# Fresh-cluster acceptance test for the yay-tsa-stack umbrella chart.
#
# Proves the "one helm install on any cluster" claim end to end:
#   kind create cluster -> helm install -> backend rollout -> login as the
#   bootstrap admin -> browse the (empty) library.
#
# Requires: kind, kubectl, helm, curl, jq, docker. Usage: ./kind-acceptance-test.sh
set -euo pipefail

CLUSTER="${CLUSTER:-yaytsa-accept}"
NS="${NS:-yay-tsa}"
RELEASE="${RELEASE:-yay-tsa}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PW="${ADMIN_PW:-Acceptance123!}"
# Override image tags to pin a known-good build (defaults to the chart's :latest).
BACKEND_TAG="${BACKEND_TAG:-}"
FRONTEND_TAG="${FRONTEND_TAG:-}"
CHART_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PF_PID=""

TAG_ARGS=()
[[ -n "$BACKEND_TAG" ]] && TAG_ARGS+=(--set "yay-tsa-v2.backend.image.tag=${BACKEND_TAG}")
[[ -n "$FRONTEND_TAG" ]] && TAG_ARGS+=(--set "yay-tsa.frontend.image.tag=${FRONTEND_TAG}")

cleanup() {
  [[ -n "$PF_PID" ]] && kill "$PF_PID" 2>/dev/null || true
  kind delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "==> [1/6] Creating kind cluster '$CLUSTER'"
kind create cluster --name "$CLUSTER" --wait 120s

echo "==> [2/6] Building chart dependencies"
helm dependency build "$CHART_DIR" >/dev/null

echo "==> [3/6] helm install (bundled DB, admin bootstrap, everything else off)"
helm install "$RELEASE" "$CHART_DIR" \
  --namespace "$NS" --create-namespace \
  --set "yay-tsa-v2.backend.adminBootstrap.username=${ADMIN_USER}" \
  --set "yay-tsa-v2.backend.adminBootstrap.password=${ADMIN_PW}" \
  ${TAG_ARGS[@]+"${TAG_ARGS[@]}"}

echo "==> [4/6] Waiting for the bundled Postgres and the backend to come up"
kubectl -n "$NS" rollout status "statefulset/${RELEASE}-postgres" --timeout=300s
kubectl -n "$NS" rollout status "deployment/${RELEASE}-yay-tsa-v2-backend" --timeout=420s

echo "==> [5/6] Port-forwarding the backend and logging in as the bootstrap admin"
kubectl -n "$NS" port-forward "svc/${RELEASE}-yay-tsa-v2-backend" 18080:80 >/dev/null 2>&1 &
PF_PID=$!
for _ in $(seq 1 30); do
  curl -fsS "http://localhost:18080/manage/health" >/dev/null 2>&1 && break
  sleep 2
done

TOKEN=$(curl -fsS -X POST "http://localhost:18080/Users/AuthenticateByName" \
  -H 'Content-Type: application/json' \
  -d "{\"Username\":\"${ADMIN_USER}\",\"Pw\":\"${ADMIN_PW}\"}" | jq -r '.AccessToken')

if [[ -z "$TOKEN" || "$TOKEN" == "null" ]]; then
  echo "FAIL: bootstrap admin login did not return a token"
  exit 1
fi
echo "    login OK (token ${TOKEN:0:8}...)"

echo "==> [6/6] Browsing the empty library"
ITEMS=$(curl -fsS "http://localhost:18080/Items?Recursive=true" -H "Authorization: Bearer ${TOKEN}")
COUNT=$(echo "$ITEMS" | jq -r '.TotalRecordCount')
if [[ "$COUNT" != "0" ]]; then
  echo "FAIL: expected an empty library (TotalRecordCount 0), got: $COUNT"
  exit 1
fi

echo
echo "PASS: fresh kind cluster -> helm install -> admin login -> empty library (TotalRecordCount=0)"
