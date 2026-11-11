# simulator

Synthetic patients P-0001..P-0020 (the foundation seed) streaming vital signs into the ingest-gateway, plus a load generator. Python 3.12, `uv`, `httpx` (asyncio), `typer`, `structlog`.

## Run

```bash
cd tools/simulator && uv sync

# 5 patients, the first 2 develop sepsis, scenario time runs 10x faster than wall time
uv run vitalstream-sim run --scenario sepsis --patients 5 --affected 2 --speed 10x

# per-patient scenarios, other wire formats, bounded run
uv run vitalstream-sim run --assign P-0003=hypoxia --assign P-0004=arrhythmia --assign P-0005=sensor_fault \
  --transport fhir --ticks 120

# load: 2000 readings/s for 30 s, prints p50/p95 latency and accepted/duplicate/rejected counts
uv run vitalstream-sim load --rate 2000 --duration 30
```

In the stack (gateway healthy first, in-network URL `http://ingest-gateway:8081`):

```bash
make simulate                       # = docker compose ... run --rm simulator
SIM_SCENARIO=hypoxia SIM_PATIENTS=10 SIM_DURATION=600 make simulate
```

Tests: `uv run pytest` (unit, no Docker, 80 % coverage gate), `uv run pytest -m integration -o addopts=""` (real ingest-gateway + Redis + Kafka via Testcontainers, host port 25081; builds `simdash/ingest-gateway:test` from `services/ingest-gateway` if missing, override with `GATEWAY_IMAGE`). Lint: `make lint`.

## Configuration

| Option / env | Default | Meaning |
|---|---|---|
| `--url` / `INGEST_GATEWAY_URL` | `http://localhost:8081` | gateway base URL |
| `--api-key` / `INGEST_API_KEY` | `dev-ingest-key` | sent as `X-Api-Key` |
| `--scenario` / `SIM_SCENARIO` | `stable` | `stable`, `sepsis`, `hypoxia`, `arrhythmia`, `sensor_fault` |
| `--patients` / `SIM_PATIENTS` | 5 | patients P-0001..N (max 20) |
| `--affected` / `SIM_AFFECTED` | all | only the first N get `--scenario`, the rest stay stable |
| `--assign P-0003=sepsis` | | per-patient override (repeatable), wins over `--scenario` |
| `--speed` / `SIM_SPEED` | `1x` | scenario speed relative to wall clock |
| `--transport` / `SIM_TRANSPORT` | `batch` | `batch` (device JSON), `fhir` (R4 Bundle), `hl7` (ORU^R01) |
| `--interval` / `SIM_INTERVAL` | 1.0 | seconds between sampling rounds |
| `--duration` / `SIM_DURATION`, `--ticks` | unbounded | stop condition |
| `--seed` / `SIM_SEED` | 42 | everything is deterministic for a seed |
| `LOG_LEVEL` | `INFO` | JSON logs on stdout with `service` and `correlationId` |

## Scenarios

| Scenario | Behaviour | Expected downstream |
|---|---|---|
| `stable` | baseline + circadian drift + noise, inside normal range | no alerts |
| `sepsis` | HR +48, RR +14, Temp +2.4, SBP -38, DBP -20, SpO2 -6 over 600 scenario seconds | NEWS2 reaches >= 7 (CRITICAL) |
| `hypoxia` | SpO2 -13, RR +14, HR +45 over 300 s | NEWS2 >= 7, SpO2 threshold |
| `arrhythmia` | isolated HR bursts of +50..90 bpm on ~5 % of rounds | TREND_ZSCORE |
| `sensor_fault` | ~35 % of readings out of the valid range (HR 0/300, SpO2 0/140 ...) | gateway rejects with `OUT_OF_RANGE` |

## Design decisions

* **Deterministic.** Every sample is a pure function of (seed, patient, metric, tick); `eventId` is a UUIDv5 of `patientId|metric|observedAt`. A retry therefore re-sends byte-identical bodies and the gateway reports duplicates instead of storing twice. `load --run-id X` resends identical ids on purpose.
* **Speed only scales the scenario.** `observedAt` always follows the wall clock because the gateway rejects timestamps more than 5 minutes in the future. 10x means a 10 minute sepsis ramp takes one minute.
* **Retries.** 429 (honours `Retry-After`, capped by the backoff ceiling), 503 and transport errors back off exponentially with jitter, up to 5 retries; 4xx other than 429 fail fast. One `X-Correlation-Id` per logical request, reused across retries.
* **Load pacing.** Requests are scheduled on a fixed timeline (rate / batch size) with a bounded number in flight, so a slow gateway shows up as lower throughput and higher latency, not as an unbounded queue. Each request carries one patient's readings, which keeps the per-device limit (50 req/s) out of the way.
* **HL7.** The device id goes in OBX-18 (Equipment Instance Identifier) as the gateway reads it. Note `contracts/samples/hl7-oru-r01.hl7` places it at OBX-16.
* **Compose.** The `simulator` service has zero replicas so `make up` does not start it, but `compose config --services` still lists it for `make simulate`.
