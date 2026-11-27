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

audio_seed=$(mktemp "${TMPDIR:-/tmp}/lyreo-lesson-audio.XXXXXX.sql")
trap 'rm -f "$audio_seed"' EXIT
python_bin=python3
if [[ "${STORAGE_MODE:-local}" == "r2" && -x "$ROOT/tools/data-import/.venv/bin/python" ]]; then
  python_bin="$ROOT/tools/data-import/.venv/bin/python"
fi
PYTHONPATH="$ROOT/tools/data-import" "$python_bin" \
  "$ROOT/tools/data-import/scripts/prepare_lesson_seed_audio.py" "$LESSON_SEED_FILE" > "$audio_seed"

run_seed "$ROOT/infra/postgres/seed/dev.sql"
if [[ -s "$audio_seed" ]]; then
  run_seed "$audio_seed"
fi
run_seed "$LESSON_SEED_FILE"
"$ROOT/scripts/seed-demo.sh"
