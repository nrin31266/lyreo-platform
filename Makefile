.PHONY: help init-env doctor setup setup-media deps deps-java data-fetch data-check lexicon-fetch lexicon-check dev-infra dev-config db-shell db-reset db-recreate seed-dev seed-lessons keycloak-seed down dev core ai admin mobile \
        mobile-ios-device-register mobile-ios-build \
        android-check android-emulator-create android-emulator mobile-android-install \
        ai-local clean-cache clean \
        test-java verify-java test-ai test-data-import test-tooling test-frontend typecheck build-web validate-docs validate-repo validate check prod-config verify-prod-env down-v \
        import-clean-lexicon import-clean-grammar-toeic migrate setup-data data-storage-check data-ai-pilot

help:
	@printf '%s\n' \
	  'Lyreo common commands:' \
	  '' \
	  'Setup' \
	  '  make setup                       First-clone setup: env + deps + infrastructure + migrations + reference seed' \
	  '  make init-env                    Copy/synchronize local .env files' \
	  '  make doctor                      Inspect local toolchain/env/data readiness' \
	  '  make setup-media                 Install the Core YouTube metadata/audio downloader' \
	  '  make deps                        Sync Python dependencies and install pnpm workspace dependencies' \
	  '  make deps-java                   Compile and install internal Java artifacts required by Core into local Maven cache' \
	  '' \
	  'Run' \
	  '  make dev                         Run Core + AI + Admin in one terminal (APPS="core ai admin mobile" to choose); Ctrl+C stops all' \
	  '  make core                        Run Core; rebuild/restart after saved Java/resources change' \
	  '  make ai                          Run FastAPI locally' \
	  '  make ai-local                    Run FastAPI locally with WhisperX and Kokoro' \
	  '  make admin                       Run Admin Vite dev server' \
	  '  make mobile                      Run Expo Metro bundler (requires Dev Build installed on device/emulator)' \
	  '' \
	  'Data' \
	  '  make setup-data [DATASET=...]     Install lexicon, grammar-toeic or both packages (all)' \
	  '  make data-storage-check          Check Core and importer use the same media storage' \
	  '  make data-ai-pilot ARGS=--execute Run the bounded maintenance pilot (default: dry-run)' \
	  '  make data-fetch                  Download/verify the versioned Grammar+TOEIC clean release' \
	  '  make data-check                  Verify the installed Grammar+TOEIC clean release' \
	  '  make lexicon-fetch               Download/verify the versioned Lexicon clean release' \
	  '  make lexicon-check               Verify the installed Lexicon clean release' \
	  '  make import-clean-lexicon        Validate/dry-run the pinned Lexicon release (ARGS="--apply" to install once)' \
	  '  make import-clean-grammar-toeic  Validate/dry-run the pinned Grammar+TOEIC release (ARGS="--apply" to install once)' \
	  '' \
	  'Infrastructure' \
	  '  make dev-infra                   Start PostgreSQL + Keycloak only' \
	  '  make db-shell                    Open psql inside PostgreSQL container against Lyreo dev DB' \
	  '  make db-reset                    Preview lesson-only reset; keeps imported banks and configuration' \
	  '  make db-recreate                 Preview destructive whole application database recreation' \
	  '  make seed-dev                    Migrate and seed reference categories in the configured database' \
	  '  make migrate                     Apply Flyway without starting HTTP or workers' \
	  '  make down-v CONFIRM=lyreo-volumes Delete ALL volumes, including imports and Keycloak' \
	  '  make keycloak-seed               Verify realm/client/roles and seed dev users' \
	  '' \
	  'Mobile native' \
	  '  make mobile-ios-device-register  Register a physical iOS device with EAS (run once per device)' \
	  '  make mobile-ios-build            Trigger EAS cloud build for iOS development profile' \
	  '  make android-check               Verify Android CLI tools, KVM, and AVD readiness' \
	  '  make android-emulator-create     Create the canonical Lyreo Android emulator (idempotent)' \
	  '  make android-emulator            Start the Android emulator (no Android Studio needed)' \
	  '  make mobile-android-install      Build and install Android Dev Build into running emulator/device' \
	  '' \
	  'Verification' \
	  '  make validate                    Offline repository/syntax guardrails' \
	  '  make check                       Run available Java/Python/importer/frontend checks' \
	  '  make validate-docs               Offline Markdown link/anchor/ID/path guardrails' \
	  '  make validate-repo               Offline repository invariant checks' \
	  '  make test-tooling                Run validator tests' \
	  '  make test-java                   Run fast Java unit tests (Surefire)' \
	  '  make verify-java                 Run full Java verification: unit + integration tests (Failsafe)' \
	  '  make test-data-import            Run data importer test suite' \
	  '  make typecheck                   Typecheck TypeScript workspaces' \
	  '  make test-frontend              Run Admin Web and Mobile tests' \
	  '  make build-web                  Build Admin Web' \
	  '' \
	  'Cleanup' \
	  '  make clean-cache                 Clean Python bytecode and test caches' \
	  '  make clean                       Clean Python bytecode and test caches' \
	  '' \
	  'Deployment/config' \
	  '  make dev-config                  Validate compose.dev.yml syntax/resolution' \
	  '  make prod-config                 Validate compose.prod.yml syntax/resolution' \
	  '' \
	  'make setup prepares the application; run make setup-data when you need content.' \
	  '' \
	  'Android first-time: make android-check -> make android-emulator-create -> make android-emulator -> make mobile-android-install -> make mobile' \
	  'iOS first-time:     make mobile-ios-device-register -> make mobile-ios-build -> install IPA from EAS URL -> make mobile'

