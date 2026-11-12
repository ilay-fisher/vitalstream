# clinical-api

Spring Boot 4 (Java 21) service on port **8082**. It consumes `alerts.detected`, stores alerts in
Postgres, fans them out reliably (RabbitMQ, SQS, Elasticsearch, live SSE feed) and serves the
clinical read APIs (patients, vitals history, alerts, acknowledgement).

Layers: `api` (controllers, problem+json, SSE hub) -> `application` (use cases and ports) ->
`domain` (alert lifecycle, suppression, delivery planning) <- `infrastructure` (Kafka, JDBC, Redis,
RabbitMQ, SQS, Elasticsearch, Cassandra adapters and Spring config).

## Run

Real-run evidence against live containers: [docs/real-run.md](docs/real-run.md).

```bash
export PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH JAVA_HOME=/opt/homebrew/opt/openjdk@21
make infra-up                          # from repo root (defaults work against infra/ on localhost)
cd services/clinical-api && ./mvnw spring-boot:run
# or the whole stack: make up
```

Tests (Docker needed for integration tests, see CONTRIBUTING section 7 for the Colima exports):

```bash
./mvnw test      # unit tests, no Docker
./mvnw verify    # + Testcontainers integration tests, JaCoCo gate (>= 80 % lines), report in target/site/jacoco
./mvnw spotless:apply
```

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8082` | HTTP port |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka |
| `POSTGRES_URL` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `jdbc:postgresql://localhost:5433/vitalstream` / `vitalstream` / `vitalstream` | Postgres (schema `clinical`) |
| `REDIS_URL` | `redis://localhost:6379` | latest vitals, cache, suppression, pub/sub |
| `CASSANDRA_CONTACT_POINTS` | `localhost:9042` | `host:port[,host:port]`, keyspace `vitalstream`, DC `datacenter1` |
| `ELASTICSEARCH_URL` | `http://localhost:9200` | index `alerts` |
| `RABBITMQ_URL` | `amqp://vitalstream:vitalstream@localhost:5672` | exchange `alerts.notify` |
| `AWS_ENDPOINT_URL` / `AWS_REGION` | `` / `us-east-1` | SQS queue `alert-escalations` (endpoint set means LocalStack, dummy credentials) |
| `LOG_LEVEL` | `INFO` | root log level |
| `ALLOWED_HOSTS` | `localhost,127.0.0.1,clinical-api` | `Host` header allowlist (DNS-rebinding defence); bare names also match with the serving port, others get 400 problem+json |
| `APP_ENV` | `dev` | anything else enables the secret guard: startup fails if `LOG_PSEUDONYM_KEY`, the Postgres or RabbitMQ password is empty, a dev default or shorter than 32 chars (dev logs one WARN) |
| `CLINICAL_JWT_SECRET` | `dev-clinical-jwt-secret-0123456789abcdef` | HS256 key (>= 32 bytes) used to verify tokens when no JWKS URI is set; in the secret guard unless JWKS mode is on |
| `CLINICAL_JWT_JWK_SET_URI` | unset | when set, tokens are verified as RS256 against this JWKS (OIDC provider) and the HMAC secret and dev token endpoint are off |
| `CLINICAL_DEV_TOKENS_ENABLED` | `false` (compose fragment: `true`) | opt-in for the unauthenticated dev token endpoint; also needs `APP_ENV=dev` and no JWKS URI. Startup fails if it is `true` with `APP_ENV` other than `dev` or with `CLINICAL_JWT_JWK_SET_URI` set; when on, one WARN is logged |
| `CLINICAL_JWT_ISSUER` | `vitalstream-dev` | required `iss` claim |
| `LOG_PSEUDONYM_KEY` | `dev-only-pseudonym-key` | HMAC key for `patientRef` in logs (override outside local dev) |
| `vitalstream.outbox.batch-size`, `vitalstream.outbox.interval-ms` | `50`, `500` | relay tuning (Spring properties) |
| `vitalstream.kafka.retries`, `.backoff-initial`, `.backoff-multiplier` | `3`, `500ms`, `2.0` | consumer retry before DLQ |
| `vitalstream.cors.allowed-origins` | `http://localhost:3000,http://localhost:5173` | CORS |

