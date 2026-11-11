#!/usr/bin/env bash
# Checks every infrastructure service (container health + a functional probe that proves the init
# step ran) and prints a table. Exit code is non-zero if any required check fails.
# Optional services (profile `tools`) are reported only when their container exists.
set -u

declare -a ROWS=()
FAIL=0

container_health() { # container -> healthy|unhealthy|starting|none|missing
  docker inspect -f '{{if .State.Running}}{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}{{else}}stopped{{end}}' \
    "vitalstream-$1" 2>/dev/null || echo missing
}

record() { # service status detail
  ROWS+=("$1|$2|$3")
  [ "$2" = "OK" ] || [ "$2" = "SKIP" ] || FAIL=1
}

# check <service> <probe-command...>: probe prints a short detail on stdout and exits 0 when good.
check() {
  local svc=$1; shift
  local health; health=$(container_health "$svc")
  if [ "$health" != "healthy" ]; then
    record "$svc" "FAIL" "container: $health"
    return
  fi
  local out
  if out=$("$@" 2>&1); then
    record "$svc" "OK" "$out"
  else
    record "$svc" "FAIL" "probe failed: $(echo "$out" | head -c 120 | tr '\n' ' ')"
  fi
}

dx() { local c=$1; shift; docker exec "vitalstream-$c" "$@"; }

probe_kafka() {
  local topics; topics=$(dx kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --list) || return 1
  for t in vitals.raw alerts.detected alerts.detected.dlq; do
    echo "$topics" | grep -qx "$t" || { echo "missing topic $t"; return 1; }
  done
  echo "topics: vitals.raw alerts.detected alerts.detected.dlq"
}

probe_postgres() {
  local n ext schemas
  n=$(dx postgres psql -U vitalstream -d vitalstream -tAc "select count(*) from clinical.patients") || return 1
  ext=$(dx postgres psql -U vitalstream -d vitalstream -tAc "select count(*) from pg_extension where extname='vector'")
  schemas=$(dx postgres psql -U vitalstream -d vitalstream -tAc "select count(*) from information_schema.schemata where schema_name in ('clinical','copilot')")
  [ "$n" -ge 20 ] && [ "$ext" = "1" ] && [ "$schemas" = "2" ] || { echo "patients=$n vector=$ext schemas=$schemas"; return 1; }
  echo "patients=$n vector ext, schemas clinical+copilot"
}

probe_redis() { [ "$(dx redis redis-cli ping)" = "PONG" ] && echo "PONG"; }

probe_cassandra() {
  local t; t=$(dx cassandra cqlsh -e "DESCRIBE TABLES" -k vitalstream) || return 1
  echo "$t" | grep -q vitals_raw && echo "$t" | grep -q vitals_by_minute || { echo "tables missing"; return 1; }
  echo "keyspace vitalstream: vitals_raw, vitals_by_minute"
}

probe_elasticsearch() {
  local idx; idx=$(curl -sf "http://localhost:9200/_cat/indices/alerts,guidelines?h=index") || return 1
  echo "$idx" | grep -qx alerts && echo "$idx" | grep -qx guidelines || { echo "indices missing"; return 1; }
  echo "indices: alerts, guidelines"
}

probe_rabbitmq() {
  local q ex
  q=$(curl -sf -u vitalstream:${RABBITMQ_PASSWORD:-vitalstream} "http://localhost:15672/api/queues/%2F" | tr ',' '\n' | grep '"name"' ) || return 1
  ex=$(curl -sf -u vitalstream:${RABBITMQ_PASSWORD:-vitalstream} "http://localhost:15672/api/exchanges/%2F/alerts.notify") || { echo "exchange missing"; return 1; }
  for name in notify.critical notify.all alerts.notify.dlq; do
    echo "$q" | grep -q "\"$name\"" || { echo "missing queue $name"; return 1; }
  done
  echo "exchange alerts.notify; queues notify.critical notify.all (+dlq)"
}

probe_localstack() {
  dx localstack awslocal s3api head-bucket --bucket vitalstream-archive >/dev/null 2>&1 || { echo "bucket missing"; return 1; }
  local q; q=$(dx localstack awslocal sqs list-queues --query 'QueueUrls' --output text) || return 1
  echo "$q" | grep -q 'alert-escalations-dlq' && echo "$q" | grep -q 'alert-escalations' || { echo "queues missing"; return 1; }
  echo "s3 vitalstream-archive; sqs alert-escalations (+dlq)"
}

probe_prometheus() {
  curl -sf http://localhost:9090/api/v1/targets >/dev/null || return 1
  local up; up=$(curl -sf 'http://localhost:9090/api/v1/query?query=up' | grep -o '"value":\[[^]]*"1"\]' | wc -l | tr -d ' ')
  echo "ready; ${up} target(s) up"
}

probe_grafana() {
  curl -sf -u admin:${GRAFANA_ADMIN_PASSWORD:-admin} "http://localhost:3001/api/search?query=VitalStream%20Overview" | grep -q 'VitalStream Overview' \
    || { echo "dashboard not provisioned"; return 1; }
  echo "dashboard 'VitalStream Overview' provisioned"
}

probe_http() { curl -sf "$1" >/dev/null && echo "$2"; }

check kafka          probe_kafka
check kafka-ui       probe_http http://localhost:8090/actuator/health "UI up"
check kafka-exporter probe_http http://localhost:9308/healthz "exporter up"
check postgres       probe_postgres
check redis          probe_redis
check cassandra      probe_cassandra
check elasticsearch  probe_elasticsearch
check rabbitmq       probe_rabbitmq
check localstack     probe_localstack
check prometheus     probe_prometheus
check grafana        probe_grafana

for opt in "kibana|http://localhost:5601/api/status" "n8n|http://localhost:5678/proxy-healthz"; do
  svc=${opt%%|*}; url=${opt#*|}
  if [ "$(container_health "$svc")" = "missing" ]; then
    record "$svc" "SKIP" "profile tools not started"
  else
    check "$svc" probe_http "$url" "up"
  fi
done

# One-shot init containers must have exited 0.
for init in init-kafka init-postgres init-cassandra init-elasticsearch; do
  code=$(docker inspect -f '{{.State.ExitCode}}' "vitalstream-$init" 2>/dev/null || echo missing)
  if [ "$code" = "0" ]; then record "$init" "OK" "exited 0"; else record "$init" "FAIL" "exit code: $code"; fi
done

printf '\n%-20s %-6s %s\n' SERVICE STATUS DETAIL
printf '%-20s %-6s %s\n' -------------------- ------ ------
for row in "${ROWS[@]}"; do
  IFS='|' read -r s st d <<<"$row"
  printf '%-20s %-6s %s\n' "$s" "$st" "$d"
done
echo
if [ "$FAIL" -eq 0 ]; then echo "ALL HEALTHY"; else echo "UNHEALTHY: see FAIL rows"; fi
exit "$FAIL"
