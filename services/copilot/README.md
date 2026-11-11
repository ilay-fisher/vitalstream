# copilot

Clinical decision-support copilot for VitalStream: a RAG pipeline over (synthetic) clinical guidelines, a LangGraph tool-calling agent that can read live patient vitals and alerts from `clinical-api`, a streaming chat API (SSE) and an MCP server exposing the same tools to Claude Desktop / Claude Code.

> All guideline content in `corpus/` is **synthetic educational text**. This service is a demo; it is not a medical device.

## Run

```bash
cd services/copilot
uv sync
# needs Postgres (pgvector) and Elasticsearch, e.g. `make infra-up` from the repo root
LLM_PROVIDER=fake uv run copilot            # http://localhost:8083/docs
```

Whole stack: `make up` at the repo root (uses `compose.yaml` in this folder). Container: multi-stage build, non-root user, `HEALTHCHECK` on `/health`, embedding model baked in at build time (no internet needed at runtime).

Tests:

```bash
uv run pytest                       # unit tests, no Docker, coverage gate 80 %
uv run pytest -m integration        # pgvector + Elasticsearch via Testcontainers
uv run ruff check . && uv run ruff format --check . && uv run mypy --strict src
```

## Endpoints

| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/chat` | `{"sessionId"?,"patientId"?,"message"}` -> SSE: `session`, `token`, `tool_call`, `citation`, `done` |
| POST | `/api/v1/documents` | **Bearer `COPILOT_ADMIN_TOKEN`**. `{"documents":[{"title"?,"source"?,"content"}]}` markdown -> 201; stored with provenance `api-upload` |
| GET | `/api/v1/documents/search?q=&k=` | hybrid search debug view incl. per-side ranks |
| POST | `/mcp` | **Bearer `MCP_TOKEN`**; MCP streamable HTTP (stateless, JSON responses) |
| GET | `/health`, `/metrics`, `/docs` | health (503 if Postgres is down), Prometheus, OpenAPI |

SSE events (`event:` line + one-line JSON `data:`):

* `session` `{"sessionId"}` - always first. The server issues a random 128-bit id and only accepts ids it issued that have not expired; unknown ids get a new session (never adopted).

* `token` `{"text"}` - answer fragments.
* `tool_call` `{"id","name","arguments","status":"running"|"ok"|"error","durationMs"?,"error"?}` - emitted when a tool starts and again when it finishes.
* `citation` `{"index","title","chunkId","documentId","score","provenance","snippet"}` - the `[n]` markers in the answer refer to `index`.
* `done` `{"text","toolCalls","usage","sessionId","correlationId"}`; on failure `{"error":true,"text":...}`.

## RAG pipeline

```mermaid
flowchart LR
  MD[Markdown corpus / POST documents] --> CH[Markdown-aware chunker<br/>~400 tokens, 60 overlap]
  CH --> EM[Embedder<br/>bge-small-en-v1.5, 384d]
  EM --> PG[(Postgres copilot.chunks<br/>pgvector HNSW cosine)]
  CH --> ES[(Elasticsearch guidelines<br/>BM25, english analyzer)]
  Q[Query] --> QE[Embed query]
  QE --> PG
  Q --> ES
  PG -- ranked list --> RRF[Reciprocal Rank Fusion k=60]
  ES -- ranked list --> RRF
  RRF --> HITS[Top-k hits<br/>title + chunk id = citation]
```

Chunks never cross headings and are prefixed with their heading path (`Title > Section`), so they embed and cite well. Lists, tables and fenced code are kept whole; a ~60-token tail of the previous chunk is repeated inside a section.

Reciprocal Rank Fusion merges the dense and BM25 rankings without needing comparable scores:

```
score(d) = sum over rankings r of  1 / (k + rank_r(d)),   k = 60, rank is 1-based
```

If one side is down (e.g. Elasticsearch restarting) search degrades to the other side and logs a warning. Ingestion writes Postgres first and rolls it back if indexing in Elasticsearch fails. On startup the corpus is ingested when the store is empty, and the Elasticsearch index is rebuilt from Postgres if it was wiped.

## Agent

Plain LangGraph (`StateGraph`) rather than the `deepagents` package: the use case is a bounded model/tool loop, so planning scratchpads and sub-agents would only add weight.

```mermaid
flowchart LR
  S([start]) --> L[llm node<br/>stream tokens]
  L -- tool calls and steps < max --> T[tools node<br/>parallel tool execution<br/>emits tool_call + citation]
  T --> L
  L -- no tool calls / step limit --> E([done])