init-env:
	./scripts/init-dev-env.sh

doctor:
	./scripts/doctor.sh

data-fetch:
	./tools/data-import/scripts/fetch-data.sh

data-check:
	./tools/data-import/scripts/fetch-data.sh --check

lexicon-fetch:
	./tools/data-import/scripts/fetch-lexicon.sh

lexicon-check:
	./tools/data-import/scripts/fetch-lexicon.sh --check

import-clean-lexicon:
	./tools/data-import/scripts/import-clean-lexicon.sh $(ARGS)

import-clean-grammar-toeic:
	./tools/data-import/scripts/import-clean-grammar-toeic.sh $(ARGS)

data-ai-pilot:
	./scripts/data-ai-pilot.sh $(ARGS)

deps-java:
	./mvnw -B -pl apps/core-service -am -DskipTests -DskipITs install

setup-media:
	uv tool install --upgrade --with-executables-from 'deno==2.9.8' 'yt-dlp[default]==2026.10.26'
	@command -v yt-dlp >/dev/null || { echo 'Add the uv tool bin directory to PATH before starting Core.'; exit 1; }
	@command -v deno >/dev/null || { echo 'Add the uv tool bin directory to PATH so yt-dlp can find Deno.'; exit 1; }
	@command -v ffmpeg >/dev/null && command -v ffprobe >/dev/null || { echo 'Install FFmpeg (including ffprobe) on the Core host.'; exit 1; }

deps: deps-java
	cd apps/ai-service && uv sync --locked --extra dev
	cd tools/data-import && uv sync --locked --extra dev
	pnpm install --frozen-lockfile

setup:
	$(MAKE) init-env
	$(MAKE) doctor
	$(MAKE) deps
	$(MAKE) dev-infra
	$(MAKE) keycloak-seed
	$(MAKE) seed-dev
	@printf '%s\n' \
	  'Lyreo local dependencies are ready.' \
	  'Open separate terminals for: make core, make ai, make admin, make mobile.'

dev-infra:
	docker compose --env-file infra/docker/.env -f compose.dev.yml up -d

dev-config:
	docker compose --env-file infra/docker/.env -f compose.dev.yml config >/dev/null

