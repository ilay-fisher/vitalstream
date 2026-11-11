#!/bin/sh
# Fail closed: refuse to start an unauthenticated proxy in front of n8n.
set -eu

if [ -z "${N8N_PROXY_PASSWORD:-}" ]; then
  echo "FATAL: N8N_PROXY_PASSWORD is empty; set it (e.g. in .env) to use the tools profile" >&2
  exit 1
fi
export N8N_PROXY_USER="${N8N_PROXY_USER:-admin}"
N8N_PROXY_HASH=$(caddy hash-password --plaintext "$N8N_PROXY_PASSWORD")
export N8N_PROXY_HASH
exec caddy run --config /etc/caddy/Caddyfile --adapter caddyfile
