# e2e

End-to-end suite for the VitalStream compose stack. It proves the platform works as a whole: one simulated sepsis patient (P-0003) travels from the gateway to a clinician acknowledgement and a copilot answer.

Python 3.12, `uv`, `pytest` (marker `e2e`), `httpx`, `confluent-kafka`.

## What it asserts

| Test | Hop | Pass condition |
|---|---|---|
| `test_01` | ingest-gateway | `POST /api/v1/vitals/batch` for P-0003 returns `202`, `accepted: 1` |
| `test_02` | Kafka `vitals.raw` | the probe `eventId` appears, key `P-0003`, with a `correlationId` header |
| `test_03` | simulator | the sepsis run finished without a traceback and produced readings for P-0003 |
| `test_04` | stream-processor | an alert for P-0003 shows up on `alerts.detected` |
| `test_05` | clinical-api REST | `GET /api/v1/alerts?patientId=P-0003` lists an alert the processor raised |
| `test_06` | clinical-api SSE | `GET /api/v1/alerts/stream` (opened before the run) delivered an `alert` event |
| `test_07` | clinical-api auth | no token: `GET /alerts` and `POST .../ack` return `401` problem+json with `WWW-Authenticate: Bearer`; health stays `200` |
| `test_08` | clinical-api auth | a VIEWER ack returns `403` and the alert stays `OPEN` |
| `test_09` | clinical-api ack | a CLINICIAN (Nurse Levi) ack returns `200`, `acknowledgedBy` is `Nurse Levi`; the second ack is `409` |
| `test_10` | clinical-api ack | a `by` field in the ack body is ignored: `acknowledgedBy` is the token's name (`Dr. Cohen`) |
| `test_11` | copilot auth | `POST /api/v1/chat` without a token returns `401` problem+json |
| `test_12` | copilot | with a user token, `POST /api/v1/chat` (`LLM_PROVIDER=fake`) emits `tool_call` ok for `get_patient_vitals` / `list_alerts` (called as that user) and ends with `done` (no error) |

Tokens come from clinical-api's dev endpoint (`APP_ENV=dev` and `CLINICAL_DEV_TOKENS_ENABLED=true`, which the compose fragment sets by default): `nurse.levi` and `dr.cohen` (CLINICIAN), `observer` (VIEWER). Tests 08-10 need at least three OPEN alerts for P-0003.

Every wait is `poll_until(...)` with a timeout and a description, so a failure names the missing hop.

## Run

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock   # Colima, CONTRIBUTING section 7
cd e2e && uv sync
uv run pytest                              # unit tests of the helpers, no Docker, 80 % coverage gate
uv run ruff check . && uv run ruff format --check . && uv run mypy --strict src
uv run pytest -m e2e -o addopts="" -v -s   # full stack, ~5 min after images are built
```

The session fixture runs `make infra-up`, then `docker compose --project-directory . -f infra/compose.yaml -f <every fragment> -f e2e/compose.e2e.yaml up -d --build --wait` for the five components (the same file set as `make up`; see "Compose override"), runs the scenario once, and tears down.

| Env | Default | Meaning |
|---|---|---|
| `E2E_TEARDOWN` | auto | `all`, `services` or `none`. Auto: `all` when the suite started the infra, otherwise only the five components, so shared infra is never stopped |
| `E2E_SKIP_BUILD` | unset | reuse built images (`--no-build`) |
| `E2E_SIM_DURATION`, `E2E_SIM_SPEED` | `150`, `20x` | simulator seconds and scenario speed (sepsis ramps over 600 scenario seconds) |
| `INGEST_GATEWAY_URL`, `CLINICAL_API_URL`, `COPILOT_URL`, `KAFKA_BOOTSTRAP_SERVERS`, `INGEST_API_KEY` | CONTRACTS host defaults | target another stack |

## Layout

```
src/vitalstream_e2e/   polling, sse, kafka_tail, probes, stack (all unit-tested without Docker)
tests/unit/            helper tests + static checks of workflows, Jenkinsfile, GitLab CI, n8n JSON
tests/e2e/             conftest (stack + scenario fixtures) and test_pipeline.py
compose.e2e.yaml       e2e-only override
```

## Compose override

`compose.e2e.yaml` is merged last. It caps memory per CONTRIBUTING section 7 (Java `-Xmx` through `JAVA_TOOL_OPTIONS`, Spark driver 1 GB, `mem_limit` per container). It also fixes two defects of `services/stream-processor/compose.yaml` that make `make up` fail on a clean checkout: `build.context: .` (resolves to the repo root) and the shared network declared under the key `default` (compose rejects the existing `vitalstream` network label). Both belong in that fragment; reported to the manager.

## Design decisions

* **Tap before you trigger.** The Kafka consumers and the SSE listener start before the probe and simulator run; the live feed has no replay and `auto.offset.reset=latest` must be pinned to partition assignment first.
* **One scenario, many tests.** The 150 s simulator run happens once in a session fixture; each test then asserts one hop, so a failure points at the broken hop.
* **No Docker in unit tests.** Polling takes an injected clock, the Kafka tail takes a consumer-shaped object, SSE parsing is pure.
* **Never stop what you did not start.** Developer machines share one infra stack; auto teardown leaves pre-existing infra running.

## Last run

2026-11-12, macOS + Colima (15.6 GiB VM), `E2E_SKIP_BUILD=1` (the five component images had been built by an earlier `up --build` run of the same suite; infra was already running, so auto teardown removed only the components). Command: `E2E_SKIP_BUILD=1 uv run pytest -m e2e -o addopts="" -v -s`. Paths shortened to `<repo>`, `rdkafka` connection noise removed.

```text
============================= test session starts ==============================
platform darwin -- Python 3.12.15, pytest-9.2.0, pluggy-1.7.0 -- <repo>/e2e/.venv/bin/python

