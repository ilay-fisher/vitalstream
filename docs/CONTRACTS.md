# VitalStream contracts (source of truth)

Owner: manager. Any change goes through the manager. Workers code against this document.
Machine-readable copies live in `contracts/` (JSON Schema + sample payloads), created by the foundation workstream.

## 1. Ports (host)

| Component | Port | Notes |
|---|---|---|
| ingest-gateway | 8081 | |
| clinical-api | 8082 | |
| copilot | 8083 | REST + MCP (streamable HTTP at `/mcp`) |
| dashboard | 3000 | nginx in container; Vite dev 5173 |
| Kafka | 9092 | in-network `kafka:29092` |
| Kafka UI | 8090 | |
| PostgreSQL (pgvector) | 5433 | in-network `postgres:5432`, db `vitalstream`, user/pass `vitalstream`/`vitalstream` |
| Redis | 6379 | |
| Cassandra | 9042 | keyspace `vitalstream`, DC `datacenter1` |
| Elasticsearch | 9200 | security disabled locally |
| Kibana | 5601 | compose profile `tools` |
| RabbitMQ | 5672 / 15672 | user/pass `vitalstream`/`vitalstream` |
| LocalStack (S3, SQS) | 4566 | region `us-east-1` |
| Spark UI | 4040 | |
| Prometheus | 9090 | |
| Grafana | 3001 | admin/admin |
| n8n | 5678 | compose profile `tools` |

Docker network name: `vitalstream`. In-network hostnames = compose service names above (`kafka`, `postgres`, `redis`, `cassandra`, `elasticsearch`, `rabbitmq`, `localstack`, `ingest-gateway`, `clinical-api`, `copilot`, `dashboard`).

## 2. Kafka topics

| Topic | Partitions | Key | Value | Producer | Consumer(s) |
|---|---|---|---|---|---|
| `vitals.raw` | 12 | `patientId` | `VitalSignEvent` | ingest-gateway | stream-processor |
| `alerts.detected` | 6 | `patientId` | `AlertEvent` | stream-processor | clinical-api |
| `alerts.detected.dlq` | 1 | `patientId` | original bytes + headers `error`, `originalTopic` | clinical-api | ops |

Serialization: JSON, UTF-8. Headers on every record: `correlationId`, `schemaVersion` (`"1"`), `source` (service name).
Retention: `vitals.raw` 7 days, `alerts.*` 30 days. Replication factor 1 locally.

## 3. Event schemas

### 3.1 VitalSignEvent (`contracts/schemas/vital-sign-event.schema.json`)

```json
{
  "eventId": "6f1c2a1e-8d0b-4c11-9a77-3f0c2b9e1d10",
  "patientId": "P-0001",
  "deviceId": "DEV-ICU-0001",
  "metric": "HEART_RATE",
  "value": 118.0,
  "unit": "/min",
  "loinc": "8867-4",
  "observedAt": "2026-11-11T20:15:30.123Z",
  "ingestedAt": "2026-11-11T20:15:30.456Z",
  "source": "DEVICE_BATCH"
}
```

| Field | Type | Rules |
|---|---|---|
| eventId | UUID string | required; idempotency key |
| patientId | string | `^P-\d{4,}$` |
| deviceId | string | `^DEV-[A-Z]+-\d{4,}$` |
| metric | enum | see table below |
| value | number | within physiological range below, else reject |
| unit | string | UCUM, must match metric |
| loinc | string | must match metric |
| observedAt | ISO-8601 UTC | not more than 5 min in the future |
| ingestedAt | ISO-8601 UTC | set by gateway |
| source | enum | `FHIR`, `HL7V2`, `DEVICE_BATCH` |

| metric | unit | LOINC | valid range | normal range |
|---|---|---|---|---|
| HEART_RATE | `/min` | 8867-4 | 20–250 | 51–90 |
| SPO2 | `%` | 59408-5 | 50–100 | 96–100 |
| RESP_RATE | `/min` | 9279-1 | 4–60 | 12–20 |
| SYSTOLIC_BP | `mm[Hg]` | 8480-6 | 50–260 | 111–219 |
| DIASTOLIC_BP | `mm[Hg]` | 8462-4 | 20–160 | 60–90 |
| TEMPERATURE | `Cel` | 8310-5 | 30–43 | 36.1–38.0 |

