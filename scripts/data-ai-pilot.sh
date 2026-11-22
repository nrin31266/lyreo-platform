#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
# Use explicit operator credentials or optional research settings.
set -a
source apps/core-service/.env
if [[ -f tools/research/.env ]]; then
  source tools/research/.env
fi
set +a
export AI_SERVICE_BASE_URL="${DATA_MAINTENANCE_AI_URL:-$AI_SERVICE_BASE_URL}"
export DATA_MAINTENANCE_INPUT="${DATA_MAINTENANCE_INPUT:-$ROOT/.data/maintenance/pilot.jsonl}"
export DATA_MAINTENANCE_OUTPUT="${DATA_MAINTENANCE_OUTPUT:-$ROOT/.data/maintenance/pilot-results.json}"
./mvnw -q -pl apps/core-service -am -DskipTests -DskipITs install
./mvnw -q -pl apps/core-service spring-boot:run \
  -Dspring-boot.run.main-class=com.lyreo.DataMaintenanceCommand \
  -Dspring-boot.run.arguments="${*}"
