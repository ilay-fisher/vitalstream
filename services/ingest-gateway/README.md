# ingest-gateway

Front door for vital signs. It accepts readings as a native device batch, FHIR R4 Observations/Bundles or HL7 v2.5 `ORU^R01`, validates every reading against the metric catalog, de-duplicates retries, throttles misbehaving devices and publishes `VitalSignEvent` records to Kafka topic `vitals.raw` (key `patientId`).

Java 21, Spring Boot 4.1, HAPI FHIR 8.12 (R4), HAPI HL7v2 2.6 (v2.5 structures), Redis, Kafka. Hexagonal layout under `io.vitalstream.ingest`:

| Package | Contents |
|---|---|
| `api` | REST controllers, DTOs, RFC 9457 error handler |
| `domain` | `Metric` catalog, `VitalReading`, `ReadingValidator`, `IngestService`, ports (no Spring types) |
| `adapters.inbound` | One strategy per wire format: `DeviceBatchAdapter`, `FhirAdapter`, `Hl7OruAdapter` (+ `Hl7AckBuilder`) |
| `adapters.outbound` | `RedisIdempotencyStore`, `RedisTokenBucketRateLimiter` (Lua), `KafkaEventPublisher` |
| `infrastructure` | Wiring, properties, correlation filter, Micrometer metrics |

## Run

```bash
# Infra (Kafka + Redis) from the repo root, then:
cd services/ingest-gateway
./mvnw spring-boot:run                      # http://localhost:8081
# or the whole stack
make up                                     # uses compose.yaml in this directory
```

Tests: `./mvnw test` (unit, no Docker), `./mvnw verify` (adds Testcontainers integration tests, JaCoCo report in `target/site/jacoco`, 80 % line-coverage gate), `./mvnw spotless:apply` to format.

Standalone compose: `docker compose --project-directory . -f infra/compose.yaml -f services/ingest-gateway/compose.yaml up` from the repo root (paths in the fragment are relative to the root).

## Configuration

| Env var | Default | Meaning |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka brokers |
| `REDIS_URL` | `redis://localhost:6379` | Redis |
| `LOG_LEVEL` | `INFO` | Root log level |
| `SERVER_PORT` | `8081` | HTTP port |
| `APP_ENV` | `dev` | Anything other than `dev` enables the secret startup guard |
| `INGEST_API_KEY` | `dev-ingest-key` | Required in header `X-Api-Key` on `/api/v1/**` |
| `LOG_PSEUDONYM_KEY` | `dev-only-pseudonym-key` | HMAC key for the `patientRef` log field |
| `ALLOWED_HOSTS` | `localhost`, `127.0.0.1`, `ingest-gateway` (each also with `:8081`) | Accepted `Host` headers (DNS-rebinding guard) |
| `INGEST_TOPIC` | `vitals.raw` | Target topic |
| `INGEST_IDEMPOTENCY_TTL` | `24h` | How long an `eventId` is remembered |
| `INGEST_RATE_LIMIT_PER_SECOND` | `50` | Sustained requests/s per device |
| `INGEST_RATE_LIMIT_BURST` | `100` | Bucket size per device |

## Endpoints

OpenAPI at `/v3/api-docs`, Swagger UI at `/swagger-ui.html`, health at `/actuator/health`, metrics at `/actuator/prometheus`. Every response carries `X-Correlation-Id` (your value if supplied, else generated); it is also in the logs and in the Kafka `correlationId` header.

Security (CONTRACTS section 4.1): every `/api/v1/**` call needs `X-Api-Key` (constant-time compare, `401` problem+json otherwise; the key is never logged). Health, metrics and API docs stay open. Requests whose `Host` header is not in `ALLOWED_HOSTS` get `400`. With `APP_ENV` other than `dev` the application refuses to start unless `INGEST_API_KEY` and `LOG_PSEUDONYM_KEY` (and a Redis password, if `REDIS_URL` has one) are set, differ from the dev defaults and are at least 32 characters; in dev a WARN `running with dev secrets` is logged. Logs never contain a raw `patientId`, only `patientRef` (first 12 hex of HMAC-SHA256).  All curl examples below need `-H 'X-Api-Key: dev-ingest-key'`.

Success is always `202 {"accepted":n,"duplicates":n,"rejected":[{"eventId","reason"}]}`. A bad reading is rejected on its own, the rest of the request is processed.

