#!/usr/bin/env bash
# Port-forwards the enabled services on 26081 (ingest-gateway) and 26083 (copilot) and curls their
# health endpoints. Exits non-zero if any check fails.
set -euo pipefail

CLUSTER="${CLUSTER:-vitalstream-platform}"
APP_NS="${APP_NS:-vitalstream}"
CTX="kind-$CLUSTER"
pids=()
cleanup() { for p in "${pids[@]:-}"; do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

kubectl --context "$CTX" -n "$APP_NS" port-forward svc/ingest-gateway 26081:8081 >/dev/null & pids+=($!)
kubectl --context "$CTX" -n "$APP_NS" port-forward svc/copilot 26083:8083 >/dev/null & pids+=($!)
sleep 3

rc=0
check() { # label url
  code=$(curl -s -o /tmp/vs-smoke.out -w '%{http_code}' "$2" || true)
  printf '%-34s HTTP %s  %s\n' "$1" "$code" "$(head -c 160 /tmp/vs-smoke.out)"
  [ "$code" = "200" ] || rc=1
}
check "ingest-gateway /actuator/health" http://localhost:26081/actuator/health
check "ingest-gateway liveness"         http://localhost:26081/actuator/health/liveness
check "ingest-gateway readiness"        http://localhost:26081/actuator/health/readiness
check "copilot /health"                 http://localhost:26083/health
exit "$rc"
