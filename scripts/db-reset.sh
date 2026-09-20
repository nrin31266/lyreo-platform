#!/usr/bin/env bash
set -euo pipefail

# Development-only database reset for Lyreo application database.
# Resets ONLY the Lyreo app development database (default: lyreo_dev).
# Does NOT touch Keycloak database (lyreo_keycloak) or Docker volumes.

ENV_FILE="infra/docker/.env"
if [[ -f "$ENV_FILE" ]]; then
  POSTGRES_DB=$(grep -E '^POSTGRES_DB=' "$ENV_FILE" | cut -d= -f2- | tr -d ' "\r' || true)
  POSTGRES_USER=$(grep -E '^POSTGRES_USER=' "$ENV_FILE" | cut -d= -f2- | tr -d ' "\r' || true)
  KEYCLOAK_DB=$(grep -E '^KEYCLOAK_DB=' "$ENV_FILE" | cut -d= -f2- | tr -d ' "\r' || true)
fi

TARGET_DB="${POSTGRES_DB:-lyreo_dev}"
TARGET_USER="${POSTGRES_USER:-lyreo}"
KEYCLOAK_DB_NAME="${KEYCLOAK_DB:-lyreo_keycloak}"

# Safety checks: explicitly reject empty, system, or Keycloak database targets
if [[ -z "$TARGET_DB" ]]; then
  echo "ERROR: Target database name cannot be empty." >&2
  exit 1
fi

case "$TARGET_DB" in
  postgres|template0|template1|"$KEYCLOAK_DB_NAME"|lyreo_keycloak)
    echo "ERROR: Refusing to reset unsafe, system, or Keycloak database: '$TARGET_DB'" >&2
    exit 1
    ;;
esac

echo "Target application database to reset: '$TARGET_DB' (owner: '$TARGET_USER')"

# Verify PostgreSQL container is running
if ! docker compose --env-file infra/docker/.env -f compose.dev.yml ps --status running --format '{{.Service}}' | grep -q '^postgres$'; then
  echo "ERROR: PostgreSQL container is not running. Start it with 'make dev-infra' first." >&2
  exit 1
fi

echo "Terminating active connections to '$TARGET_DB'..."
docker compose --env-file infra/docker/.env -f compose.dev.yml exec -T postgres psql -U "$TARGET_USER" -d postgres -c \
  "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$TARGET_DB' AND pid <> pg_backend_pid();" >/dev/null

echo "Dropping database '$TARGET_DB'..."
docker compose --env-file infra/docker/.env -f compose.dev.yml exec -T postgres psql -U "$TARGET_USER" -d postgres -c \
  "DROP DATABASE IF EXISTS \"$TARGET_DB\";" >/dev/null

echo "Recreating database '$TARGET_DB' with owner '$TARGET_USER'..."
docker compose --env-file infra/docker/.env -f compose.dev.yml exec -T postgres psql -U "$TARGET_USER" -d postgres -c \
  "CREATE DATABASE \"$TARGET_DB\" OWNER \"$TARGET_USER\";" >/dev/null

echo "Database '$TARGET_DB' reset successfully."
echo "Run 'make core' to apply Flyway migrations (V001..V003)."