### 3.2 AlertEvent (`contracts/schemas/alert-event.schema.json`)

```json
{
  "alertId": "0b7f9c3e-2a41-4f7e-9d0d-5c1a2b3c4d5e",
  "patientId": "P-0001",
  "type": "NEWS2",
  "severity": "HIGH",
  "metric": null,
  "value": null,
  "score": 7.0,
  "message": "NEWS2 score 7 (RR 25, SpO2 91, HR 131)",
  "windowStart": "2026-11-11T20:15:00Z",
  "windowEnd": "2026-11-11T20:16:00Z",
  "detectedAt": "2026-11-11T20:16:02.010Z"
}
```

| Field | Type | Rules |
|---|---|---|
| alertId | UUID | deterministic: UUIDv5 of `patientId|type|metric|windowStart` (lets consumers dedupe replays) |
| type | enum | `THRESHOLD`, `TREND_ZSCORE`, `NEWS2`, `ML_ANOMALY` |
| severity | enum | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` |
| metric / value | nullable | set for THRESHOLD and TREND_ZSCORE |
| score | nullable number | NEWS2 total, z-score, or anomaly score |

### 3.3 Detection rules (stream-processor)

- **THRESHOLD**: 1-min window avg outside normal range → MEDIUM; outside `valid range` midpoint-to-edge critical bands (HR <40 or >130, SpO2 <90, RR <8 or >25, SBP <90, Temp <35 or >39.1) → CRITICAL.
- **TREND_ZSCORE**: per patient+metric EWMA mean/variance (alpha 0.1, state kept across micro-batches); |z| ≥ 3 → HIGH.
- **NEWS2** (Royal College of Physicians, room-air scale 1, consciousness assumed Alert): computed per patient per 1-min window from latest RR, SpO2, SBP, HR, Temp. Total 5–6 → HIGH, ≥7 → CRITICAL, any single parameter scoring 3 → MEDIUM.
- **ML_ANOMALY**: Spark ML KMeans (k chosen by silhouette) trained offline on normal simulated windows; feature vector = standardized per-window means of the 6 metrics; distance to nearest centroid above the 99th percentile of training distances → MEDIUM.
- Windows: tumbling 1 min on `observedAt`, watermark 30 s.

## 4. REST APIs

All JSON. Errors use RFC 9457 `application/problem+json`. All endpoints accept/return `X-Correlation-Id`. OpenAPI served at `/v3/api-docs` + Swagger UI (Java) or `/docs` (FastAPI).

### ingest-gateway (8081)
| Method | Path | Body | Result |
|---|---|---|---|
| POST | `/api/v1/vitals/batch` | `{"deviceId","patientId","readings":[{"eventId","metric","value","observedAt"}]}` max 500 readings | 202 `{"accepted":n,"duplicates":n,"rejected":[{"eventId","reason"}]}` |
| POST | `/api/v1/fhir/Observation` | FHIR R4 Observation (single) or Bundle (`type: batch`) | 202 same shape |
| POST | `/api/v1/hl7` | `text/plain` HL7 v2.5 ORU^R01 | 202 + HL7 ACK (AA/AE) in body |

Gateway behaviour: validate → idempotency check in Redis → publish to Kafka (`acks=all`, idempotent producer) → 202. Rate limit per device: token bucket in Redis, 50 req/s burst 100 → 429 with `Retry-After`.

### clinical-api (8082)
| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/patients` | list with latest vitals (from Redis) + open alert count |
| GET | `/api/v1/patients/{id}` | patient + latest vitals |
| GET | `/api/v1/patients/{id}/vitals?metric=&from=&to=&resolution=raw\|minute` | from Cassandra; cache-aside in Redis (TTL 10 s) |
| GET | `/api/v1/alerts?patientId=&severity=&status=&q=&page=&size=` | `q` → Elasticsearch full text, otherwise Postgres |
| GET | `/api/v1/alerts/{id}` | |
| POST | `/api/v1/alerts/{id}/ack` | `{"by":"dr.cohen","note":"..."}` → status ACKNOWLEDGED |
| GET | `/api/v1/alerts/stream` | Server-Sent Events, event `alert` with AlertView JSON |

