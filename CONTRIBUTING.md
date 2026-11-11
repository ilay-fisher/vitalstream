# Engineering rules (binding for every contributor and agent)

Read this file and [docs/CONTRACTS.md](docs/CONTRACTS.md) before writing any code.

## 1. Ownership

Each workstream owns exactly the paths listed in its task. Do not edit files outside your paths.
If you need a change elsewhere (contract, shared infra), stop and report it to the manager instead of editing.

## 2. Test first (TDD)

1. Write a failing test that describes the behaviour.
2. Write the minimum code to make it pass.
3. Refactor. Repeat.

Commit history should show tests landing with or before the code they cover.

- Java: JUnit 5, AssertJ, Mockito, Testcontainers for anything that touches Kafka/Postgres/Redis/Cassandra/Elasticsearch/RabbitMQ. JaCoCo.
- Python: pytest, pytest-cov, testcontainers-python; chispa / local SparkSession for Spark.
- TypeScript: Vitest + Testing Library; Playwright for UI checks and screenshots.
- Coverage: at least 80 % line coverage on business logic (exclude generated code, config, `main`).
- Unit tests must not need Docker. Integration tests are tagged/marked (`@Tag("integration")`, `pytest -m integration`).

## 3. Code quality

- Small, single-responsibility classes/functions. Explicit names. No dead code, no commented-out code.
- Public classes, functions and REST endpoints have doc comments (Javadoc / docstrings / TSDoc) that say *why*, not just *what*.
- Formatting / linting must pass: Java `spotless` (google-java-format), Python `ruff` + `ruff format` + `mypy --strict` on src, TS `eslint` + `prettier` + `tsc --noEmit`.
- Config through environment variables only (12-factor). Defaults work with `infra/` compose on localhost.
- Never hard-code secrets. Never commit `.env`.

## 4. Logging and observability

- Structured JSON logs to stdout. Java: logback + logstash-logback-encoder. Python: structlog.
- Every log line carries `service` and `correlationId` (MDC / contextvars).
- `X-Correlation-Id` HTTP header is read or generated at the edge and propagated: HTTP header → Kafka header `correlationId` → RabbitMQ header → logs.
- Log levels: `INFO` for business events (event accepted, alert raised), `WARN` for recoverable problems (retry, validation reject), `ERROR` only with exception and context. `DEBUG` for payload details. Never log full PHI payloads at INFO.
- Expose `/actuator/health` + `/actuator/prometheus` (Java) or `/health` + `/metrics` (Python). Add business counters (events ingested, alerts raised, latency histograms).

## 5. Git and PR workflow

- Branch from `main`: `feat/<workstream>-<short-desc>` (e.g. `feat/ingest-gateway-fhir`).
- Conventional commits (`feat:`, `test:`, `fix:`, `docs:`, `chore:`, `ci:`).
- Each workstream ships its own GitHub Actions workflow `.github/workflows/<workstream>.yml` with a `paths:` filter, running lint + unit + integration tests.
- Open a PR to `main` with: summary, how to run, test evidence (counts + coverage), screenshots if UI, and any contract questions.
- Do not merge your own PR. The manager reviews and merges.

## 6. Definition of done

- [ ] All tests pass locally and in CI; coverage gate met.
- [ ] Lint/format/type checks clean.
- [ ] Logging per section 4; metrics endpoint works.
- [ ] Component `README.md` (purpose, run, config table, endpoints, design decisions).
- [ ] Dockerfile (multi-stage, non-root user, healthcheck) + `compose.yaml` fragment for the component (see CONTRACTS.md §8).
- [ ] Verified running for real against `make infra-up` (curl / script output, or screenshots for UI saved under `docs/images/`).

## 7. Local environment notes (macOS + Colima)

- Docker runtime is Colima. For Testcontainers export:
  ```bash
  export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
  export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
  export TESTCONTAINERS_HOST_OVERRIDE=$(colima ls -j | python3 -c 'import sys,json;print(json.load(sys.stdin).get("address") or "127.0.0.1")')
  ```
- PATH first: `export PATH=/opt/homebrew/opt/openjdk@21/bin:/opt/homebrew/bin:$PATH` (native arm64 `gh`/`git`; `/usr/local/bin/gh` is a broken x86 build).
- Java 21: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21`. Use the Maven wrapper (`./mvnw`).
- Ad-hoc manual runs while other workers run in parallel: start only the containers you need, named `<workstream>-<svc>`, on your host port block (foundation: real ports from CONTRACTS; ingest-gateway 21xxx; stream-processor 22xxx; clinical-api 23xxx; copilot 24xxx; simulator/dashboard 25xxx). Remove them when done.
- Python: use `uv` for env and dependency management.
- Docker memory is shared by parallel workers: cap heaps (ES `-Xms512m -Xmx512m`, Cassandra `MAX_HEAP_SIZE=1G`), and stop your containers when done.