testpaths: tests
plugins: cov-7.1.0, anyio-4.16.0, respx-0.23.1
collecting ... collected 46 items / 38 deselected / 8 selected

tests/e2e/test_pipeline.py::test_01_gateway_accepts_probe_with_202 $ docker ps --filter name=^vitalstream-kafka$ --format {{.Names}}
[e2e 01:30:48] infra already running: True; teardown mode: services
$ make -C <repo> infra-up
$ docker compose --project-directory . -f infra/compose.yaml -f <fragments> -f e2e/compose.e2e.yaml up -d --no-build --wait --wait-timeout 600 ingest-gateway stream-processor clinical-api copilot dashboard
[e2e 01:30:55] probe: POST one reading to the gateway
[e2e 01:30:55] simulator: sepsis scenario for P-0003 (150s at 20x)
$ docker compose --project-directory . -f infra/compose.yaml -f <fragments> -f e2e/compose.e2e.yaml run --rm --no-deps simulator run --assign P-0003=sepsis --patients 3 --speed 20x --duration 150
[e2e 01:33:26] gateway: 202 {'accepted': 1, 'duplicates': 0, 'rejected': []}
PASSED
tests/e2e/test_pipeline.py::test_02_probe_event_reaches_vitals_raw [e2e 01:33:26] vitals.raw: eventId 1a08e7e4-c4ff-42c1-bd61-33f09d7c8aee correlationId e2e-1a08e7e4-c4ff-42c1-bd61-33f09d7c8aee
PASSED
tests/e2e/test_pipeline.py::test_03_simulator_completed_without_gateway_errors [e2e 01:33:26] vitals.raw: 901 records for P-0003 during the run
PASSED
tests/e2e/test_pipeline.py::test_04_stream_processor_raises_alert_on_alerts_detected [e2e 01:33:26] alerts.detected: TREND_ZSCORE HIGH 605e2d65-cf21-55f7-8739-fe3f884b92ac: SPO2 98.0 deviates from EWMA trend (z=3.40)
PASSED
tests/e2e/test_pipeline.py::test_05_clinical_api_lists_the_alert [e2e 01:33:26] clinical-api: 50 alerts for P-0003
PASSED
tests/e2e/test_pipeline.py::test_06_sse_stream_delivers_the_alert [e2e 01:33:26] sse: event alert id=605e2d65-cf21-55f7-8739-fe3f884b92ac:0 severity=HIGH
PASSED
tests/e2e/test_pipeline.py::test_07_ack_returns_200_then_409 [e2e 01:33:26] ack: first 200, second 409 for bec60dc0-6d2e-51b7-bfc2-d894014f660d
PASSED
tests/e2e/test_pipeline.py::test_08_copilot_chat_calls_clinical_api_tools_and_finishes [e2e 01:33:26] copilot: events ['citation', 'done', 'session', 'token', 'tool_call']; tools ok ['list_alerts', 'get_patient_vitals', 'comput...
PASSED[e2e 01:33:27] stopping components only; pre-existing infra stays up
$ docker compose --project-directory . -f infra/compose.yaml -f <fragments> -f e2e/compose.e2e.yaml rm -sf ingest-gateway stream-processor clinical-api copilot dashboard

================= 8 passed, 38 deselected in 168.74s (0:02:48) =================
```
