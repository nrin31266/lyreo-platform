#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)

copy_if_missing() {
  local src=$1
  local dst=${1%.example}
  if [[ ! -f "$dst" ]]; then
    cp "$src" "$dst"
    echo "created ${dst#$ROOT/}"
  fi
}

for example in \
  "$ROOT/infra/docker/.env.example" \
  "$ROOT/infra/keycloak/.env.example" \
  "$ROOT/apps/core-service/.env.example" \
  "$ROOT/services/ai-service/.env.example" \
  "$ROOT/apps/admin-web/.env.example" \
  "$ROOT/apps/mobile/.env.example" \
  "$ROOT/tools/data-import/.env.example"; do
  copy_if_missing "$example"
done

DOCKER_ENV="$ROOT/infra/docker/.env"
KC_ENV="$ROOT/infra/keycloak/.env"
CORE_ENV="$ROOT/apps/core-service/.env"
AI_ENV="$ROOT/services/ai-service/.env"

get_env() {
  local file=$1 key=$2
  sed -n "s/^${key}=//p" "$file" | tail -n1
}
set_env() {
  local file=$1 key=$2 value=$3
  if grep -q "^${key}=" "$file"; then
    python3 - "$file" "$key" "$value" <<'PY'
from pathlib import Path
import sys
path,key,value=Path(sys.argv[1]),sys.argv[2],sys.argv[3]
lines=path.read_text().splitlines()
out=[]
for line in lines:
    out.append(f"{key}={value}" if line.startswith(key+'=') else line)
path.write_text('\n'.join(out)+'\n')
PY
  else
    printf '\n%s=%s\n' "$key" "$value" >> "$file"
  fi
}

# Generate shared local secrets once and synchronize every consumer. This avoids the
# common failure where Docker Keycloak, bootstrap scripts and Core Service each hold
# a different client secret/token after copying independent .env.example files.
CORE_SECRET=$(get_env "$DOCKER_ENV" LYREO_CORE_CLIENT_SECRET)
if [[ -z "$CORE_SECRET" || "$CORE_SECRET" == "lyreo-local-core-secret-change-me" ]]; then
  CORE_SECRET=$(openssl rand -hex 32)
  set_env "$DOCKER_ENV" LYREO_CORE_CLIENT_SECRET "$CORE_SECRET"
fi
set_env "$KC_ENV" LYREO_CORE_CLIENT_SECRET "$CORE_SECRET"
set_env "$CORE_ENV" KEYCLOAK_CORE_CLIENT_SECRET "$CORE_SECRET"

KC_ADMIN=$(get_env "$DOCKER_ENV" KEYCLOAK_ADMIN)
KC_PASSWORD=$(get_env "$DOCKER_ENV" KEYCLOAK_ADMIN_PASSWORD)
set_env "$KC_ENV" KEYCLOAK_ADMIN "${KC_ADMIN:-admin}"
set_env "$KC_ENV" KEYCLOAK_ADMIN_PASSWORD "${KC_PASSWORD:-admin-local-change-me}"

DB_NAME=$(get_env "$DOCKER_ENV" POSTGRES_DB)
DB_USER=$(get_env "$DOCKER_ENV" POSTGRES_USER)
DB_PASSWORD=$(get_env "$DOCKER_ENV" POSTGRES_PASSWORD)
DB_PORT=$(get_env "$DOCKER_ENV" POSTGRES_PORT)
set_env "$CORE_ENV" DATABASE_URL "jdbc:postgresql://localhost:${DB_PORT:-5432}/${DB_NAME:-lyreo_dev}"
set_env "$CORE_ENV" DATABASE_USERNAME "${DB_USER:-lyreo}"
set_env "$CORE_ENV" DATABASE_PASSWORD "${DB_PASSWORD:-lyreo_dev_password}"

if [[ -z "$(get_env "$CORE_ENV" MASTER_ENCRYPTION_KEY)" ]]; then
  set_env "$CORE_ENV" MASTER_ENCRYPTION_KEY "$(openssl rand -base64 32 | tr -d '\n')"
  echo 'generated MASTER_ENCRYPTION_KEY'
fi

AI_TOKEN=$(get_env "$CORE_ENV" AI_SERVICE_INTERNAL_TOKEN)
if [[ -z "$AI_TOKEN" || "$AI_TOKEN" == "change-me" ]]; then
  AI_TOKEN=$(openssl rand -hex 32)
fi
set_env "$CORE_ENV" AI_SERVICE_INTERNAL_TOKEN "$AI_TOKEN"
set_env "$AI_ENV" AI_SERVICE_INTERNAL_TOKEN "$AI_TOKEN"

BOOTSTRAP_TOKEN=$(get_env "$CORE_ENV" DEV_BOOTSTRAP_TOKEN)
if [[ -z "$BOOTSTRAP_TOKEN" || "$BOOTSTRAP_TOKEN" == "lyreo-dev-bootstrap-token-change-me" ]]; then
  BOOTSTRAP_TOKEN=$(openssl rand -hex 24)
fi
set_env "$CORE_ENV" DEV_BOOTSTRAP_TOKEN "$BOOTSTRAP_TOKEN"
set_env "$KC_ENV" DEV_BOOTSTRAP_TOKEN "$BOOTSTRAP_TOKEN"

echo 'Local environment files synchronized.'
echo 'Review R2_* values before running storage-dependent flows.'
