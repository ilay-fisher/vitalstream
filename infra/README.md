# infra

Local infrastructure for VitalStream: one `compose.yaml`, idempotent init containers, Prometheus and
Grafana provisioning. Source of truth for ports, topics and schemas is [docs/CONTRACTS.md](../docs/CONTRACTS.md).

## What runs

| Service | Image | Host port | Notes |
|---|---|---|---|
| kafka (KRaft) | apache/kafka:3.8.1 | 9092 (in-network `kafka:29092`) | 1g limit, heap 512m |
| kafka-ui | provectuslabs/kafka-ui:v0.7.2 | 8090 | |
| kafka-exporter | danielqsj/kafka-exporter:v1.8.0 | 9308 | consumer-lag metrics for Prometheus |
| postgres (pgvector) | pgvector/pgvector:0.8.0-pg16 | 5433 | db/user/pass `vitalstream` |
| redis | redis:7.4.1-alpine | 6379 | |
| cassandra | cassandra:4.1.7 | 9042 | heap 1G, limit 2g |
| elasticsearch | elasticsearch:8.15.3 | 9200 | heap 512m, security off |
| rabbitmq | rabbitmq:3.13.7-management-alpine | 5672, 15672 | `vitalstream`/`vitalstream` |
| localstack | localstack/localstack:3.8.1 | 4566 | S3 + SQS |
| prometheus | prom/prometheus:v2.55.1 | 9090 | |
| grafana | grafana/grafana:11.3.1 | 3001 | `admin`/`admin` |
| kibana (profile `tools`) | kibana:8.15.3 | 5601 | |
| n8n (profile `tools`) | n8nio/n8n:1.64.3 | via proxy 5678 | behind Caddy basic auth (`caddy:2.8.4-alpine`) |

Network `vitalstream`; in-network hostnames equal the service names. Every service has a healthcheck,
a memory limit and (where stateful) a named volume.

## Use

```bash
make infra-up                 # start + init + wait until healthy
make infra-up PROFILES=tools  # also Kibana and n8n
make health                   # table of every service, non-zero exit on failure
make infra-down               # stop, keep data
make clean                    # stop and delete volumes
```

Paths inside `compose.yaml` are relative to the repo root, so run raw compose commands with
`docker compose --project-directory . -f infra/compose.yaml ...` (the Makefile does).
Component fragments (`services/*/compose.yaml`, `tools/*/compose.yaml`, `web/*/compose.yaml`) are
added automatically by `make up`; `make fragments` shows what was found.

## How init works

One-shot `init-*` containers start after their store is healthy and exit 0. All are idempotent, so
`make infra-up` can be repeated against existing volumes:

| Init | Does |
|---|---|
| `init-kafka` | `kafka-topics --create --if-not-exists` for `vitals.raw` (12p, 7d), `alerts.detected` (6p, 30d), `alerts.detected.dlq` (1p, 30d) |
| `init-postgres` | `CREATE EXTENSION IF NOT EXISTS vector`, schemas `clinical`/`copilot`, `clinical.patients` + 20 seeds (`ON CONFLICT DO NOTHING`) |
| `init-cassandra` | keyspace `vitalstream`, tables `vitals_raw`, `vitals_by_minute` (`IF NOT EXISTS`) |
| `init-elasticsearch` | creates indices `alerts`, `guidelines` if absent (mappings in `elasticsearch/*.json`) |
| rabbitmq | topology loaded at boot from `rabbitmq/definitions.json` (exchange `alerts.notify`, queues, DLX) |
| localstack | `localstack/init.sh` runs as a ready hook: bucket `vitalstream-archive`, SQS `alert-escalations` + DLQ (maxReceiveCount 5) |

Changing an existing ES mapping needs the index dropped first (`curl -X DELETE localhost:9200/alerts`, then `make infra-up`).

## Observability

Prometheus scrapes `ingest-gateway:8081` and `clinical-api:8082` (`/actuator/prometheus`), `copilot:8083`
(`/metrics`), `stream-processor:4040` (`/metrics/prometheus`) and `kafka-exporter:9308`. Targets of services
that are not running show DOWN. Grafana is provisioned with the Prometheus datasource and the
"VitalStream Overview" dashboard. The panels assume these metric names (services should expose them):
`vitalstream_events_ingested_total`, `vitalstream_events_rejected_total{reason}`,
`vitalstream_alerts_raised_total{severity}`, `kafka_consumergroup_lag` (from the exporter) and Spring's
`http_server_requests_seconds_bucket`.

## Troubleshooting

- **Containers exit 137 / restart loops (OOM):** Colima's VM is too small. Run all infra with at least
  8 GiB: `colima stop && colima start --cpu 6 --memory 12`. Total limits in this file sum to roughly 9 GiB
  with `tools` off.
- **Elasticsearch `max virtual memory` error:** `colima ssh -- sudo sysctl -w vm.max_map_count=262144`.
- **Cassandra slow to become healthy:** first boot takes 60-90 s; `infra-up` waits up to 7 minutes.
- **Port already in use:** another stack is running; `docker ps` and stop it.
- **`init-*` failed:** `docker logs vitalstream-init-<name>`; fix and re-run `make infra-up` (idempotent).
- **Image pull TLS timeout:** retry; the registry connection is flaky on some networks.

## Security notes

- Every published port is bound to `127.0.0.1`; nothing is reachable from other hosts. In-network access is unchanged.
- Credentials are local-dev defaults from CONTRACTS (`vitalstream`/`vitalstream`, Grafana `admin`/`admin`). Override with
  `POSTGRES_PASSWORD`, `RABBITMQ_PASSWORD`, `GRAFANA_ADMIN_PASSWORD`, `N8N_PROXY_USER`, `N8N_PROXY_PASSWORD`
  in your shell or a git-ignored `.env`.
  Note: Postgres/RabbitMQ passwords only take effect on a fresh volume, and components expect the CONTRACTS defaults.
- n8n (profile `tools`) is not published. Only the Caddy `n8n-proxy` listens on 127.0.0.1:5678, with basic auth
  (`N8N_PROXY_USER`, default `admin`; `N8N_PROXY_PASSWORD`, empty by default). The proxy exits non-zero at start if the
  password is empty, so use e.g. `N8N_PROXY_PASSWORD=... make infra-up PROFILES=tools`.
  n8n 1.x creates its owner account in the UI on first visit; do that behind the proxy. Diagnostics are off and the
  `executeCommand`, `ssh` and `code` nodes are excluded.
- Elasticsearch has security disabled and Redis has no auth. Never expose this compose stack to a network or run it on a shared host.
