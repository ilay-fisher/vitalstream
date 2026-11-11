#!/bin/sh
# Idempotently creates the `alerts` and `guidelines` indices (CONTRACTS section 5).
# An existing index is left untouched; drop it manually if a mapping must change.
set -eu

ES="${ELASTICSEARCH_URL:-http://elasticsearch:9200}"

create_index() { # name mapping-file
  code=$(curl -s -o /dev/null -w '%{http_code}' -I "$ES/$1")
  if [ "$code" = "200" ]; then
    echo "index $1 exists"
    return
  fi
  curl -sf -X PUT "$ES/$1" -H 'Content-Type: application/json' --data-binary "@$2"
  echo
}

create_index alerts /init/alerts.json
create_index guidelines /init/guidelines.json
curl -sf "$ES/_cat/indices/alerts,guidelines?h=index,health,status"
