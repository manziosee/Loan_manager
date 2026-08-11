.PHONY: help dev test lint build docker-build docker-up docker-down migrate rollback clean fmt check

REGISTRY   ?= ghcr.io
IMAGE_NAME ?= loanmanager/loanmanager
VERSION    ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo "dev")

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
	  awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

# ── Development ───────────────────────────────────────────────────────────────

dev: ## Start REPL with dev profile (auto-starts DB via docker-compose)
	docker-compose up db -d
	clojure -M:dev

repl: ## Start bare nREPL (no auto-start)
	clojure -M:dev

# ── Testing ───────────────────────────────────────────────────────────────────

test: ## Run all unit tests
	clojure -M:test

test-watch: ## Run tests in watch mode
	clojure -M:test --watch

# ── Linting ───────────────────────────────────────────────────────────────────

lint: ## Run clj-kondo linter
	clj-kondo --lint src test

fmt: ## Check formatting with cljfmt
	clojure -Sdeps '{:deps {cljfmt/cljfmt {:mvn/version "0.9.2"}}}' \
	  -M -m cljfmt.main check src test

fmt-fix: ## Auto-fix formatting with cljfmt
	clojure -Sdeps '{:deps {cljfmt/cljfmt {:mvn/version "0.9.2"}}}' \
	  -M -m cljfmt.main fix src test

check: lint test ## Run lint + tests

# ── Build ─────────────────────────────────────────────────────────────────────

build: ## Build uberjar
	clojure -T:build uber

clean: ## Remove build artifacts
	clojure -T:build clean
	rm -rf target/

# ── Docker ────────────────────────────────────────────────────────────────────

docker-build: build ## Build Docker image
	docker build -t $(REGISTRY)/$(IMAGE_NAME):$(VERSION) .
	docker tag $(REGISTRY)/$(IMAGE_NAME):$(VERSION) $(REGISTRY)/$(IMAGE_NAME):latest

docker-up: ## Start full stack (app + db + pgadmin)
	docker-compose --profile dev up -d

docker-up-prod: ## Start production stack (app + db only)
	docker-compose up -d

docker-down: ## Stop all containers
	docker-compose --profile dev down

docker-logs: ## Tail application logs
	docker-compose logs -f app

docker-shell: ## Open shell in running app container
	docker-compose exec app sh

# ── Database ──────────────────────────────────────────────────────────────────

db-up: ## Start only the database
	docker-compose up db -d

migrate: ## Run pending migrations
	clojure -M:migrate

rollback: ## Rollback last migration
	clojure -M -e "(require '[loanmanager.db.migrations :as m] '[loanmanager.db.connection :as db] '[aero.core :as aero] '[clojure.java.io :as io]) (let [cfg (aero/read-config (io/resource \"config.edn\")) ds (db/init-pool! (:database cfg))] (m/rollback! ds) (db/close-pool! ds))"

psql: ## Open psql shell against local DB
	docker-compose exec db psql -U postgres -d loanmanager

# ── Utilities ─────────────────────────────────────────────────────────────────

outdated: ## Check for outdated dependencies
	clojure -Sdeps '{:deps {com.github.liquidz/antq {:mvn/version "RELEASE"}}}' \
	  -M -m antq.core

version: ## Print current version
	@echo $(VERSION)