`AlertView` = AlertEvent fields + `status` (`OPEN`, `ACKNOWLEDGED`, `RESOLVED`), `acknowledgedBy`, `acknowledgedAt`.

### copilot (8083)
| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/chat` | `{"sessionId","patientId"?,"message"}` → SSE stream: events `token`, `tool_call`, `citation`, `done` |
| POST | `/api/v1/documents` | ingest markdown guideline(s) into RAG store |
| GET | `/api/v1/documents/search?q=&k=` | hybrid search debug endpoint |
| — | (as built) | SSE order: `session` `{sessionId}` → `tool_call` twice per call (`status: running`, then `ok`/`error`) → `citation` `{title, chunkId, provenance}` → `token`… → `done` `{sessionId, correlationId, toolCalls, usage, error?}` (failure = `done` with `error:true`, no separate error event). `POST /api/v1/documents` body `{"documents":[{"title"?,"source"?,"content"}]}`. `ALLOWED_HOSTS` matches host names only (Starlette ignores ports). |
| — | `/mcp` | MCP server (streamable HTTP). Tools: `get_patient_vitals(patient_id, minutes)`, `list_alerts(patient_id?, severity?)`, `search_guidelines(query, k)`, `compute_news2(rr, spo2, sbp, hr, temp)` |

### 4.1 Authentication (local-dev model; production = OIDC/JWT at API gateway, see known gaps)

| Surface | Requirement | Env var (local default) |
|---|---|---|
| ingest-gateway all `/api/v1/**` | header `X-Api-Key` | `INGEST_API_KEY` (`dev-ingest-key`) |
| copilot `POST /api/v1/documents` | `Authorization: Bearer` | `COPILOT_ADMIN_TOKEN` (`dev-admin-token`) |
| copilot `/mcp` | `Authorization: Bearer`; DNS-rebinding protection ON, allowed hosts `localhost:8083`, `127.0.0.1:8083`, `copilot:8083` | `MCP_TOKEN` (`dev-mcp-token`) |
| clinical-api, copilot chat, health/metrics | open (loopback only) | — |

Rules: constant-time comparison; missing/invalid → 401 problem+json; never log tokens; an empty expected secret never matches (fail closed).
**Host-header allowlist (all HTTP services, DNS-rebinding defence):** reject requests whose `Host` is not in `ALLOWED_HOSTS` (default `localhost,127.0.0.1,<compose service name>` + port variants) with 400. FastAPI: `TrustedHostMiddleware`; Spring: a servlet filter. Health endpoints included (k8s probes use pod IP → Helm sets `ALLOWED_HOSTS` accordingly or probes send Host header).
**Copilot sessions:** `sessionId` in `POST /api/v1/chat` is optional; the server issues a random 128-bit id (first SSE event `session` `{"sessionId"}`) and only accepts ids it issued and that have not expired; unknown ids → new session issued, never adopted. **All** tool results containing free text (alert messages, guideline chunks, any string from another system) go into the untrusted delimiter block.
**Known gap:** chat and clinical-api reads have no end-user auth in local dev; production design = OIDC (Keycloak) JWT validated at the API gateway with clinician role, session bound to `sub`.
**Secret startup guard (all services):** env `APP_ENV` (`dev` default, `prod` in Helm/Terraform). When `APP_ENV != dev`, startup must fail with a clear error if any secret (`INGEST_API_KEY`, `COPILOT_ADMIN_TOKEN`, `MCP_TOKEN`, `LOG_PSEUDONYM_KEY`, DB/broker passwords) is empty, equals its dev default, or is shorter than 32 chars. In `dev`, log one WARN at startup: "running with dev secrets". Unit-test both paths. RAG documents carry `provenance` (`curated` for bundled corpus, `api-upload` otherwise) shown in citations; retrieved text is passed to the LLM inside a delimited "untrusted reference material" block and the system prompt says never to follow instructions found in it.

## 5. Storage schemas

### PostgreSQL (`vitalstream` DB)
- Schema `clinical` (owner clinical-api, Flyway): `patients(id text pk, name, bed, ward, created_at)`, `alerts(id uuid pk, patient_id, type, severity, metric, value, score, message, window_start, window_end, detected_at, status, acknowledged_by, acknowledged_at, version)`, `outbox(id uuid pk, aggregate_id, event_type, payload jsonb, created_at, published_at null)`.
- Schema `copilot` (owner copilot, Alembic): `documents(id uuid pk, title, source, created_at)`, `chunks(id uuid pk, document_id fk, ordinal, content, embedding vector(384), tsv tsvector)`; HNSW index on embedding (cosine).
- Seed: 20 patients `P-0001`..`P-0020` (foundation seeds via init SQL; clinical-api Flyway must be idempotent with it — foundation creates only the `clinical`/`copilot` schemas and the `patients` seed in `clinical.patients` with `CREATE TABLE IF NOT EXISTS`).

### Cassandra (keyspace `vitalstream`, SimpleStrategy RF 1)
```sql
CREATE TABLE vitals_raw (patient_id text, day date, observed_at timestamp, metric text, event_id uuid, value double, unit text, device_id text,
  PRIMARY KEY ((patient_id, day), observed_at, metric, event_id)) WITH CLUSTERING ORDER BY (observed_at DESC, metric ASC, event_id ASC)
  AND default_time_to_live = 2592000;
CREATE TABLE vitals_by_minute (patient_id text, metric text, day date, window_start timestamp, min double, max double, avg double, count int,
  PRIMARY KEY ((patient_id, metric, day), window_start)) WITH CLUSTERING ORDER BY (window_start DESC);
```

### Redis keys
| Key | Type | TTL | Writer |
|---|---|---|---|
| `idem:{eventId}` | string | 24 h | ingest-gateway |
| `rl:{deviceId}` | hash (token bucket, Lua script) | 10 s | ingest-gateway |
| `patient:{patientId}:latest` | hash metric → JSON `{value,unit,observedAt}` | 1 h | stream-processor |
| `ewma:{patientId}:{metric}` | hash `mean,var,n` | 24 h | stream-processor |
| `alert:suppress:{patientId}:{type}:{metric}` | string | 5 min | clinical-api |
| `cache:vitals:{patientId}:{hash(query)}` | string JSON | 10 s | clinical-api |

### Elasticsearch
- `alerts` index (clinical-api): mirrors AlertView; `message` text (english analyzer), keywords for ids/enums, dates.
- `guidelines` index (copilot): `chunk_id`, `document_id`, `title`, `content` (text, english), for BM25 side of hybrid search.

### RabbitMQ
- Topic exchange `alerts.notify`; routing key `alert.<severity lowercase>`.
- Queues: `notify.critical` (binding `alert.critical`), `notify.all` (binding `alert.*`). Durable, with DLX `alerts.notify.dlx`.

### AWS (LocalStack locally)
- S3 bucket `vitalstream-archive`: stream-processor writes raw vitals as Parquet partitioned `dt=YYYY-MM-DD/hour=HH/`.
- SQS queue `alert-escalations` (+ `alert-escalations-dlq`, maxReceiveCount 5): clinical-api sends CRITICAL alerts.

## 6. Environment variables (shared names)

| Var | Default (host) | Used by |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | gateway, stream, clinical-api |
| `REDIS_URL` | `redis://localhost:6379` | all |
| `POSTGRES_URL` | `jdbc:postgresql://localhost:5433/vitalstream` (Java) / `postgresql://vitalstream:vitalstream@localhost:5433/vitalstream` (Python) | clinical-api, copilot |
| `CASSANDRA_CONTACT_POINTS` | `localhost:9042` | stream, clinical-api |
| `ELASTICSEARCH_URL` | `http://localhost:9200` | clinical-api, copilot |
| `RABBITMQ_URL` | `amqp://vitalstream:vitalstream@localhost:5672` | clinical-api |
| `AWS_ENDPOINT_URL` | `http://localhost:4566` | stream, clinical-api |
| `AWS_REGION` | `us-east-1` | stream, clinical-api |
| `CLINICAL_API_URL` | `http://localhost:8082` | copilot, dashboard |
| `INGEST_GATEWAY_URL` | `http://localhost:8081` | simulator |
| `COPILOT_URL` | `http://localhost:8083` | dashboard |
| `LLM_PROVIDER` | `fake` | copilot (`fake` \| `anthropic`) |
| `ANTHROPIC_API_KEY` | empty | copilot |
| `LLM_MODEL` | `claude-sonnet-5-5` | copilot |
| `LOG_LEVEL` | `INFO` | all |

## 7. Correlation & logging fields

JSON log fields: `timestamp`, `level`, `service`, `correlationId`, `message`, plus context keys (`patientRef`, `eventId`, `alertId`, `topic`, `partition`, `offset`, `durationMs`). Never log vital values at INFO in bulk.

**PHI minimisation:** never log raw `patientId` (it is a medical record identifier). Log `patientRef` = first 12 hex chars of HMAC-SHA256(patientId, `LOG_PSEUDONYM_KEY`). Env `LOG_PSEUDONYM_KEY` default `dev-only-pseudonym-key` (must be overridden outside local dev). Same algorithm in every service so refs correlate across logs. Test vector: HMAC-SHA256("P-0001", "dev-only-pseudonym-key") → assert your helper matches the other languages (compute and pin it in a unit test).

**Event-time guard (stream-processor):** before watermarking or any state update, drop events whose `observedAt` is more than 5 min ahead of processing time or older than 24 h; count them (`vitalstream_events_dropped_total{reason}`) and log at WARN with `eventId` only. Prevents watermark poisoning that would suppress alerts.

**Prometheus metric names (Grafana "VitalStream Overview" depends on these):**

| Metric | Type | Labels | Emitted by |
|---|---|---|---|
| `vitalstream_events_ingested_total` | counter | `source` (batch\|fhir\|hl7) | ingest-gateway |
| `vitalstream_events_rejected_total` | counter | `reason` | ingest-gateway |
| `vitalstream_events_dropped_total` | counter | `reason` (future\|stale) | stream-processor |
| `vitalstream_alerts_raised_total` | counter | `severity`, `type` | stream-processor (detected), clinical-api (persisted) – add label `service` via scrape config |
| `http_server_requests_seconds_bucket` | histogram | Spring default | Java services |
| `kafka_consumergroup_lag` | gauge | kafka-exporter | foundation |

Micrometer names `vitalstream.events.ingested` etc. export as the names above. The event-time guard counter above is `vitalstream_events_dropped_total`.

**Schema validation notes:** `contracts/schemas/device-batch.schema.json` describes the `POST /api/v1/vitals/batch` body. Rules JSON Schema cannot express (observedAt ≤ now + 5 min, ≥ now − 24 h) are enforced in code by the gateway (reject) and stream-processor (drop).

## 8. Compose integration

- `infra/compose.yaml` (foundation) defines all infrastructure on network `vitalstream`.
- Each component ships `<component-path>/compose.yaml` defining only its own service(s), using in-network hostnames, `depends_on` with `condition: service_healthy` on infra services, and the `vitalstream` network declared `external: false` with the same name.
- Root `Makefile` (foundation) auto-discovers fragments: `docker compose --project-directory . -f infra/compose.yaml $(foreach f,$(wildcard services/*/compose.yaml tools/*/compose.yaml web/*/compose.yaml),-f $f)`.
- Make targets: `infra-up`, `infra-down`, `up`, `down`, `ps`, `logs`, `health`, `simulate`, `test`, `clean`.
- **Security:** every published host port binds to loopback (`"127.0.0.1:8081:8081"`), never `0.0.0.0`. Credentials come from env with local-dev defaults (`${VAR:-default}`). Ad-hoc test containers follow the same rule.
