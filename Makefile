.PHONY: help init-env doctor dev-infra dev-config keycloak-seed down core ai admin mobile \
        test-java test-ai test-importers typecheck build-frontend validate check prod-config verify-prod-env

help:
	@printf '%s\n' \
	  'Lyreo common commands:' \
	  '  make init-env        Copy/synchronize local .env files' \
	  '  make doctor          Inspect local toolchain/env readiness' \
	  '  make dev-infra       Start PostgreSQL + Keycloak only' \
	  '  make keycloak-seed   Verify realm/client/roles and seed dev users' \
	  '  make core            Run Spring Boot locally' \
	  '  make ai              Run FastAPI locally' \
	  '  make admin           Run Admin Vite dev server' \
	  '  make mobile          Run Expo Development Build dev server' \
	  '  make validate        Offline repository/syntax guardrails' \
	  '  make check           Run available Java/Python/importer/frontend checks' \
	  '  make dev-config      Validate compose.dev.yml syntax/resolution' \
	  '  make prod-config     Validate compose.prod.yml syntax/resolution'

init-env:
	./scripts/init-dev-env.sh

doctor:
	./scripts/doctor.sh

dev-infra:
	docker compose --env-file infra/docker/.env -f compose.dev.yml up -d

dev-config:
	docker compose --env-file infra/docker/.env -f compose.dev.yml config >/dev/null

keycloak-seed:
	set -a; . ./infra/keycloak/.env; set +a; ./infra/keycloak/scripts/bootstrap-keycloak.sh; ./infra/keycloak/scripts/seed-dev-users.sh

down:
	docker compose --env-file infra/docker/.env -f compose.dev.yml down

core:
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
	cd services/ai-service && uv run --extra dev pytest

test-importers:
	cd tools/data-import && uv run --extra dev pytest

typecheck:
	pnpm typecheck

build-frontend:
	pnpm build

validate:
	python3 -m compileall -q services/ai-service/app services/ai-service/tests tools/data-import
	bash -n scripts/*.sh infra/keycloak/scripts/*.sh infra/postgres/init/*.sh
	python3 tooling/validate_repo.py

check: validate test-java test-ai test-importers typecheck build-frontend

verify-prod-env:
	./scripts/verify-prod-env.sh

prod-config: verify-prod-env
	docker compose --env-file infra/docker/.env -f compose.prod.yml config >/dev/null
