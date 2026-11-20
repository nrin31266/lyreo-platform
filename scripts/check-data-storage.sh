#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)

storage_identity() (
  cd "$ROOT/$1"
  [[ -f .env ]] || { echo "Missing $1/.env; run make init-env." >&2; exit 1; }
  set -a
  source ./.env
  set +a
  local mode=${STORAGE_MODE:-$2}
  case "$mode" in
    local)
      [[ -n "${LOCAL_STORAGE_ROOT:-}" ]] || { echo 'LOCAL_STORAGE_ROOT is required.' >&2; exit 1; }
      python3 - "$LOCAL_STORAGE_ROOT" <<'PY'
from pathlib import Path
import sys
print('local:' + str(Path(sys.argv[1]).resolve()))
PY
      ;;
    r2)
      [[ -n "${R2_ENDPOINT:-}" && -n "${R2_BUCKET:-}" && -n "${R2_ACCESS_KEY_ID:-}" && -n "${R2_SECRET_ACCESS_KEY:-}" ]] || {
        echo "R2 credentials and bucket are required in $1/.env." >&2; exit 1;
      }
      printf 'r2:%s:%s\n' "${R2_ENDPOINT%/}" "$R2_BUCKET"
      ;;
    *) echo 'STORAGE_MODE must be local or r2.' >&2; exit 1 ;;
  esac
)

case "${1:-}" in
  --database-only) ;;
  '')
    core_storage=$(storage_identity apps/core-service local)
    data_storage=$(storage_identity tools/data-import r2)
    [[ "$core_storage" == "$data_storage" ]] || {
      echo 'Core and importer target different storage. Align STORAGE_MODE and the local root or R2 endpoint/bucket in both .env files before importing.' >&2
      exit 1
    }
    echo "Core and importer storage match (${core_storage%%:*}); existing media will be verified and reused."
    ;;
  *) echo 'Usage: check-data-storage.sh [--database-only]' >&2; exit 2 ;;
esac

database_identity() (
  cd "$ROOT/$1"
  [[ -f .env ]] || { echo "Missing $1/.env; run make init-env." >&2; exit 1; }
  set -a
  source ./.env
  set +a
  python3 - <<'PY'
import os
from urllib.parse import urlsplit, parse_qs
url = os.getenv('DATABASE_URL', '').removeprefix('jdbc:')
try:
    parsed = urlsplit(url)
    if parsed.scheme not in {'postgres', 'postgresql'} or not parsed.hostname or not parsed.path.strip('/'):
        raise ValueError()
    host = parsed.hostname.lower()
    if host in {'localhost', '127.0.0.1', '::1'}:
        host = 'loopback'
    schema = parse_qs(parsed.query).get('currentSchema', ['public'])[0]
    print(f'{host}:{parsed.port or 5432}:{parsed.path}:{schema}')
except ValueError:
    raise SystemExit('A valid PostgreSQL DATABASE_URL is required in both owner .env files.')
PY
)
core_database=$(database_identity apps/core-service)
data_database=$(database_identity tools/data-import)
[[ "$core_database" == "$data_database" ]] || {
  echo 'Core and importer target different databases. Align DATABASE_URL before importing.' >&2
  exit 1
}
echo 'Core and importer database targets match.'
