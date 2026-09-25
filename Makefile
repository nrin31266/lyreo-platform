.PHONY: help init-env doctor setup deps deps-java data-fetch data-check dev-infra dev-config db-shell db-reset keycloak-seed down core ai admin mobile \
        mobile-ios-device-register mobile-ios-build \
        android-check android-emulator-create android-emulator mobile-android-install \
        ai-local lesson-prep test-lesson-prep clean-prep clean-cache clean \
        test-java verify-java test-ai test-data-import test-tooling test-frontend typecheck build-web validate-docs validate-repo validate check prod-config verify-prod-env down-v

help:
	@printf '%s\n' \
	  'Lyreo common commands:' \
	  '' \
	  'Setup' \
	  '  make setup                       First-clone setup: env + deps + PostgreSQL/Keycloak bootstrap' \
	  '  make init-env                    Copy/synchronize local .env files' \
	  '  make doctor                      Inspect local toolchain/env/data readiness' \
	  '  make deps                        Sync Python dependencies and install pnpm workspace dependencies' \
	  '  make deps-java                   Compile and install internal Java artifacts required by Core into local Maven cache' \
	  '' \
	  'Run' \
	  '  make core                        Run Spring Boot locally' \
	  '  make ai                          Run FastAPI locally' \
	  '  make ai-local                    Run FastAPI locally with local Qwen/Kokoro weights' \
	  '  make lesson-prep                 Run local Lesson Prep Gradio workstation' \
	  '  make admin                       Run Admin Vite dev server' \
	  '  make mobile                      Run Expo Metro bundler (requires Dev Build installed on device/emulator)' \
	  '' \
	  'Data' \
	  '  make data-fetch                  Download/verify the versioned Grammar+TOEIC clean release' \
	  '  make data-check                  Verify the installed Grammar+TOEIC clean release' \
	  '' \
	  'Infrastructure' \
	  '  make dev-infra                   Start PostgreSQL + Keycloak only' \
	  '  make db-shell                    Open psql inside PostgreSQL container against Lyreo dev DB' \
	  '  make db-reset                    Reset local Lyreo application DB (preserves Keycloak)' \
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
	  '  make test-lesson-prep            Run lesson-prep test suite' \
	  '  make typecheck                   Typecheck TypeScript workspaces' \
	  '  make test-frontend              Run Admin Web and Mobile tests' \
	  '  make build-web                  Build Admin Web' \
	  '' \
	  'Cleanup' \
	  '  make clean-prep                  Clean temporary lesson-prep session workspaces' \
	  '  make clean-cache                 Clean Python bytecode and test caches' \
	  '  make clean                       Aggregate cleanup: clean-cache + clean-prep' \
	  '' \
	  'Deployment/config' \
	  '  make dev-config                  Validate compose.dev.yml syntax/resolution' \
	  '  make prod-config                 Validate compose.prod.yml syntax/resolution' \
	  '' \
	  'Clean release download is opt-in: make data-fetch, or WITH_DATA=1 make setup.' \
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

deps-java:
	./mvnw -B -pl apps/core-service -am -DskipTests install

deps: deps-java
	cd apps/ai-service && uv sync --locked --extra dev
	cd tools/data-import && uv sync --locked --extra dev
	cd tools/lesson-prep && uv sync --locked --extra dev
	pnpm install --frozen-lockfile

setup:
	$(MAKE) init-env
	$(MAKE) doctor
	$(MAKE) deps
	@if [ "$${WITH_DATA:-0}" = "1" ]; then \
		$(MAKE) data-fetch; \
	fi
	$(MAKE) dev-infra
	$(MAKE) keycloak-seed
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

keycloak-seed:
	set -a; . ./infra/keycloak/.env; set +a; ./infra/keycloak/scripts/bootstrap-keycloak.sh; ./infra/keycloak/scripts/seed-dev-users.sh

down:
	docker compose --env-file infra/docker/.env -f compose.dev.yml down

down-v:
	docker compose --env-file infra/docker/.env -f compose.dev.yml down -v
	
core: deps-java
	cd apps/core-service && set -a && . ./.env && set +a && ../../mvnw spring-boot:run

ai:
	cd apps/ai-service && set -a && . ./.env && set +a && uv run --locked uvicorn app.main:app --reload --port 8000

# Local Qwen runtime: requires GPU/model availability. qwen + kokoro extras both live here
# because the Lesson Prep Tool may call STT/alignment AND Kokoro TTS in one session.
ai-local:
	cd apps/ai-service && set -a && . ./.env && set +a && AI_RUNTIME_MODE=local uv run --locked --extra qwen --extra kokoro uvicorn app.main:app --reload --port 8000

# Lesson Prep Tool. Requires: make ai (AI service), and ffmpeg/ffprobe on PATH.
# Does NOT require Core, Keycloak, or PostgreSQL.
lesson-prep:
	@[ -f tools/lesson-prep/.env ] || { echo 'tools/lesson-prep/.env missing — run make init-env first'; exit 1; }
	cd tools/lesson-prep && set -a && . ./.env && set +a && uv run --locked python -m lesson_prep.ui_app

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

test-lesson-prep:
	cd tools/lesson-prep && uv run --locked --extra dev python -m pytest

clean-prep:
	rm -rf tools/lesson-prep/.work/* tools/lesson-prep/.pytest_cache
	@echo "Cleaned lesson-prep temporary files."

clean-cache:
	find . -type d -name "__pycache__" -exec rm -rf {} + 2>/dev/null || true
	find . -type d -name ".pytest_cache" -exec rm -rf {} + 2>/dev/null || true
	@echo "Cleaned Python bytecode and test caches."

clean: clean-cache clean-prep
	@echo "All caches and temporary workspaces cleaned."

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
	    tools/lesson-prep/lesson_prep \
	    tools/lesson-prep/tests \
	    tools/data-import/import_grammar.py \
	    tools/data-import/import_toeic.py \
	    tools/data-import/import_lexicon.py \
	    tools/data-import/build_grammar_toeic_release.py \
	    tools/data-import/validate_grammar_toeic_release.py \
	    tools/data-import/scripts/fetch_release.py \
	    tools/data-import/common.py \
	    tools/data-import/tests; \
	  status=$$?; rm -rf "$$tmp"; exit $$status
	bash -n scripts/*.sh apps/mobile/scripts/*.sh tools/data-import/scripts/*.sh infra/keycloak/scripts/*.sh infra/postgres/init/*.sh
	$(MAKE) validate-repo

check: validate test-tooling verify-java test-ai test-data-import test-lesson-prep typecheck test-frontend build-web

verify-prod-env:
	./scripts/verify-prod-env.sh

prod-config: verify-prod-env
	docker compose --env-file infra/docker/.env -f compose.prod.yml config >/dev/null
