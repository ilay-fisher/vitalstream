#!/bin/bash
# Idempotently creates the topics from CONTRACTS section 2 (--if-not-exists).
set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:-kafka:29092}"
KT=/opt/kafka/bin/kafka-topics.sh
DAYS_7=604800000
DAYS_30=2592000000

create() { # name partitions retention_ms
  "$KT" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$1" --partitions "$2" --replication-factor 1 \
    --config "retention.ms=$3"
}

create vitals.raw          12 "$DAYS_7"
create alerts.detected      6 "$DAYS_30"
create alerts.detected.dlq  1 "$DAYS_30"

"$KT" --bootstrap-server "$BOOTSTRAP" --list