## Authentication and roles

clinical-api is an OAuth2 resource server (CONTRACTS section 4.2). Every `/api/v1/**` call needs
`Authorization: Bearer <JWT>` with `iss` = `CLINICAL_JWT_ISSUER`, `aud` containing `clinical-api`,
`sub` and `exp` (30 s clock skew). The `roles` claim maps to authorities; unknown roles are ignored.

| Role | GET (incl. `/alerts/stream`) | `POST /alerts/{id}/ack` |
|---|---|---|
| `CLINICIAN` | yes | yes |
| `VIEWER` | yes | 403 |
| `SERVICE` (machine clients such as the copilot) | yes | 403 |

`/actuator/health/**` and `/actuator/prometheus` are open; Swagger UI and `/v3/api-docs` need a token;
anything else under `/api` is denied. Missing, invalid or expired token: 401 `application/problem+json`
with `WWW-Authenticate: Bearer`. Valid token, wrong role: 403 `application/problem+json`. Tokens are never logged.

The acknowledging user is the token, never the body: `acknowledgedBy` is the `name` claim, else `sub`;
a `by` field in the request body is ignored. Each success writes an INFO audit line `alert acknowledged`
with `alertId`, `actor` (the `sub`) and the `correlationId` MDC field.

**Dev login.** `POST /api/v1/dev/token` with `{"sub","name"?,"roles":[...]}` returns `{"token","expiresAt"}`
(HS256, 8 h). It exists only when `CLINICAL_DEV_TOKENS_ENABLED=true` (default `false`; only the compose fragment sets it),
`APP_ENV=dev` and `CLINICAL_JWT_JWK_SET_URI` is unset; otherwise it answers 404. It is unauthenticated on purpose (local demo login) and must never run in production.

```bash
TOKEN=$(curl -s -X POST localhost:8082/api/v1/dev/token -H 'Content-Type: application/json' \
  -d '{"sub":"nurse.levi","name":"Nurse Levi","roles":["CLINICIAN"]}' | jq -r .token)
curl -s -H "Authorization: Bearer $TOKEN" localhost:8082/api/v1/patients | jq
```

## Endpoints

Swagger UI: `/swagger-ui.html`, OpenAPI: `/v3/api-docs`, health: `/actuator/health`, metrics: `/actuator/prometheus`.
Errors are RFC 9457 `application/problem+json` with a `correlationId` property.

```bash
# all calls below need -H "Authorization: Bearer $TOKEN" (see Authentication above)
curl -s localhost:8082/api/v1/patients | jq                       # patients + latest vitals (Redis) + open alert count
curl -s localhost:8082/api/v1/patients/P-0001 | jq
curl -s "localhost:8082/api/v1/patients/P-0001/vitals?metric=HEART_RATE&from=2026-11-11T20:00:00Z&to=2026-11-11T21:00:00Z&resolution=minute" | jq
curl -s "localhost:8082/api/v1/alerts?patientId=P-0001&severity=CRITICAL&status=OPEN&page=0&size=20" | jq
curl -s "localhost:8082/api/v1/alerts?q=oxygen" | jq                # full text via Elasticsearch
curl -s localhost:8082/api/v1/alerts/<alertId> | jq
curl -s -X POST localhost:8082/api/v1/alerts/<alertId>/ack -H 'Content-Type: application/json' \
     -d '{"note":"at bedside"}' | jq                             # 409 if already acknowledged / concurrent change
curl -N -H 'Accept: text/event-stream' localhost:8082/api/v1/alerts/stream   # live feed, event "alert"
```

Every request accepts and returns `X-Correlation-Id`.

## Response shapes

