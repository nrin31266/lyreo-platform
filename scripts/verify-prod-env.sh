#!/usr/bin/env bash
set -euo pipefail

# Fail-fast helper before starting compose.prod.yml. It never prints secret values.
required_files=(
  infra/docker/.env
  apps/core-service/.env
  apps/ai-service/.env
)

for file in "${required_files[@]}"; do
  [[ -f "$file" ]] || { echo "Missing $file" >&2; exit 1; }
done

get_env() {
  local file=$1 key=$2
  sed -n "s/^${key}=//p" "$file" | tail -n1
}

require_non_placeholder() {
  local file=$1 key=$2
  local value
  value=$(get_env "$file" "$key")
  if [[ -z "$value" || "$value" == *change-me* || "$value" == replace-* ]]; then
    echo "Unsafe/missing $key in $file" >&2
    return 1
  fi
}

require_non_placeholder infra/docker/.env POSTGRES_PASSWORD
require_non_placeholder infra/docker/.env KEYCLOAK_ADMIN_PASSWORD
require_non_placeholder infra/docker/.env LYREO_CORE_CLIENT_SECRET
require_non_placeholder apps/core-service/.env MASTER_ENCRYPTION_KEY
require_non_placeholder apps/core-service/.env AI_SERVICE_INTERNAL_TOKEN
require_non_placeholder apps/ai-service/.env AI_SERVICE_INTERNAL_TOKEN

core_ai_token=$(get_env apps/core-service/.env AI_SERVICE_INTERNAL_TOKEN)
ai_service_token=$(get_env apps/ai-service/.env AI_SERVICE_INTERNAL_TOKEN)
if [[ "$core_ai_token" != "$ai_service_token" ]]; then
  echo 'ERROR: Core and AI internal service tokens do not match.' >&2
  exit 1
fi

if [[ "$(get_env apps/core-service/.env SPRING_PROFILES_ACTIVE)" == "dev" ]]; then
  echo 'Production Core env must not use SPRING_PROFILES_ACTIVE=dev' >&2
  exit 1
fi

echo 'Production env basic validation passed. This does not replace secret-manager/TLS/security review.'
