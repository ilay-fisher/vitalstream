# syntax=docker/dockerfile:1
FROM python:3.12-slim AS builder
COPY --from=ghcr.io/astral-sh/uv:0.12.23 /uv /usr/local/bin/uv
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=0
WORKDIR /app
COPY pyproject.toml uv.lock README.md ./
RUN uv sync --frozen --no-dev --no-install-project
COPY src ./src
RUN uv sync --frozen --no-dev --no-editable

FROM python:3.12-slim
RUN useradd --system --uid 10001 --create-home simulator
WORKDIR /app
COPY --from=builder /app/.venv /app/.venv
ENV PATH="/app/.venv/bin:$PATH" PYTHONUNBUFFERED=1
USER simulator
# The simulator is a client; the check proves the installed CLI starts.
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD ["vitalstream-sim", "--help"]
ENTRYPOINT ["vitalstream-sim"]
CMD ["run"]
