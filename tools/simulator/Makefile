.PHONY: test lint integration

lint:
	uv run ruff check . && uv run ruff format --check . && uv run mypy --strict src

test:
	uv run pytest

integration:
	uv run pytest -m integration -o addopts=""
