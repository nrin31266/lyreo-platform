#!/usr/bin/env bash
set -euo pipefail

IMPORT_DIR=$(cd "$(dirname "$0")/.." && pwd)
args=()
while (($#)); do
  if [[ "$1" == "--release" && $# -ge 2 ]]; then
    release="$2"
    [[ "$release" == /* ]] || release="$PWD/$release"
    args+=(--release "$release")
    shift 2
  else
    args+=("$1")
    shift
  fi
done

cd "$IMPORT_DIR"
set -a
# shellcheck disable=SC1091
source ./.env
set +a
exec uv run --locked python import_clean_vocabulary.py "${args[@]}"
