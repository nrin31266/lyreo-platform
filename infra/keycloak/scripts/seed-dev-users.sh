#!/usr/bin/env bash
set -euo pipefail

DIR=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=_common.sh
source "$DIR/_common.sh"

"$DIR/wait-for-keycloak.sh"
login_admin

create_or_update_user() {
  local email=$1
  local password=$2
  local role=$3
  local id

  id=$(user_id_by_username "$email" || true)
  if [[ -z "$id" ]]; then
    kcadm create users -r "$REALM" \
      -s "username=$email" \
      -s "email=$email" \
      -s enabled=true \
      -s emailVerified=true \
      >/dev/null
    id=$(user_id_by_username "$email")
  fi

  # Local-only deterministic credentials. Production never uses this script for real users.
  kcadm set-password -r "$REALM" --userid "$id" --new-password "$password" >/dev/null
  kcadm add-roles -r "$REALM" --uid "$id" --rolename LEARNER >/dev/null 2>&1 || true
  if [[ "$role" == "ADMIN" ]]; then
    kcadm add-roles -r "$REALM" --uid "$id" --rolename ADMIN >/dev/null 2>&1 || true
  fi

  printf '%s' "$id"
}

ADMIN_ID=$(create_or_update_user \
  "${KEYCLOAK_SEED_ADMIN_EMAIL:?KEYCLOAK_SEED_ADMIN_EMAIL required}" \
  "${KEYCLOAK_SEED_ADMIN_PASSWORD:?KEYCLOAK_SEED_ADMIN_PASSWORD required}" \
  ADMIN)

LEARNER_ID=$(create_or_update_user \
  "${KEYCLOAK_SEED_LEARNER_EMAIL:?KEYCLOAK_SEED_LEARNER_EMAIL required}" \
  "${KEYCLOAK_SEED_LEARNER_PASSWORD:?KEYCLOAK_SEED_LEARNER_PASSWORD required}" \
  LEARNER)

echo "Seeded Keycloak dev users: admin=$ADMIN_ID learner=$LEARNER_ID"

# Optional convenience: if Core is already running with Spring `dev` profile, mirror Keycloak
# subjects into app_user. Failure is non-fatal because production uses JIT provisioning on login.
if [[ -n "${DEV_BOOTSTRAP_TOKEN:-}" ]]; then
  BODY=$(printf \
    '[{"subject":"%s","email":"%s"},{"subject":"%s","email":"%s"}]' \
    "$ADMIN_ID" "$KEYCLOAK_SEED_ADMIN_EMAIL" "$LEARNER_ID" "$KEYCLOAK_SEED_LEARNER_EMAIL")

  if ! curl -fsS \
    -X POST "${CORE_API_BASE_URL:-http://localhost:8080}/internal/dev/bootstrap/users" \
    -H 'Content-Type: application/json' \
    -H "X-Lyreo-Dev-Bootstrap-Token: $DEV_BOOTSTRAP_TOKEN" \
    -d "$BODY" \
    >/dev/null; then
    echo "Core dev sync skipped (Core not running or dev bootstrap endpoint disabled)."
  fi
fi
