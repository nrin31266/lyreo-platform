#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
# Root env is operator-only; never copy its provider key into app/client env files.
set -a
source apps/core-service/.env
if [[ -f .env ]]; then source .env; fi
set +a
export DATA_MAINTENANCE_INPUT="${DATA_MAINTENANCE_INPUT:-$ROOT/.data/maintenance/pilot.jsonl}"
export DATA_MAINTENANCE_OUTPUT="${DATA_MAINTENANCE_OUTPUT:-$ROOT/.data/maintenance/pilot-results.json}"
./mvnw -q -pl apps/core-service -am -DskipTests -DskipITs install
./mvnw -q -pl apps/core-service spring-boot:run \
  -Dspring-boot.run.main-class=com.lyreo.DataMaintenanceCommand \
  -Dspring-boot.run.arguments="${*}"