```bash
# Device batch (max 500 readings)
curl -i -X POST localhost:8081/api/v1/vitals/batch -H 'Content-Type: application/json' -d '{
  "deviceId":"DEV-ICU-0001","patientId":"P-0001",
  "readings":[{"eventId":"6f1c2a1e-8d0b-4c11-9a77-3f0c2b9e1d10","metric":"HEART_RATE","value":118.0,"observedAt":"2026-11-11T20:15:30.123Z"}]}'

# FHIR Observation or Bundle (type batch)
curl -i -X POST localhost:8081/api/v1/fhir/Observation -H 'Content-Type: application/fhir+json' \
  -d @src/test/resources/fixtures/fhir-observation-hr.json

# HL7 v2.5 ORU^R01, the response body is an ACK (segments separated by CR)
curl -i -X POST localhost:8081/api/v1/hl7 -H 'Content-Type: text/plain' \
  --data-binary @src/test/resources/fixtures/hl7-oru-r01.hl7 | tr '\r' '\n'
```

Errors are `application/problem+json` with a `correlationId` property: `400` malformed payload / validation, `415` wrong media type, `429` + `Retry-After` rate limited, `503` + `Retry-After` Kafka or Redis unavailable. The HL7 endpoint answers an unparseable message with `400` and an `AE` ACK instead, because HL7 senders only understand ACKs.

Rejection reasons (`rejected[].reason` starts with the code): `INVALID_EVENT_ID`, `INVALID_PATIENT_ID`, `INVALID_DEVICE_ID`, `UNKNOWN_METRIC`, `LOINC_MISMATCH`, `UNIT_MISMATCH`, `MISSING_VALUE`, `OUT_OF_RANGE`, `INVALID_TIMESTAMP`, `FUTURE_TIMESTAMP`, `UNSUPPORTED_RESOURCE`.

Format mapping notes:
- FHIR: patient from `subject.reference` (`Patient/P-0001`), device from `device.reference`, metric from the LOINC coding, unit from `valueQuantity.code` (UCUM), `eventId` from `Observation.id` (or the first `identifier.value`). Panels with `component` (blood pressure 85354-9) become one reading per component with a deterministic derived id.
- HL7: patient PID-3, LOINC OBX-3, value/unit OBX-5/6, time OBX-14 (fallback OBR-7), device OBX-18 (fallback MSH-3). HL7 has no event-id field, so ids are derived from sender + MSH-10 + OBX position; a retransmitted message therefore deduplicates. `AA` when no reading was rejected (duplicates count as fine), `AE` plus one `ERR` per rejection otherwise.

## Metrics

`vitalstream_events_ingested_total{source=batch|fhir|hl7}`, `vitalstream_events_rejected_total{reason}` (names pinned by CONTRACTS section 7 for the Grafana dashboard), `vitals_duplicates_total`, `vitals_kafka_publish_seconds{outcome}` (timer/histogram), plus the standard JVM/HTTP metrics.

## Design decisions

**Why 202.** The request is not "done" in the sense a REST resource is created; the reading is handed to a pipeline. We still answer 202 only after the Kafka broker acknowledged the write (`acks=all`, idempotent producer), so 202 means "durably in the log, processing is asynchronous". If the broker does not acknowledge we answer 503 and the sender retries.

**Idempotency and the race.** `SET idem:{eventId} 1 NX EX 86400` is a single atomic Redis command, so of N concurrent requests carrying the same id exactly one wins and publishes; the others are counted as duplicates. The claim happens before the publish (otherwise two requests could both publish), and is deleted if the publish fails so the client's retry is not mistaken for a duplicate. The remaining window: a concurrent duplicate that arrives while the winner is still publishing is told "duplicate" even if the winner then fails; the winner's client gets a 503 and retries, and that retry publishes the event, so nothing is lost, only delayed. If a batch partly fails, keys of the acknowledged readings stay (they are in Kafka), keys of the failed ones are released, and the whole batch gets 503; on retry the delivered readings come back as duplicates. If Redis is down during the claim the request fails with 503 (we cannot tell duplicates apart) and already-claimed keys are released.

**Why Lua for the rate limit.** A token bucket is read, refill, compare, write. Done from Java as GET then SET, two gateway instances can interleave and spend the same token twice. Executed as one Lua script Redis runs it atomically, in one round trip, and takes its clock from Redis `TIME`, so instances with skewed clocks agree. The bucket is a hash (`tokens`, `ts`) at `rl:{deviceId}` with a 10 s TTL, so idle devices cost nothing. One request consumes one token per distinct device in it, regardless of how many readings it carries. If Redis is unreachable the limiter fails open: refusing clinical data because the protective layer is down would be worse than briefly not throttling.

**Validation per reading, in one place.** Adapters only translate structure into `RawReading`; `ReadingValidator` applies the catalog rules (UUID event id, `P-` / `DEV-` id patterns, metric/LOINC/unit consistency, valid range, `observedAt` at most 5 minutes in the future) so the three formats cannot drift apart.

**Keyed by patient.** Records are keyed by `patientId` so one patient's readings stay ordered inside a partition, which the stream processor's per-patient state relies on.
