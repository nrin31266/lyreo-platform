.PHONY: help init-env doctor setup deps deps-java data-fetch data-check dev-infra dev-config keycloak-seed down core ai admin mobile \
        mobile-ios-device-register mobile-ios-build \
        android-check android-emulator-create android-emulator mobile-android-install \
        test-java test-ai test-importers typecheck build-frontend validate check prod-config verify-prod-env

help:
	@printf '%s\n' \
	  'Lyreo common commands:' \
	  '  make setup                       First-clone setup: env + dataset + deps + PostgreSQL/Keycloak bootstrap' \
	  '  make init-env                    Copy/synchronize local .env files' \
	  '  make doctor                      Inspect local toolchain/env/data readiness' \
	  '  make data-fetch                  Download/install Grammar+TOEIC dataset when missing' \
	  '  make data-check                  Validate the importer-facing Grammar+TOEIC dataset structure' \
	  '  make deps                        Sync Python dependencies and install pnpm workspace dependencies' \
	  '  make deps-java                   Compile and install internal Java artifacts required by Core into local Maven cache' \
	  '  make dev-infra                   Start PostgreSQL + Keycloak only' \
	  '  make keycloak-seed               Verify realm/client/roles and seed dev users' \
	  '  make core                        Run Spring Boot locally' \
	  '  make ai                          Run FastAPI locally' \
	  '  make admin                       Run Admin Vite dev server' \
	  '  make mobile                      Run Expo Metro bundler (requires Dev Build installed on device/emulator)' \
	  '  make mobile-ios-device-register  Register a physical iOS device with EAS (run once per device)' \
	  '  make mobile-ios-build            Trigger EAS cloud build for iOS development profile' \
	  '  make android-check               Verify Android CLI tools, KVM, and AVD readiness' \
	  '  make android-emulator-create     Create the canonical Lyreo Android emulator (idempotent)' \
	  '  make android-emulator            Start the Android emulator (no Android Studio needed)' \
	  '  make mobile-android-install      Build and install Android Dev Build into running emulator/device' \
	  '  make validate                    Offline repository/syntax guardrails' \
	  '  make check                       Run available Java/Python/importer/frontend checks' \
	  '  make dev-config                  Validate compose.dev.yml syntax/resolution' \
	  '  make prod-config                 Validate compose.prod.yml syntax/resolution' \
	  '' \
	  'First clone downloads the shared TOEIC archive by default. Use SKIP_DATA=1 make setup to defer it.' \
	  '' \
	  'Android first-time: make android-check -> make android-emulator-create -> make android-emulator -> make mobile-android-install -> make mobile' \
	  'iOS first-time:     make mobile-ios-device-register -> make mobile-ios-build -> install IPA from EAS URL -> make mobile'

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
	./scripts/android-emulator.sh check

# Create the canonical Lyreo Android emulator (idempotent — safe to re-run).
# Requires: Android SDK with cmdline-tools and system-images;android-36;google_apis;x86_64.
android-emulator-create:
	./scripts/android-emulator.sh create

# Start the canonical emulator without Android Studio.
# Run this in a dedicated terminal; Metro runs separately with: make mobile
android-emulator:
	./scripts/android-emulator.sh start

# Build and install the Android Development Build into the running emulator or connected device.
# Uses --no-bundler so that 'make mobile' retains sole responsibility for running Metro.
# Requires an emulator or device to be visible via adb before running.
# Automatically sets sdk.dir and prioritizes canonical JDK 17 LTS (or 21 LTS fallback) when host Java is >= 24 (Spring Boot 4 backend).
mobile-android-install:
	@SDK=$$(./scripts/android-emulator.sh sdk-path 2>/dev/null || echo "$$ANDROID_HOME"); \
	[ -z "$$SDK" ] && SDK="$$HOME/Android/Sdk"; \
	export ANDROID_HOME="$$SDK"; \
	export ANDROID_SDK_ROOT="$$SDK"; \
	export PATH="$$SDK/platform-tools:$$SDK/cmdline-tools/latest/bin:$$PATH"; \
	mkdir -p apps/mobile/android && echo "sdk.dir=$$SDK" > apps/mobile/android/local.properties; \
	CUR_JAVA_VER=$$(java -version 2>&1 | awk -F '"' '/version/{print $$2}' | cut -d. -f1); \
	if [ "$${CUR_JAVA_VER:-0}" -ge 24 ]; then \
	  for cand in "$$JAVA_17_HOME" "$$JAVA_21_HOME" "$$HOME/.sdkman/candidates/java/17"* "$$HOME/.sdkman/candidates/java/21"* /usr/lib/jvm/java-17* /usr/lib/jvm/java-21*; do \
	    if [ -n "$$cand" ] && [ -d "$$cand" ]; then \
	      export JAVA_HOME="$$cand"; \
	      export PATH="$$JAVA_HOME/bin:$$PATH"; \
	      break; \
	    fi; \
	  done; \
	fi; \
	cd apps/mobile && npx expo run:android --no-bundler

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
