# VitalStream developer entry points (CONTRACTS section 8).
# Component fragments (services/*, tools/*, web/*) are discovered automatically; with none present
# every target still works against infra alone.

FRAGMENTS := $(wildcard services/*/compose.yaml tools/*/compose.yaml web/*/compose.yaml)
COMPOSE_BASE := docker compose --project-directory . -f infra/compose.yaml
COMPOSE := $(COMPOSE_BASE) $(foreach f,$(FRAGMENTS),-f $(f))
PROFILES ?=
PROFILE_FLAGS := $(foreach p,$(PROFILES),--profile $(p))

.DEFAULT_GOAL := help
.PHONY: help infra-up infra-down up down ps logs health simulate test clean fragments

help: ## List targets
	@grep -E '^[a-z-]+:.*##' $(MAKEFILE_LIST) | sed -E 's/:.*## /\t/' | column -t -s "$$(printf '\t')"

fragments: ## Show discovered component compose fragments
	@echo "$(if $(FRAGMENTS),$(FRAGMENTS),(none))"

infra-up: ## Start infrastructure and run idempotent init (PROFILES=tools adds Kibana + n8n)
	$(COMPOSE_BASE) $(PROFILE_FLAGS) up -d --wait --wait-timeout 420

infra-down: ## Stop infrastructure (volumes kept)
	$(COMPOSE_BASE) --profile tools down --remove-orphans

up: ## Start infrastructure plus every discovered component
	$(COMPOSE) $(PROFILE_FLAGS) up -d --build --wait --wait-timeout 600

down: ## Stop everything (volumes kept)
	$(COMPOSE) --profile tools down --remove-orphans

ps: ## Show container status
	$(COMPOSE) --profile tools ps

logs: ## Tail logs (SERVICE=name to filter)
	$(COMPOSE) --profile tools logs -f --tail=100 $(SERVICE)

health: ## Check every infra service; non-zero exit on failure
	@scripts/health.sh

simulate: ## Run the vitals simulator if a component provides one
	@if $(COMPOSE) config --services | grep -qx simulator; then \
		$(COMPOSE) run --rm simulator; \
	else \
		echo "no 'simulator' service found in discovered compose fragments"; exit 1; \
	fi

test: ## Contract tests, then `make test` in each component that has a Makefile
	cd contracts && uv run pytest -q
	@for d in $(wildcard services/*/Makefile tools/*/Makefile web/*/Makefile); do \
		echo "== $$(dirname $$d)"; $(MAKE) -C $$(dirname $$d) test || exit 1; \
	done

clean: ## Stop everything AND delete all volumes (data loss)
	$(COMPOSE) --profile tools down -v --remove-orphans
