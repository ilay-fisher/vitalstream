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
| Spark UI | 4040 | Spark metrics at `/metrics/prometheus/` |
| stream-processor metrics/health | 9404 | business counters `/metrics`, `/health` (Prometheus scrapes both 4040 and 9404) |
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
| observedAt | ISO-8601 UTC | gateway rejects > 5 min in the future (coarse device-clock check); stream-processor drops > 30 s ahead (see §7) |
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
| alertId | UUID | **detection id** (one per patient, type, metric and window; clinical-api groups detections into episodes, §3.4, whose id is a different UUID). Deterministic: `uuid5(NAMESPACE_URL, "patientId|type|metric|windowStart")` (RFC 4122 URL namespace `6ba7b811-9dad-11d1-80b4-00c04fd430c8`; metric = empty string when null; windowStart formatted `yyyy-MM-ddTHH:mm:ssZ` UTC, no millis). Lets consumers dedupe replays and recompute ids |
| type | enum | `THRESHOLD`, `TREND_ZSCORE`, `NEWS2`, `ML_ANOMALY` |
| severity | enum | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` |
| metric / value | nullable | set for THRESHOLD and TREND_ZSCORE |
| score | nullable number | NEWS2 total, z-score, or anomaly score |

### 3.3 Detection rules (stream-processor)

- **THRESHOLD**: 1-min window avg outside normal range → MEDIUM; outside `valid range` midpoint-to-edge critical bands (HR <40 or >130, SpO2 <90, RR <8 or >25, SBP <90, Temp <35 or >39.1) → CRITICAL.
- **TREND_ZSCORE** (v2, alarm-fatigue fix): scored on the **closed 1-min window mean** of a metric, never on raw samples (raw 1 Hz scoring ran ~60 tests per metric per minute and alerted stable patients by chance). Per patient+metric EWMA over window means: alpha 0.2, warm-up 5 windows (no hits before), std floor as today. A window is a *breach* when |z| ≥ 3 **and** |window mean − EWMA mean| ≥ the metric's minimum clinical delta: HEART_RATE 10 /min, RESP_RATE 4 /min, SPO2 3 %, SYSTOLIC_BP 15 mm[Hg], DIASTOLIC_BP 10 mm[Hg], TEMPERATURE 0.5 Cel. An alert is emitted for a window only when at least 3 of the last 5 windows (this one included) are breaches **in the same direction** (persistence). The window is scored against the state before it, then folded into the state. Severity **MEDIUM** (a trend inside the normal range is a heads-up; NEWS2 and THRESHOLD carry escalation). `score` = z of this window, `value` = window mean, message names direction and delta, e.g. `HEART_RATE rising: 104.2 vs trend 88.9 (+15.3, z=4.1, 3 of 5 min)`.
- **NEWS2** (Royal College of Physicians, room-air scale 1, consciousness assumed Alert): computed per patient per 1-min window from latest RR, SpO2, SBP, HR, Temp. Total 5–6 → HIGH, ≥7 → CRITICAL, any single parameter scoring 3 → MEDIUM.
- **ML_ANOMALY**: Spark ML KMeans (k chosen by silhouette) trained offline on normal simulated windows; feature vector = standardized per-window means of the 6 metrics; distance to nearest centroid above the 99th percentile of training distances → MEDIUM.
- Windows: tumbling 1 min on `observedAt`, watermark 30 s.

### 3.4 Alert episodes (clinical-api)

Clinicians see **episodes**, not per-window detections. An episode is one ongoing condition: key `(patientId, type, metric)` (metric `""` when null). At most one non-RESOLVED episode per key (Postgres partial unique index).

| Rule | Behaviour |
|---|---|
| Open | Detection with no non-RESOLVED episode for its key → new episode, `status OPEN`. Episode id = `uuid5(NAMESPACE_URL, "episode|patientId|type|metric|windowStart")` with the first detection's windowStart (same formatting as §3.2), so replays re-derive it. |
| Update | Detection for a key with an OPEN or ACKNOWLEDGED episode → same row: `occurrences + 1`, `lastSeenAt = windowEnd`, `value`/`score`/`message`/`windowStart`/`windowEnd`/`detectedAt`/`lastDetectionId` = this detection, `version + 1`. No new row. |
| Idempotency | Detection whose windowStart < the episode's latest windowStart → ignored (late or replayed). Same windowStart (Spark update mode re-emits) → no occurrence increment; only a severity raise applies. |
| Severity | Episode `severity` = highest seen while open, never lowered. A raise is an **escalation**: if the episode was ACKNOWLEDGED it goes back to OPEN (acknowledgement fields kept for audit). |
| Resolve | Scheduled job (every 30 s): OPEN/ACKNOWLEDGED episodes with `lastSeenAt` older than `ALERT_RESOLVE_AFTER` (default `PT5M`) → `RESOLVED`, `resolvedAt = now`. A later detection for the key opens a new episode. Clinicians never resolve manually. |
| Ack | `POST /alerts/{id}/ack` on OPEN → ACKNOWLEDGED. Occurrences keep counting while acknowledged. Ack of ACKNOWLEDGED or RESOLVED → 409. A version clash caused by a concurrent ingest update is retried server-side (up to 3 times); 409 only for the two states above. |
| Hierarchy | NEWS2 is the parent. A THRESHOLD, TREND_ZSCORE or ML_ANOMALY episode of a patient with a non-RESOLVED NEWS2 episode gets `parentAlertId` = that NEWS2 episode **if its severity ≤ the parent's**; a child that outranks the parent stays top-level. When a NEWS2 episode opens, the patient's open qualifying episodes are linked to it; when it resolves, still-open children are unlinked (become top-level). Escalation is re-checked on each update. |
| Notify | RabbitMQ `alerts.notify` and SQS (CRITICAL) get a message only when a **top-level** episode opens or escalates (`AlertView` body, routing key by the episode's current severity). Updates, children, acks and resolutions do not notify. The existing `alert:suppress` window still applies on top. |
| Live feed | SSE emits `event: alert` with the full AlertView on every episode change (open, update, escalation, ack, resolve, link/unlink), `id: <alertId>:<version>`; clients upsert by `alertId`. |
| Legacy rows | Flyway migration: per key keep the newest non-RESOLVED row open (occurrences = number of rows for the key since its first open row), resolve the rest with `resolvedAt = migration time`; `firstSeenAt = window_start`, `lastSeenAt = window_end` for every row. |

## 4. REST APIs

All JSON. Errors use RFC 9457 `application/problem+json`. All endpoints accept/return `X-Correlation-Id`. OpenAPI served at `/v3/api-docs` + Swagger UI (Java) or `/docs` (FastAPI).

### ingest-gateway (8081)
| Method | Path | Body | Result |
|---|---|---|---|
| POST | `/api/v1/vitals/batch` | `{"deviceId","patientId","readings":[{"eventId","metric","value","observedAt"}]}` max 500 readings | 202 `{"accepted":n,"duplicates":n,"rejected":[{"eventId","reason"}]}` |
| POST | `/api/v1/fhir/Observation` | FHIR R4 Observation (single) or Bundle (`type: batch`) | 202 same shape |
| POST | `/api/v1/hl7` | `text/plain` HL7 v2.5 ORU^R01 | 202 + HL7 ACK (AA/AE) in body |

HL7 mapping: PID-3 → patientId, OBX-3 LOINC → metric, OBX-5 → value, OBX-14 → observedAt, OBX-18 (equipment instance identifier) → deviceId.

Gateway behaviour: validate → idempotency check in Redis → publish to Kafka (`acks=all`, idempotent producer) → 202. Rate limit per device: token bucket in Redis, 50 req/s burst 100 → 429 with `Retry-After`.

### clinical-api (8082)
| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/patients` | list with latest vitals (from Redis) + open alert count (`openAlerts` = top-level OPEN/ACKNOWLEDGED episodes) |
| GET | `/api/v1/patients/{id}` | patient + latest vitals |
| GET | `/api/v1/patients/{id}/vitals?metric=&from=&to=&resolution=raw\|minute` | from Cassandra; cache-aside in Redis (TTL 10 s) |
| GET | `/api/v1/alerts?patientId=&severity=&status=&type=&topLevel=&parentAlertId=&q=&page=&size=` | episodes (§3.4). `status` repeatable (`status=OPEN&status=ACKNOWLEDGED`). `topLevel=true` → only episodes without `parentAlertId`; `parentAlertId=<id>` → that episode's children. Sorted by `lastSeenAt` desc. `q` → Elasticsearch full text, otherwise Postgres |
| GET | `/api/v1/alerts/{id}` | |
| POST | `/api/v1/alerts/{id}/ack` | `{"note":"..."}` → status ACKNOWLEDGED; actor from the JWT (§4.2), role CLINICIAN |
| GET | `/api/v1/alerts/stream` | Server-Sent Events, event `alert` with AlertView JSON |
| GET | `/api/v1/me` | `{sub, name, roles}` of the caller's token (any role); used by dashboard and copilot (§4.2) |