Timestamps are ISO-8601 UTC strings, enum values upper case, `null` fields are always present in
`AlertView` and omitted in vital points. There is no separate `/search` endpoint: full-text search
is `GET /api/v1/alerts?q=...`, and acknowledgement is `POST` (CONTRACTS section 4), not PATCH.

`GET /api/v1/patients` returns a bare array; `GET /api/v1/patients/{id}` returns one element:

```json
[{"id":"P-0001","name":"Dana Levi","bed":"ICU-01","ward":"ICU",
  "latestVitals":{"HEART_RATE":{"value":97.0,"unit":"/min","observedAt":"2026-11-11T20:15:30.123Z"}},
  "openAlerts":2}]
```

`GET /api/v1/patients/{id}/vitals?metric=&from=&to=&resolution=raw|minute` (defaults: last hour, all metrics, raw; max range 7 days, max 5000 points, ascending by time):

```json
{"patientId":"P-0001","resolution":"RAW","from":"2026-11-11T20:00:00Z","to":"2026-11-11T21:00:00Z",
 "points":[{"observedAt":"2026-11-11T20:15:30Z","metric":"HEART_RATE","value":101.0,"unit":"/min","deviceId":"DEV-ICU-0001"}]}
```

With `resolution=minute` the response has `"resolution":"MINUTE"` and points are
`{"observedAt":"<window start>","metric":"HEART_RATE","min":98.0,"max":104.0,"avg":101.0,"count":12}`.

`GET /api/v1/alerts?patientId=&severity=&status=&q=&page=0&size=20` (size 1..100, newest first):

```json
{"items":[<AlertView>],"page":0,"size":20,"totalElements":31,"totalPages":2}
```

`GET /api/v1/alerts/{id}` and `POST /api/v1/alerts/{id}/ack` (body `{"note":"..."}`, optional; the actor comes from the token) return an `AlertView`:

```json
{"alertId":"0b7f9c3e-2a41-4f7e-9d0d-5c1a2b3c4d5e","patientId":"P-0001","type":"NEWS2","severity":"HIGH",
 "metric":null,"value":null,"score":7.0,"message":"NEWS2 score 7 (RR 25, SpO2 91, HR 131)",
 "windowStart":"2026-11-11T20:15:00Z","windowEnd":"2026-11-11T20:16:00Z","detectedAt":"2026-11-11T20:16:02.010Z",
 "status":"ACKNOWLEDGED","acknowledgedBy":"dr.cohen","acknowledgedAt":"2026-11-11T20:20:00Z",
 "acknowledgementNote":"at bedside","version":1}
```

SSE events (`event: alert`, `id: <alertId>:<version>`) carry the same `AlertView` as `data`.

Errors (`application/problem+json`): `{"type":"about:blank","title":"Resource not found","status":404,"detail":"Alert ... not found","instance":"/api/v1/alerts/...","correlationId":"..."}`;
404 unknown alert/patient, 400 invalid parameter or Host, 409 already acknowledged or concurrent change, 503 backing store down.

## Alert flow

```mermaid
sequenceDiagram
    participant SP as stream-processor
    participant K as Kafka alerts.detected
    participant C as AlertKafkaConsumer
    participant PG as Postgres
    participant R as Redis
    participant OR as OutboxRelay
    participant MQ as RabbitMQ alerts.notify
    participant SQS as SQS alert-escalations
    participant ES as Elasticsearch
    participant SSE as SSE clients (every replica)
    SP->>K: AlertEvent (correlationId header)
    K->>C: poll
    C->>PG: TX: INSERT alert ON CONFLICT DO NOTHING
    C->>R: SET alert:suppress:{patient}:{type}:{metric} NX EX 300
    C->>PG: TX: INSERT outbox rows (index / notify / escalate)
    C->>K: commit offset (manual ack)
    C->>R: PUBLISH alerts.live
    R-->>SSE: every replica receives, fans out to its clients
    loop every 500 ms, any replica
        OR->>PG: SELECT ... FOR UPDATE SKIP LOCKED
        OR->>ES: PUT alerts/_doc/{id} (external version)
        OR->>MQ: alert.<severity> (confirm + correlationId header)
        OR->>SQS: CRITICAL only
        OR->>PG: set published_at
    end
    Note over C,K: poison JSON or 3 failed retries -> alerts.detected.dlq (error, originalTopic headers)
```

