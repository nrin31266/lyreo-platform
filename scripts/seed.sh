#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

CORE_ENV="$ROOT/apps/core-service/.env"
if [[ ! -f "$CORE_ENV" ]]; then
  echo "Missing apps/core-service/.env. Run 'make init-env' first." >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$CORE_ENV"
set +a

LESSON_SEED_FILE="${LESSON_SEED_FILE:-$ROOT/infra/postgres/seed/lessons.sql}"

run_seed() {
  local seed_file=$1
  ./mvnw -B -pl apps/core-service spring-boot:run \
    -Dspring-boot.run.main-class=com.lyreo.platform.bootstrap.DatabaseSetup \
    -Dspring-boot.run.arguments="--seed-dev \"$seed_file\""
}

run_seed "$ROOT/infra/postgres/seed/dev.sql"
run_seed "$LESSON_SEED_FILE"
exec "$ROOT/scripts/seed-demo.sh"