db-shell:
	docker compose --env-file infra/docker/.env -f compose.dev.yml exec postgres psql -U $${POSTGRES_USER:-lyreo} -d $${POSTGRES_DB:-lyreo_dev}

db-reset:
	./scripts/db-reset.sh

db-recreate:
	./scripts/db-reset.sh --database

seed-dev: deps-java
	@set -a; . ./apps/core-service/.env; set +a; ./mvnw -B -pl apps/core-service spring-boot:run -Dspring-boot.run.main-class=com.lyreo.platform.bootstrap.DatabaseSetup -Dspring-boot.run.arguments="--seed-dev \"$(CURDIR)/infra/postgres/seed/dev.sql\""

LESSON_SEED_FILE ?= $(or $(wildcard $(CURDIR)/.data/lesson-export/lessons-with-songs.sql),$(CURDIR)/infra/postgres/seed/lessons.sql)
seed-lessons: seed-dev
	@set -a; . ./apps/core-service/.env; set +a; ./mvnw -B -pl apps/core-service spring-boot:run -Dspring-boot.run.main-class=com.lyreo.platform.bootstrap.DatabaseSetup -Dspring-boot.run.arguments="--seed-dev \"$(abspath $(LESSON_SEED_FILE))\""

migrate: deps-java
	@set -a; . ./apps/core-service/.env; set +a; ./mvnw -B -pl apps/core-service spring-boot:run -Dspring-boot.run.main-class=com.lyreo.platform.bootstrap.DatabaseSetup

setup-data: export DATASET ?= all
setup-data:
	./scripts/setup-data.sh "$$DATASET"

data-storage-check:
	./scripts/check-data-storage.sh

keycloak-seed:
	set -a; . ./infra/keycloak/.env; set +a; ./infra/keycloak/scripts/bootstrap-keycloak.sh; ./infra/keycloak/scripts/seed-dev-users.sh

down:
	docker compose --env-file infra/docker/.env -f compose.dev.yml down

down-v:
	@test "$(CONFIRM)" = "lyreo-volumes" || { echo "Refusing volume deletion: use CONFIRM=lyreo-volumes. This removes application data AND Keycloak." >&2; exit 1; }
	docker compose --env-file infra/docker/.env -f compose.dev.yml down -v
	
# Prefixed logs in one terminal; Docker infra stays separate (make dev-infra / make down).
dev:
	@./scripts/dev.sh

core:
	@set -a; . ./apps/core-service/.env; set +a; python3 scripts/core_dev.py

ai:
	cd apps/ai-service && set -a && . ./.env && set +a && uv run --locked uvicorn app.main:app --reload --port 8000

# CTranslate2 resolves the CUDA libraries installed with PyTorch at process startup.
ai-local:
	cd apps/ai-service && uv sync --locked --extra whisperx --extra kokoro
	cd apps/ai-service && set -a && . ./.env && set +a && \
	  cuda_libs=$$(.venv/bin/python -c 'from pathlib import Path; import sysconfig; root = Path(sysconfig.get_path("purelib")); print(":".join(str(p) for p in (root / "nvidia/cublas/lib", root / "nvidia/cudnn/lib") if p.is_dir()))') && \
	  if [ -n "$$cuda_libs" ]; then export LD_LIBRARY_PATH="$$cuda_libs$${LD_LIBRARY_PATH:+:$$LD_LIBRARY_PATH}"; fi && \
	  AI_RUNTIME_MODE=local .venv/bin/python -m uvicorn app.main:app --reload --port 8000

admin:
	pnpm --filter @lyreo/admin-web dev

mobile:
	pnpm --filter @lyreo/mobile start

# Register a physical iOS device (UDID) with EAS for ad-hoc/internal distribution.
# Run this once per new device before triggering a new iOS development build.
# Requires EAS login: npx eas-cli@latest login
mobile-ios-device-register:
	cd apps/mobile && npx eas-cli@latest device:create

