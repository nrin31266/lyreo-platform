#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
IMPORT_DIR="$ROOT/tools/data-import"

if [[ ! -f "$IMPORT_DIR/.env" ]]; then
  echo "Missing tools/data-import/.env. Run 'make init-env' first." >&2
  exit 1
fi

cd "$IMPORT_DIR"
set -a
# shellcheck disable=SC1091
source ./.env
set +a

ARGS=()
while (($#)); do
  if [[ "$1" == "--release" && $# -ge 2 ]]; then
    RELEASE_PATH="$2"
    if [[ "$RELEASE_PATH" != /* && ! -d "$RELEASE_PATH" && -d "$ROOT/$RELEASE_PATH" ]]; then
      RELEASE_PATH="$ROOT/$RELEASE_PATH"
    fi
    ARGS+=(--release "$RELEASE_PATH")
    shift 2
  else
    ARGS+=("$1")
    shift
  fi
done

exec uv run --locked --extra dev python import_clean_lexicon.py "${ARGS[@]}"