```

Tools (shared with MCP): `get_patient_vitals`, `list_alerts` (HTTP to clinical-api, forwarding `X-Correlation-Id`), `search_guidelines` (hybrid RAG), `compute_news2` (pure function, RCP chart, scale 1 and 2). System prompt: decision support only, cite sources as `[n]`, never invent vitals. Conversation memory is per `sessionId`, in-process, bounded by TTL, session count and message count.

LLM providers (`LLM_PROVIDER`):

* `fake` (default): deterministic scripted model. It detects a patient id, calls vitals + alerts, computes NEWS2 from the retrieved values only, retrieves guidelines and writes a templated answer with citations. No network.
* `anthropic`: Anthropic Messages API with streaming and tool use; model from `LLM_MODEL`, key from `ANTHROPIC_API_KEY`. The key is never logged; prompts are not logged at INFO.

## MCP

Server name `vitalstream-copilot`, streamable HTTP, URL `http://localhost:8083/mcp`. Tools: `get_patient_vitals(patient_id, minutes)`, `list_alerts(patient_id?, severity?)`, `search_guidelines(query, k)`, `compute_news2(rr, spo2, sbp, hr, temp)`.

Claude Code:

```bash
claude mcp add --transport http vitalstream-copilot http://localhost:8083/mcp \
  --header "Authorization: Bearer dev-mcp-token"
```

Claude Desktop (`claude_desktop_config.json`, bridged with `mcp-remote`):

```json
{
  "mcpServers": {
    "vitalstream-copilot": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:8083/mcp",
               "--header", "Authorization: Bearer dev-mcp-token"]
    }
  }
}
```

Raw check:

```bash
curl -s localhost:8083/mcp -H 'authorization: Bearer dev-mcp-token' -H 'content-type: application/json' -H 'accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

Security (CONTRACTS §4.1): every `/mcp` request needs `Authorization: Bearer $MCP_TOKEN` (default `dev-mcp-token`), and DNS-rebinding protection only accepts Host `localhost:8083`, `127.0.0.1:8083`, `copilot:8083`. Pass the token with `--header "Authorization: Bearer dev-mcp-token"` in `claude mcp add`, or `--header` in the `mcp-remote` args.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `POSTGRES_URL` | `postgresql://vitalstream:vitalstream@localhost:5433/vitalstream` | schema `copilot`, migrated with Alembic at startup |
| `ELASTICSEARCH_URL` | `http://localhost:9200` | BM25 index `guidelines` (`ES_INDEX`) |
| `CLINICAL_API_URL` | `http://localhost:8082` | vitals and alerts |
| `COPILOT_ADMIN_TOKEN` / `MCP_TOKEN` | `dev-admin-token` / `dev-mcp-token` | bearer tokens (constant-time compare); override outside local dev |
| `ALLOWED_HOSTS` | `localhost,127.0.0.1,copilot` | Host-header allowlist on every route (400 otherwise) |
| `APP_ENV` | `dev` | anything else makes startup fail if the admin/MCP tokens, pseudonym key or DB password are empty, dev defaults or < 32 chars; `dev` logs one WARN "running with dev secrets" |
| `LOG_PSEUDONYM_KEY` | `dev-only-pseudonym-key` | HMAC key for `patientRef` in logs (raw patient ids are never logged) |
| `LLM_PROVIDER` | `fake` | `fake` or `anthropic` |
| `ANTHROPIC_API_KEY` | empty | required for `anthropic` |
| `LLM_MODEL` | `claude-sonnet-5-5` | Anthropic model id |
| `EMBEDDER` | `fastembed` | `fastembed` (bge-small) or `hash` (offline, deterministic, not semantic) |
| `FASTEMBED_CACHE_PATH` | unset (`/opt/models` in image) | model cache |
| `CORPUS_DIR` | `corpus/` | markdown ingested when the store is empty (`INGEST_ON_STARTUP`) |
| `SESSION_TTL_SECONDS` / `MAX_SESSIONS` / `MAX_HISTORY_MESSAGES` | 1800 / 1000 / 20 | chat memory bounds |
| `MAX_AGENT_STEPS` | 6 | model calls per answer |
| `CORS_ORIGINS` | localhost:3000, :5173 | comma separated |
| `LOG_LEVEL` / `PORT` | `INFO` / `8083` | |

## Safety notes

* Known gap: chat has no end-user auth in local dev (production: OIDC JWT at the gateway, session bound to `sub`).
* All free-text tool results (vitals, alerts, guidelines) are delimited as untrusted data.

* Retrieved chunks reach the model inside an `<untrusted_reference_material>` block (delimiters inside chunks are stripped) and the system prompt says never to follow instructions found there. A test with a poisoned document proves the scripted agent ignores it.
* Documents carry `provenance` (`curated` bundled corpus, `api-upload`), shown in citations and search results.
* Logs carry `patientRef` = HMAC-SHA256(patientId, key)[:12], never the raw id.

## Design decisions

* Tools are implemented once (`copilot.tools`) and consumed by both the agent and the MCP server, so behaviour and metrics cannot drift.
* Business logic depends on small ports (`Embedder`, `VectorIndex`, `KeywordIndex`, `DocumentStore`, `LLMProvider`), which keeps unit tests free of Docker and model downloads.
* clinical-api response shapes are not specified in CONTRACTS; the client accepts either a bare array or a page object (`content`/`items`/...) and reads AlertView / vital fields by the names in §3.
* Observability: JSON logs with `service` and `correlationId`, `/metrics` with chat requests, tool calls by name, retrieval latency, LLM latency and tokens.