# Trigger an EAS cloud build for the iOS development profile.
# Use this after: adding a native module, changing Expo plugins/SDK, or registering a new device.
# The resulting .ipa is installed via the EAS build URL — no Xcode or macOS required on Fedora/Linux.
mobile-ios-build:
	cd apps/mobile && npx eas-cli@latest build --platform ios --profile development

# Verify Android CLI tools, KVM access, and canonical AVD status.
android-check:
	./apps/mobile/scripts/android-emulator.sh check

# Create the canonical Lyreo Android emulator (idempotent — safe to re-run).
# Requires: Android SDK with cmdline-tools and system-images;android-36;google_apis;x86_64.
android-emulator-create:
	./apps/mobile/scripts/android-emulator.sh create

# Start the canonical emulator without Android Studio.
# Run this in a dedicated terminal; Metro runs separately with: make mobile
android-emulator:
	./apps/mobile/scripts/android-emulator.sh start

# Build and install the Android Development Build into the running emulator or connected device.
# Uses --no-bundler so that 'make mobile' retains sole responsibility for running Metro.
# Requires an emulator or device to be visible via adb before running.
# Automatically sets sdk.dir and prioritizes canonical JDK 17 LTS (or 21 LTS fallback) when host Java is >= 24 (Spring Boot 4 backend).
mobile-android-install:
	./apps/mobile/scripts/android-install.sh

test-java:
	./mvnw -B test

verify-java:
	./mvnw -B verify

test-ai:
	cd apps/ai-service && uv run --locked --extra dev python -m pytest

test-data-import:
	cd tools/data-import && uv run --locked --extra dev python -m pytest

clean-cache:
	find . -type d -name "__pycache__" -exec rm -rf {} + 2>/dev/null || true
	find . -type d -name ".pytest_cache" -exec rm -rf {} + 2>/dev/null || true
	@echo "Cleaned Python bytecode and test caches."

clean: clean-cache
	@echo "Python bytecode and test caches cleaned."

typecheck:
	pnpm typecheck

test-frontend:
	pnpm test

build-web:
	pnpm build

validate-docs:
	python3 tooling/validate_docs.py

test-tooling:
	python3 -m unittest discover -s tooling/tests -v

validate-repo:
	python3 tooling/validate_repo.py

validate:
	$(MAKE) validate-docs
	@tmp=$$(mktemp -d); \
	  PYTHONPYCACHEPREFIX="$$tmp" python3 -m compileall -q \
	    apps/ai-service/app \
	    apps/ai-service/tests \
	    tools/data-import/import_grammar.py \
	    tools/data-import/import_toeic.py \
	    tools/data-import/import_lexicon.py \
	    tools/data-import/clean_import_common.py \
	    tools/data-import/import_clean_lexicon.py \
	    tools/data-import/import_clean_grammar_toeic.py \
	    tools/data-import/build_grammar_toeic_release.py \
	    tools/data-import/validate_grammar_toeic_release.py \
	    tools/data-import/scripts \
	    tools/data-import/build_lexicon_release.py \
	    tools/data-import/lexicon_vi_context.py \
	    tools/data-import/validate_lexicon_release.py \
	    tools/data-import/common.py \
	    scripts/core_dev.py \
	    tools/data-import/tests; \
	  status=$$?; rm -rf "$$tmp"; exit $$status
	bash -n scripts/*.sh apps/mobile/scripts/*.sh tools/data-import/scripts/*.sh infra/keycloak/scripts/*.sh infra/postgres/init/*.sh
	$(MAKE) validate-repo

check: validate test-tooling verify-java test-ai test-data-import typecheck test-frontend build-web

verify-prod-env:
	./scripts/verify-prod-env.sh

prod-config: verify-prod-env
	docker compose --env-file infra/docker/.env -f compose.prod.yml config >/dev/null