`AlertView` = one **episode** (§3.4): latest-detection fields (AlertEvent shape, but `alertId` = episode id) + `status` (`OPEN`, `ACKNOWLEDGED`, `RESOLVED`), `acknowledgedBy`, `acknowledgedAt`, episode fields below.

As built (clinical-api response shapes):

| Endpoint | Response |
|---|---|
| `GET /patients` | bare array of `{id, name, bed, ward, latestVitals:{METRIC:{value, unit, observedAt}}, openAlerts}` |
| `GET /patients/{id}/vitals` | `{patientId, resolution:"RAW"\|"MINUTE", from, to, points:[…]}`. RAW point `{observedAt, metric, value, unit, deviceId}`; MINUTE point `{observedAt (window start), metric, min, max, avg, count}`. `resolution` query param is case-insensitive. |
| `GET /alerts` | page `{items:[AlertView], page, size, totalElements, totalPages}`; `q` searches Elasticsearch (there is no `/alerts/search`) |
| `AlertView` | `{alertId, patientId, type, severity, metric, value, score, message, windowStart, windowEnd, detectedAt, status, acknowledgedBy, acknowledgedAt, acknowledgementNote, version, firstSeenAt, lastSeenAt, occurrences, resolvedAt, parentAlertId, childCount, lastDetectionId}`. `alertId` = episode id; `severity` = episode peak; `value`/`score`/`message`/`window*`/`detectedAt` = latest detection; `childCount` = non-RESOLVED children; `resolvedAt`/`parentAlertId` nullable. Fields are additive: old clients keep working. |
| `POST /alerts/{id}/ack` | 200 AlertView; already acknowledged / stale version → 409. The client sends no version: the server reads the current version and does a compare-and-set update (optimistic locking). |
| `GET /alerts/stream` | SSE `event: alert`, `id: <alertId>:<version>`, data AlertView, on every episode change (§3.4); clients upsert by `alertId`. No replay on reconnect. |
| Errors | `application/problem+json`; 400 validation, 404 unknown id, 409 conflict, 503 dependency down |

