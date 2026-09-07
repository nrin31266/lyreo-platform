.PHONY: help init-env doctor setup deps deps-java data-fetch data-check dev-infra dev-config keycloak-seed down core ai admin mobile \
        test-java test-ai test-importers typecheck build-frontend validate check prod-config verify-prod-env

help:
	@printf '%s\n' \
	  'Lyreo common commands:' \
	  '  make setup           First-clone setup: env + dataset + deps + PostgreSQL/Keycloak bootstrap' \
	  '  make init-env        Copy/synchronize local .env files' \
	  '  make doctor          Inspect local toolchain/env/data readiness' \
	  '  make data-fetch      Download/install Grammar+TOEIC dataset when missing' \
	  '  make data-check      Validate the importer-facing Grammar+TOEIC dataset structure' \
	  '  make deps            Sync Python dependencies and install pnpm workspace dependencies' \
	  '  make deps-java       Compile and install internal Java artifacts required by Core into local Maven cache' \
	  '  make dev-infra       Start PostgreSQL + Keycloak only' \
	  '  make keycloak-seed   Verify realm/client/roles and seed dev users' \
	  '  make core            Run Spring Boot locally' \
	  '  make ai              Run FastAPI locally' \
	  '  make admin           Run Admin Vite dev server' \
	  '  make mobile          Run Expo Development Build dev server' \
	  '  make validate        Offline repository/syntax guardrails' \
	  '  make check           Run available Java/Python/importer/frontend checks' \
	  '  make dev-config      Validate compose.dev.yml syntax/resolution' \
	  '  make prod-config     Validate compose.prod.yml syntax/resolution' \
	  '' \
	  'First clone downloads the shared TOEIC archive by default. Use SKIP_DATA=1 make setup to defer it.'

init-env:
	./scripts/init-dev-env.sh

doctor:
	./scripts/doctor.sh

data-fetch:
	./scripts/fetch-data.sh

data-check:
	./scripts/fetch-data.sh --check

deps-java:
	./mvnw -B -pl apps/core-service -am -DskipTests install

deps: deps-java
	cd services/ai-service && uv sync --locked --extra dev
	cd tools/data-import && uv sync --locked --extra dev
	pnpm install --frozen-lockfile

setup:
	$(MAKE) doctor
	$(MAKE) init-env
	@if [ "$${SKIP_DATA:-0}" = "1" ]; then \
		echo 'Skipping Grammar/TOEIC dataset download because SKIP_DATA=1.'; \
	else \
		./scripts/fetch-data.sh --required; \
	fi
	$(MAKE) deps
	$(MAKE) dev-infra
	$(MAKE) keycloak-seed
	@printf '%s\n' \
	  'Lyreo local dependencies are ready.' \
	  'Open separate terminals for: make core, make ai, make admin, make mobile.'

dev-infra:
	docker compose --env-file infra/docker/.env -f compose.dev.yml up -d

dev-config:
	docker compose --env-file infra/docker/.env -f compose.dev.yml config >/dev/null

keycloak-seed:
	set -a; . ./infra/keycloak/.env; set +a; ./infra/keycloak/scripts/bootstrap-keycloak.sh; ./infra/keycloak/scripts/seed-dev-users.sh

down:
	docker compose --env-file infra/docker/.env -f compose.dev.yml down

down-v:
	docker compose --env-file infra/docker/.env -f compose.dev.yml down -v
	
core: deps-java
	cd apps/core-service && set -a && . ./.env && set +a && ../../mvnw spring-boot:run

ai:
	cd services/ai-service && set -a && . ./.env && set +a && uv run uvicorn app.main:app --reload --port 8000

admin:
	pnpm --filter @lyreo/admin-web dev

mobile:
	pnpm --filter @lyreo/mobile start

test-java:
	./mvnw -B test

test-ai:
	cd services/ai-service && uv run --locked --extra dev pytest

test-importers:
	cd tools/data-import && uv run --locked --extra dev pytest

typecheck:
	pnpm typecheck

build-frontend:
	pnpm build

validate:
	@tmp=$$(mktemp -d); \
	  PYTHONPYCACHEPREFIX="$$tmp" python3 -m compileall -q \
	    services/ai-service/app \
	    services/ai-service/tests \
	    tools/data-import/import_grammar.py \
	    tools/data-import/import_toeic.py \
	    tools/data-import/import_lexicon.py \
	    tools/data-import/common.py \
	    tools/data-import/tests; \
	  status=$$?; rm -rf "$$tmp"; exit $$status
	bash -n scripts/*.sh infra/keycloak/scripts/*.sh infra/postgres/init/*.sh
	python3 tooling/validate_repo.py

check: validate test-java test-ai test-importers typecheck build-frontend

verify-prod-env:
	./scripts/verify-prod-env.sh

prod-config: verify-prod-env
	docker compose --env-file infra/docker/.env -f compose.prod.yml config >/dev/null
