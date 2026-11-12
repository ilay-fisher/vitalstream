#!/usr/bin/env bash
# Creates the kind cluster, builds + loads the service images that exist, installs dev infra and the
# VitalStream chart. Idempotent: rerun after code or chart changes.
#
#   deploy/kind/up.sh
#   CLUSTER=my-cluster BUILD=0 deploy/kind/up.sh     # skip docker build, reuse local images
#
# Needs: docker, kind, kubectl, helm, openssl. On Colima: export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
set -euo pipefail

CLUSTER="${CLUSTER:-vitalstream-platform}"
BUILD="${BUILD:-1}"
APP_NS="${APP_NS:-vitalstream}"
INFRA_NS=vitalstream-infra
RELEASE="${RELEASE:-vitalstream}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
CHART="$ROOT/deploy/helm/vitalstream"
CTX="kind-$CLUSTER"

# component:build-context for every image that has a Dockerfile on main.
IMAGES=(ingest-gateway:services/ingest-gateway copilot:services/copilot)

log() { printf '\n==> %s\n' "$*"; }
k() { kubectl --context "$CTX" "$@"; }

log "kind cluster $CLUSTER"
if ! kind get clusters | grep -qx "$CLUSTER"; then
  kind create cluster --name "$CLUSTER" --config "$HERE/kind-config.yaml" --wait 120s
fi

log "images"
for entry in "${IMAGES[@]}"; do
  name="${entry%%:*}"; ctxdir="${entry#*:}"
  image="vitalstream/$name:local"
  if [ "$BUILD" = "1" ] || ! docker image inspect "$image" >/dev/null 2>&1; then
    docker build -t "$image" "$ROOT/$ctxdir"
  fi
  kind load docker-image "$image" --name "$CLUSTER"
done

log "namespaces and secrets"
k create namespace "$APP_NS" --dry-run=client -o yaml | k apply -f -
k create namespace "$INFRA_NS" --dry-run=client -o yaml | k apply -f -
# Random 48 hex chars satisfy the APP_ENV=prod guard (>= 32 chars, not a dev default). Created once, reused after.
rand() { openssl rand -hex 24; }
if ! k -n "$INFRA_NS" get secret infra-credentials >/dev/null 2>&1; then
  PG="$(rand)"; RMQ="$(rand)"
  k -n "$INFRA_NS" create secret generic infra-credentials \
    --from-literal=POSTGRES_PASSWORD="$PG" --from-literal=RABBITMQ_PASSWORD="$RMQ"
  k -n "$APP_NS" create secret generic vitalstream-secrets \
    --from-literal=INGEST_API_KEY="$(rand)" \
    --from-literal=COPILOT_ADMIN_TOKEN="$(rand)" \
    --from-literal=MCP_TOKEN="$(rand)" \
    --from-literal=LOG_PSEUDONYM_KEY="$(rand)" \
    --from-literal=POSTGRES_PASSWORD="$PG" \
    --from-literal=RABBITMQ_PASSWORD="$RMQ"
fi

# clinical-api auth (CONTRACTS 4.2): a random HMAC secret, and the copilot's SERVICE token signed with
# it. Created once and reused; also added to a vitalstream-secrets that predates this. Neither value is
# printed or passed as an argument: the secret reaches the minting script through its environment and
# the Secret manifest goes to kubectl on stdin.
if ! k -n "$APP_NS" get secret vitalstream-secrets -o jsonpath='{.data.CLINICAL_JWT_SECRET}' 2>/dev/null | grep -q .; then
  log "clinical-api JWT secret and copilot service token"
  ISSUER="${CLINICAL_JWT_ISSUER:-$(sed -n 's/^ *issuer: *"\{0,1\}\([^"#]*[^" #]\)"\{0,1\} *$/\1/p' "$CHART/values-kind.yaml" | head -n 1)}"
  JWT_ISSUER="${ISSUER:-vitalstream-dev}"
  JWT_SECRET="$(openssl rand -hex 32)" # 64 hex chars = 32 random bytes
  export JWT_ISSUER JWT_SECRET
  SERVICE_JWT="$(python3 "$HERE/mint-service-token.py")"
  k -n "$APP_NS" apply -f - >/dev/null <<EOF
apiVersion: v1
kind: Secret
metadata:
  name: vitalstream-secrets
type: Opaque
stringData:
  CLINICAL_JWT_SECRET: "$JWT_SECRET"
  CLINICAL_API_TOKEN: "$SERVICE_JWT"
EOF
  unset JWT_SECRET SERVICE_JWT
fi

log "infrastructure"
k -n "$INFRA_NS" create configmap postgres-init --from-file=init.sql="$ROOT/infra/postgres/init.sql" \
  --dry-run=client -o yaml | k apply -f -
k -n "$INFRA_NS" create configmap es-init --from-file="$ROOT/infra/elasticsearch" \
  --dry-run=client -o yaml | k apply -f -
k apply -f "$HERE/infra.yaml"
for d in kafka redis postgres elasticsearch; do
  k -n "$INFRA_NS" rollout status "deploy/$d" --timeout=420s
done

log "helm release $RELEASE"
helm upgrade --install "$RELEASE" "$CHART" --kube-context "$CTX" --namespace "$APP_NS" \
  -f "$CHART/values-kind.yaml" --wait --timeout 8m

log "done"
k -n "$APP_NS" get pods