## Design decisions

- **Transactional outbox instead of dual write.** Writing to Postgres and then to RabbitMQ/SQS/ES
  separately can lose or duplicate side effects on a crash between the two writes. The alert and its
  outbox rows commit atomically; a relay delivers afterwards. Delivery is at-least-once, so sinks are
  idempotent (ES uses the alert version as external version; consumers dedupe on `alertId`).
  One outbox row per sink means each destination retries independently.
- **`FOR UPDATE SKIP LOCKED`.** Every replica runs the relay. Rows locked by one replica are skipped
  by the others, so scaling out increases throughput without double delivery or lock waits. The
  claiming transaction stays open while delivering; batches are small (50) to bound lock time.
- **Idempotent intake.** `alertId` is deterministic (UUIDv5), the insert is `ON CONFLICT DO NOTHING`
  and a replay returns before touching suppression, outbox or live feed.
- **Suppression.** `SET NX` with a 5 minute TTL (value = claiming alertId, so a redelivery of the
  same alert is not mistaken for a repeat). Suppressed alerts are stored, indexed and shown live, but
  get no RabbitMQ/SQS notification.
- **CQRS-lite with Elasticsearch.** Postgres is the source of truth for state and structured
  queries; Elasticsearch is a read model fed through the outbox, used only when `q` is given. Searches
  can lag the database by one relay tick.
- **Pub/sub fan-out for SSE.** The Kafka consumer group means only one replica consumes a given
  alert, but every client must see it. The consumer publishes to Redis `alerts.live`; every replica
  subscribes and writes to its own SSE connections. The live feed is best effort (a Redis outage does
  not fail ingestion; clients can reload from `/api/v1/alerts`).
- **Optimistic locking on ack.** `UPDATE ... WHERE id=? AND version=?`; a lost race returns 409.
- **DLQ headers.** Records on `alerts.detected.dlq` keep the original key, value bytes and headers (`correlationId`, ...) and add `originalTopic` and `error` (`"<ExceptionSimpleName>: <message>"` of the listener failure, max 500 chars; poison records show `PoisonMessageException: ...`), plus Spring's standard `kafka_dlt-*` headers.
- **Errors.** `DefaultErrorHandler` with exponential backoff (3 retries) then
  `DeadLetterPublishingRecoverer`; non-retryable poison records are dead-lettered immediately with
  the original bytes. Offsets are committed manually after the transaction.
- **Lazy Cassandra session** so intake and search keep working while Cassandra is down or starting.
- **Contract additions.** `clinical.alerts.ack_note` stores the ack note; `AlertView` also exposes
  `acknowledgementNote` and `version`.

## Metrics

`alerts_consumed_total{outcome}`, `alerts_suppressed_total`, `alerts_dlq_total`, `vitalstream_alerts_raised_total{severity,type}` (alerts persisted, CONTRACTS section 7),
`outbox_lag_seconds` (age of oldest unpublished row), `notifications_sent_total{channel=rabbitmq|sqs}`,
`cache_requests_total{cache="vitals",result=hit|miss}` plus the standard JVM/HTTP/Kafka metrics.

## Logging

JSON on stdout (logstash encoder). Raw patient ids are never logged: `patientRef` is the first 12 hex chars of HMAC-SHA256(patientId, `LOG_PSEUDONYM_KEY`).
Fields: with `service` and MDC fields (`correlationId`, `alertId`,
`patientRef`, `topic`, `partition`, `offset`). Correlation id: HTTP header, then Kafka header
`correlationId`, then outbox payload, then RabbitMQ/SQS header.
