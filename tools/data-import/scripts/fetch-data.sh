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

exec uv run --locked --extra dev python scripts/fetch_release.py "$@"
