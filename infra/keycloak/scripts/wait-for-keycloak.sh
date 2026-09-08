#!/usr/bin/env bash
set -euo pipefail
URL=${KEYCLOAK_URL:-http://localhost:8081}
for _ in $(seq 1 90); do
  if curl -fsS "$URL/realms/master/.well-known/openid-configuration" >/dev/null 2>&1; then echo "Keycloak ready: $URL"; exit 0; fi
  sleep 2
done
echo "Keycloak did not become ready" >&2; exit 1
