#!/usr/bin/env bash
set -euo pipefail

# Shared helper for local Keycloak bootstrap scripts.
# These scripts operate against the running container so every developer gets the same realm/client/role setup.
KEYCLOAK_CONTAINER=${KEYCLOAK_CONTAINER:-lyreo-keycloak}
REALM=${KEYCLOAK_REALM:-lyreo}
KCADM="/opt/keycloak/bin/kcadm.sh"

kcadm() {
  docker exec "$KEYCLOAK_CONTAINER" "$KCADM" "$@"
}

login_admin() {
  kcadm config credentials \
    --server http://localhost:8080 \
    --realm master \
    --user "${KEYCLOAK_ADMIN:-admin}" \
    --password "${KEYCLOAK_ADMIN_PASSWORD:?KEYCLOAK_ADMIN_PASSWORD required}" \
    >/dev/null
}

user_id_by_username() {
  local username=$1
  kcadm get users -r "$REALM" -q "username=$username" --fields id,username 2>/dev/null \
    | python3 -c '
import json, sys
username = sys.argv[1]
rows = json.load(sys.stdin)
print(next((row["id"] for row in rows if row.get("username") == username), ""))
' "$username"
}