### copilot (8083)
| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/chat` | `{"sessionId","patientId"?,"message"}` → SSE stream: events `token`, `tool_call`, `citation`, `done`. Requires the user's clinical-api JWT (§4.2) |
| POST | `/api/v1/documents` | ingest markdown guideline(s) into RAG store |
| GET | `/api/v1/documents/search?q=&k=` | hybrid search debug endpoint |
| — | (as built) | SSE order: `session` `{sessionId}` → `tool_call` twice per call (`status: running`, then `ok`/`error`) → `citation` `{title, chunkId, provenance}` → `token`… → `done` `{sessionId, correlationId, toolCalls, usage, error?}` (failure = `done` with `error:true`, no separate error event). `POST /api/v1/documents` body `{"documents":[{"title"?,"source"?,"content"}]}`. `ALLOWED_HOSTS` matches host names only (Starlette ignores ports). `done.usage` = `{inputTokens, outputTokens}` (fake LLM reports 0 output tokens). |
| — | `/mcp` | MCP server (streamable HTTP). Tools: `get_patient_vitals(patient_id, minutes)`, `list_alerts(patient_id?, severity?)`, `search_guidelines(query, k)`, `compute_news2(rr, spo2, sbp, hr, temp)` |

### 4.1 Authentication (local-dev model; production = OIDC/JWT at API gateway, see known gaps)

| Surface | Requirement | Env var (local default) |
|---|---|---|
| ingest-gateway all `/api/v1/**` | header `X-Api-Key` | `INGEST_API_KEY` (`dev-ingest-key`) |
| copilot `POST /api/v1/documents` | `Authorization: Bearer` | `COPILOT_ADMIN_TOKEN` (`dev-admin-token`) |
| copilot `/mcp` | `Authorization: Bearer`; DNS-rebinding protection ON, allowed hosts `localhost:8083`, `127.0.0.1:8083`, `copilot:8083` | `MCP_TOKEN` (`dev-mcp-token`) |
| clinical-api all `/api/v1/**` | `Authorization: Bearer <JWT>`, see §4.2 | `CLINICAL_JWT_SECRET` (`dev-clinical-jwt-secret-0123456789abcdef`) or `CLINICAL_JWT_JWK_SET_URI` |
| copilot `POST /api/v1/chat` | `Authorization: Bearer <clinical-api JWT>` (the end user's), see §4.2 | — |
| health/metrics | open (loopback only) | — |

Rules: constant-time comparison; missing/invalid → 401 problem+json; never log tokens; an empty expected secret never matches (fail closed).
**Host-header allowlist (all HTTP services, DNS-rebinding defence):** reject requests whose `Host` is not in `ALLOWED_HOSTS` (default `localhost,127.0.0.1,<compose service name>` + port variants) with 400. FastAPI: `TrustedHostMiddleware`; Spring: a servlet filter. Health endpoints included (k8s probes use pod IP → Helm sets `ALLOWED_HOSTS` accordingly or probes send Host header).
**Copilot sessions:** `sessionId` in `POST /api/v1/chat` is optional; the server issues a random 128-bit id (first SSE event `session` `{"sessionId"}`) and only accepts ids it issued and that have not expired; unknown ids → new session issued, never adopted. **All** tool results containing free text (alert messages, guideline chunks, any string from another system) go into the untrusted delimiter block.
**Known gap:** no per-patient or per-ward scoping yet: any authenticated role can read every patient. The dev token endpoint is the only login locally; production = OIDC provider (Keycloak / Cognito) via `CLINICAL_JWT_JWK_SET_URI`.

### 4.2 clinical-api authentication and authorization

clinical-api is an OAuth2 resource server (Spring Security). Every `/api/v1/**` request needs `Authorization: Bearer <JWT>`.

| Item | Rule |
|---|---|
| Validation mode | `CLINICAL_JWT_JWK_SET_URI` set → RS256 via JWKS (OIDC provider). Otherwise HS256 with `CLINICAL_JWT_SECRET` (≥ 32 bytes). |
| Required claims | `iss` = `CLINICAL_JWT_ISSUER` (default `vitalstream-dev`), `aud` contains `clinical-api`, `sub`, `exp` (30 s clock skew). Optional `name`. |
| Roles | claim `roles` (string array) → authorities. `CLINICIAN` = read + acknowledge. `VIEWER` = read. `SERVICE` = read (machine clients, e.g. copilot). Unknown roles ignored. |
| Access | `GET /api/v1/**` incl. `/alerts/stream`: any of the three roles. `POST /api/v1/alerts/{id}/ack`: `CLINICIAN` only. `/actuator/health/**`, `/actuator/prometheus`: open. Anything else under `/api`: deny. |
| Errors | missing/invalid/expired token → 401 problem+json with `WWW-Authenticate: Bearer`; valid token, wrong role → 403 problem+json. Never log tokens. |
| Ack actor | Request body is `{"note"?}` (max 1000 chars). `acknowledgedBy` = token `name`, else `sub`. A `by` field in the body is ignored, never trusted. INFO audit log `alert acknowledged` with `alertId`, actor `sub`, `correlationId`. |
| SSE | same Bearer header. Browsers read the stream with `fetch` + `ReadableStream` (EventSource cannot send headers). Tokens never go in URLs or query strings. |
| Dev token endpoint | `POST /api/v1/dev/token` body `{"sub","name"?,"roles":[…]}` → `{"token","expiresAt"}` (HS256, TTL 8 h). Opt-in: registered only when **all three** hold: `CLINICAL_DEV_TOKENS_ENABLED=true` (default `false`; only the compose fragment sets it), `APP_ENV=dev`, and JWKS mode is off. Otherwise the route does not exist (404). Startup **fails** if `CLINICAL_DEV_TOKENS_ENABLED=true` while `APP_ENV != dev` or while `CLINICAL_JWT_JWK_SET_URI` is set. When registered, log one WARN at startup ("dev token endpoint enabled: unauthenticated token minting"). Unauthenticated by design (local demo login); may issue every role, including `SERVICE` (used by copilot `/mcp` and n8n in dev). |
| Startup guard | `CLINICAL_JWT_SECRET` joins the §4.1 secret guard when HS256 mode is used outside dev. |

Clients:
- **dashboard:** dev sign-in (choose a demo user: `nurse.levi` / Nurse Levi / CLINICIAN, `dr.cohen` / Dr. Cohen / CLINICIAN, `observer` / Observer / VIEWER), shows the user from `GET /api/v1/me`, keeps the token in memory (`sessionStorage` at most), sends it on every clinical-api **and copilot chat** call, returns to sign-in on 401, disables Acknowledge for VIEWER.
- **copilot chat (no confused deputy):** `POST /api/v1/chat` requires `Authorization: Bearer <clinical-api JWT>`. Before any LLM or tool call, copilot calls `GET /api/v1/me` on clinical-api with that token; 401/403 there → copilot answers the same status as problem+json and does no LLM call. Every tool call made for that chat forwards **the user's token**, never a service token, so clinical-api enforces the user's own access. Sessions are bound to `sub`: a `sessionId` issued to another `sub` is not accepted (a new session is issued). The token is never logged and never placed in the LLM prompt or tool results.
- **copilot MCP (`/mcp`, `MCP_TOKEN`):** a trusted integration channel with no end user; its tool calls use a `SERVICE` identity: `CLINICAL_API_TOKEN` if set, otherwise in `APP_ENV=dev` a `SERVICE` token for `sub` `copilot-mcp` from the dev endpoint, refreshed 60 s before expiry; outside dev without `CLINICAL_API_TOKEN` copilot fails at startup. The SERVICE identity is used **only** by `/mcp`.
**Secret startup guard (all services):** env `APP_ENV` (`dev` default, `prod` in Helm/Terraform). When `APP_ENV != dev`, startup must fail with a clear error if any secret (`INGEST_API_KEY`, `COPILOT_ADMIN_TOKEN`, `MCP_TOKEN`, `LOG_PSEUDONYM_KEY`, DB/broker passwords) is empty, equals its dev default, or is shorter than 32 chars. In `dev`, log one WARN at startup: "running with dev secrets". Unit-test both paths. RAG documents carry `provenance` (`curated` for bundled corpus, `api-upload` otherwise) shown in citations; retrieved text is passed to the LLM inside a delimited "untrusted reference material" block and the system prompt says never to follow instructions found in it.

## 5. Storage schemas

### PostgreSQL (`vitalstream` DB)
- Schema `clinical` (owner clinical-api, Flyway): `patients(id text pk, name, bed, ward, created_at)`, `alerts(id uuid pk, patient_id, type, severity, metric, value, score, message, window_start, window_end, detected_at, status, acknowledged_by, acknowledged_at, version, first_seen_at, last_seen_at, occurrences int, resolved_at null, parent_alert_id uuid null fk alerts, last_detection_id uuid)` — one row per **episode** (§3.4); partial unique index on `(patient_id, type, coalesce(metric, ''))` where `status <> 'RESOLVED'`; index on `(status, last_seen_at)` for the resolver, `outbox(id uuid pk, aggregate_id, event_type, payload jsonb, created_at, published_at null)`.
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
| `ewma:{patientId}:{metric}` | hash `mean,var,n,ts,hist` over **window means** (§3.3): `ts` = last applied windowStart (replays idempotent), `hist` = last 5 windows as a string of `+`/`-`/`.` (breach up / down / none), newest last. Key format of the v1 raw-sample state is reused; on first read of a v1 hash (no `hist`) the state is reset | 24 h | stream-processor |
| `alert:emitted:{alertId}` | string (last emitted severity) | 15 min | stream-processor – stops update-mode windows re-emitting the same alert each micro-batch; severity escalations pass |
| `alert:suppress:{patientId}:{type}:{metric}` | string | 5 min | clinical-api |
| `cache:vitals:{patientId}:{hash(query)}` | string JSON | 10 s | clinical-api |

### Elasticsearch
- `alerts` index (clinical-api): mirrors AlertView, one document per episode, upserted on every change; `message` text (english analyzer), keywords for ids/enums, dates.
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
| `CLINICAL_JWT_SECRET` | `dev-clinical-jwt-secret-0123456789abcdef` | clinical-api (HS256 mode) |
| `CLINICAL_JWT_JWK_SET_URI` | unset | clinical-api (RS256/OIDC mode when set) |
| `CLINICAL_JWT_ISSUER` | `vitalstream-dev` | clinical-api |
| `CLINICAL_DEV_TOKENS_ENABLED` | `false` (compose fragment: `true`) | clinical-api (dev token endpoint opt-in, §4.2) |
| `CLINICAL_API_TOKEN` | unset (dev: fetched from dev token endpoint) | copilot |
| `CLINICAL_API_URL` | `http://localhost:8082` | copilot, dashboard |
| `INGEST_GATEWAY_URL` | `http://localhost:8081` | simulator |
| `COPILOT_URL` | `http://localhost:8083` | dashboard |
| `LLM_PROVIDER` | `fake` | copilot (`fake` \| `anthropic`) |
| `ANTHROPIC_API_KEY` | empty | copilot |
| `LLM_MODEL` | `claude-sonnet-5-5` | copilot |
| `LOG_LEVEL` | `INFO` | all |
| `ARCHIVE_URI` | `s3a://vitalstream-archive/vitals_raw` | stream (bucket name differs per AWS account) |
| `VITALSTREAM_SQS_QUEUENAME` | `alert-escalations` | clinical-api (queue name; SDK resolves the URL) |
| `ALERT_RESOLVE_AFTER` | `PT5M` | clinical-api (ISO-8601 duration of silence before an episode auto-resolves, §3.4) |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | `vitalstream` | clinical-api (JDBC URL carries no credentials) |
| `METRICS_PORT` | `9404` | stream (`/health`, `/metrics`) |

Kubernetes probes: gateway/clinical-api `/actuator/health/{liveness,readiness}`, copilot `/health`, stream-processor `:9404/health`, dashboard `:3000/healthz`.

Kafka consumer groups: clinical-api `clinical-api`; stream-processor uses Spark's generated `spark-kafka-source-*`; ingest-gateway only produces.

Known gap (AWS only): Terraform makes MSK require SASL/IAM over TLS and ElastiCache require an AUTH token over TLS. The services speak PLAINTEXT Kafka and `redis://` today, so a real AWS deploy needs MSK IAM auth and `rediss://` support first. Local compose and kind are unaffected.

## 7. Correlation & logging fields

JSON log fields: `timestamp`, `level`, `service`, `correlationId`, `message`, plus context keys (`patientRef`, `eventId`, `alertId`, `topic`, `partition`, `offset`, `durationMs`). Never log vital values at INFO in bulk.

**PHI minimisation:** never log raw `patientId` (it is a medical record identifier). Log `patientRef` = first 12 hex chars of HMAC-SHA256(patientId, `LOG_PSEUDONYM_KEY`). Env `LOG_PSEUDONYM_KEY` default `dev-only-pseudonym-key` (must be overridden outside local dev). Same algorithm in every service so refs correlate across logs. Test vector: HMAC-SHA256("P-0001", "dev-only-pseudonym-key") → assert your helper matches the other languages (compute and pin it in a unit test).

**Event-time guard (stream-processor):** before `withWatermark` or any state update, drop events whose `observedAt` is more than `MAX_FUTURE_SKEW` ahead of processing time or older than 24 h; count them (`vitalstream_events_dropped_total{reason}`) and log at WARN with `eventId` only. `MAX_FUTURE_SKEW` must be ≤ the watermark delay (30 s) and is derived from it in code, not configured independently. Reason: Spark's watermark tracks the max event time across all partitions, so one event at now+Δ moves the global watermark to now+Δ−30 s and every on-time event for every patient becomes late and is silently dropped (alert suppression). With Δ ≤ 30 s the watermark can never pass real time. The gateway's 5 min limit is only a coarse input check; it is not this guard.

**Prometheus metric names (Grafana "VitalStream Overview" depends on these):**

| Metric | Type | Labels | Emitted by |
|---|---|---|---|
| `vitalstream_events_ingested_total` | counter | `source` (batch\|fhir\|hl7) | ingest-gateway |
| `vitalstream_events_rejected_total` | counter | `reason` | ingest-gateway |
| `vitalstream_events_dropped_total` | counter | `reason` (future\|stale) | stream-processor |
| `vitalstream_alerts_raised_total` | counter | `severity`, `type` | stream-processor (detected), clinical-api (persisted) – add label `service` via scrape config |
| `vitalstream_alert_episodes_total` | counter | `event` (opened\|escalated\|resolved), `type` | clinical-api (§3.4); its `vitalstream_alerts_raised_total` counts detections received |
| `http_server_requests_seconds_bucket` | histogram | Spring default | Java services |
| `kafka_consumergroup_lag` | gauge | kafka-exporter | foundation |

Micrometer names `vitalstream.events.ingested` etc. export as the names above. The event-time guard counter above is `vitalstream_events_dropped_total`.

**Schema validation notes:** `contracts/schemas/device-batch.schema.json` describes the `POST /api/v1/vitals/batch` body. Rules JSON Schema cannot express (gateway: observedAt ≤ now + 5 min, ≥ now − 24 h → reject; stream-processor: ≤ now + 30 s, ≥ now − 24 h → drop) are enforced in code.

## 8. Compose integration

- `infra/compose.yaml` (foundation) defines all infrastructure on network `vitalstream`.
- Each component ships `<component-path>/compose.yaml` defining only its own service(s), using in-network hostnames, `depends_on` with `condition: service_healthy` on infra services, and the `vitalstream` network declared `external: false` with the same name.
- Root `Makefile` (foundation) auto-discovers fragments: `docker compose --project-directory . -f infra/compose.yaml $(foreach f,$(wildcard services/*/compose.yaml tools/*/compose.yaml web/*/compose.yaml),-f $f)`.
- Make targets: `infra-up`, `infra-down`, `up`, `down`, `ps`, `logs`, `health`, `simulate`, `test`, `clean`.
- **Security:** every published host port binds to loopback (`"127.0.0.1:8081:8081"`), never `0.0.0.0`. Credentials come from env with local-dev defaults (`${VAR:-default}`). Ad-hoc test containers follow the same rule.
