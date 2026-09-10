#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)

# Copy templates only when the developer has not created a local file yet.
copy_if_missing() {
  local src=$1
  local dst=${1%.example}
  if [[ ! -f "$dst" ]]; then
    cp "$src" "$dst"
    echo "created ${dst#$ROOT/}"
  fi
}

# Keep runtime/tool secrets scoped to the executable that owns them; there is no root .env.
for example in \
  "$ROOT/infra/docker/.env.example" \
  "$ROOT/infra/keycloak/.env.example" \
  "$ROOT/apps/core-service/.env.example" \
  "$ROOT/apps/ai-service/.env.example" \
  "$ROOT/apps/admin-web/.env.example" \
  "$ROOT/apps/mobile/.env.example" \
  "$ROOT/tools/data-import/.env.example"; do
  copy_if_missing "$example"
done

DOCKER_ENV="$ROOT/infra/docker/.env"
KC_ENV="$ROOT/infra/keycloak/.env"
CORE_ENV="$ROOT/apps/core-service/.env"
AI_ENV="$ROOT/apps/ai-service/.env"
DATA_ENV="$ROOT/tools/data-import/.env"

get_env() {
  local file=$1 key=$2
  sed -n "s/^${key}=//p" "$file" | tail -n1
}

# Use Python instead of sed replacement so base64/slashes/equals signs are never interpreted.
set_env() {
  local file=$1 key=$2 value=$3
  if grep -q "^${key}=" "$file"; then
    python3 - "$file" "$key" "$value" <<'PY'
from pathlib import Path
import sys
path, key, value = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
lines = path.read_text().splitlines()
out = [f"{key}={value}" if line.startswith(key + '=') else line for line in lines]
path.write_text('\n'.join(out) + '\n')
PY
  else
    printf '\n%s=%s\n' "$key" "$value" >> "$file"
  fi
}

# Keep the future `lyreo-core-service` confidential client secret identical across the
# Docker/Keycloak bootstrap owners. Core runtime does not consume it until an Admin API client
# is implemented.
CORE_SECRET=$(get_env "$DOCKER_ENV" LYREO_CORE_CLIENT_SECRET)
if [[ -z "$CORE_SECRET" || "$CORE_SECRET" == "lyreo-local-core-secret-change-me" ]]; then
  CORE_SECRET=$(openssl rand -hex 32)
  set_env "$DOCKER_ENV" LYREO_CORE_CLIENT_SECRET "$CORE_SECRET"
fi
set_env "$KC_ENV" LYREO_CORE_CLIENT_SECRET "$CORE_SECRET"

# Keep Keycloak admin credentials aligned between the container and bootstrap scripts.
KC_ADMIN=$(get_env "$DOCKER_ENV" KEYCLOAK_ADMIN)
KC_PASSWORD=$(get_env "$DOCKER_ENV" KEYCLOAK_ADMIN_PASSWORD)
set_env "$KC_ENV" KEYCLOAK_ADMIN "${KC_ADMIN:-admin}"
set_env "$KC_ENV" KEYCLOAK_ADMIN_PASSWORD "${KC_PASSWORD:-admin-local-change-me}"

# Resolve local PostgreSQL connection settings from the compose environment so custom host ports work.
DB_NAME=$(get_env "$DOCKER_ENV" POSTGRES_DB)
DB_USER=$(get_env "$DOCKER_ENV" POSTGRES_USER)
DB_PASSWORD=$(get_env "$DOCKER_ENV" POSTGRES_PASSWORD)
DB_PORT=$(get_env "$DOCKER_ENV" POSTGRES_PORT)
DB_NAME=${DB_NAME:-lyreo_dev}
DB_USER=${DB_USER:-lyreo}
DB_PASSWORD=${DB_PASSWORD:-lyreo_dev_password}
DB_PORT=${DB_PORT:-5432}
set_env "$CORE_ENV" DATABASE_URL "jdbc:postgresql://localhost:${DB_PORT}/${DB_NAME}"
set_env "$CORE_ENV" DATABASE_USERNAME "$DB_USER"
set_env "$CORE_ENV" DATABASE_PASSWORD "$DB_PASSWORD"
set_env "$DATA_ENV" DATABASE_URL "postgresql://${DB_USER}:${DB_PASSWORD}@localhost:${DB_PORT}/${DB_NAME}"

# AES-GCM provider credentials need a local 256-bit master key. Never copy it into frontend env.
if [[ -z "$(get_env "$CORE_ENV" MASTER_ENCRYPTION_KEY)" ]]; then
  set_env "$CORE_ENV" MASTER_ENCRYPTION_KEY "$(openssl rand -base64 32 | tr -d '\n')"
  echo 'generated MASTER_ENCRYPTION_KEY'
fi

# Protect the private Core -> FastAPI capability boundary with one shared local token.
AI_TOKEN=$(get_env "$CORE_ENV" AI_SERVICE_INTERNAL_TOKEN)
if [[ -z "$AI_TOKEN" || "$AI_TOKEN" == "change-me" ]]; then
  AI_TOKEN=$(openssl rand -hex 32)
fi
set_env "$CORE_ENV" AI_SERVICE_INTERNAL_TOKEN "$AI_TOKEN"
set_env "$AI_ENV" AI_SERVICE_INTERNAL_TOKEN "$AI_TOKEN"

# Dev-only bootstrap endpoints use a separate token so the AI service credential is not reused.
BOOTSTRAP_TOKEN=$(get_env "$CORE_ENV" DEV_BOOTSTRAP_TOKEN)
if [[ -z "$BOOTSTRAP_TOKEN" || "$BOOTSTRAP_TOKEN" == "lyreo-dev-bootstrap-token-change-me" ]]; then
  BOOTSTRAP_TOKEN=$(openssl rand -hex 24)
fi
set_env "$CORE_ENV" DEV_BOOTSTRAP_TOKEN "$BOOTSTRAP_TOKEN"
set_env "$KC_ENV" DEV_BOOTSTRAP_TOKEN "$BOOTSTRAP_TOKEN"

echo 'Local environment files synchronized.'
echo 'Local object storage is the default; R2_* values are only needed for R2 integration flows.'

if [[ -f "$DATA_ENV" ]]; then
  DATA_URL=$(get_env "$DATA_ENV" DAUTOEIC_DATA_URL)
  if [[ -n "$DATA_URL" ]]; then
    echo 'Grammar/TOEIC shared dataset URL configured; run make data-fetch (or make setup).'
  else
    echo 'Grammar/TOEIC dataset URL is not configured; app development can continue, importer/data work needs a local dataset.'
  fi
fi
