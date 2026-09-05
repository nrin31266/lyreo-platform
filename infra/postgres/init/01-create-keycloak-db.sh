#!/usr/bin/env bash
set -euo pipefail

# Runs only on first initialization of the PostgreSQL volume.
# Keep Keycloak persistence separate from Lyreo business tables while sharing one dev DB server.
KEYCLOAK_DB=${KEYCLOAK_DB:-lyreo_keycloak}
if ! psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  -tAc "SELECT 1 FROM pg_database WHERE datname='${KEYCLOAK_DB}'" | grep -q 1; then
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
    -c "CREATE DATABASE \"${KEYCLOAK_DB}\" OWNER \"${POSTGRES_USER}\";"
fi
