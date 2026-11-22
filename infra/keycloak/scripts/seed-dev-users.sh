#!/usr/bin/env bash
set -euo pipefail

DIR=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=_common.sh
source "$DIR/_common.sh"

"$DIR/wait-for-keycloak.sh"
login_admin

rename_dev_user() (
  local id=$1 username=$2 editable
  editable=$(kcadm get "realms/$REALM" --fields editUsernameAllowed --format csv --noquotes) || exit 1
  case "$editable" in
    false)
      # Restore the realm's original policy even if the one-time rename fails.
      trap 'kcadm update "realms/$REALM" -s editUsernameAllowed=false >/dev/null || exit 1' EXIT
      kcadm update "realms/$REALM" -s editUsernameAllowed=true >/dev/null || exit 1
      ;;
    true) ;;
    *) echo 'Could not determine the current username policy.' >&2; exit 1 ;;
  esac
  kcadm update "users/$id" -r "$REALM" -s "username=$username" >/dev/null
)

create_or_update_user() {
  local username=$1 email=$2 password=$3 role=$4 last_name=$5
  local id previous_id current_email

  if [[ -z "$username" ]]; then
    case "$email" in
      admin@local.test|learner@local.test) username=${email%@local.test} ;;
      *) username=$email ;;
    esac
  fi
  id=$(user_id_by_username "$username") || return
  if [[ -n "$id" ]]; then
    current_email=$(kcadm get "users/$id" -r "$REALM" --fields email --format csv --noquotes) || return
    if [[ "$current_email" != "$email" ]]; then
      echo "Cannot seed $username: its email does not match the configured dev account." >&2
      return 1
    fi
  fi
  if [[ "$username" != "$email" ]]; then
    previous_id=$(user_id_by_username "$email") || return
    if [[ -n "$previous_id" && -n "$id" && "$previous_id" != "$id" ]]; then
      echo "Cannot seed $username: username and previous email login belong to different users." >&2
      return 1
    fi
    if [[ -n "$previous_id" && -z "$id" ]]; then
      rename_dev_user "$previous_id" "$username" || return
    fi
    id=${id:-$previous_id}
  fi
  if [[ -z "$id" ]]; then
    id=$(kcadm create users -r "$REALM" -i \
      -s "username=$username" \
      -s "email=$email" \
      -s enabled=true \
      -s emailVerified=true) || return
  fi

  # Keycloak's default profile requires these fields before issuing tokens.
  kcadm update "users/$id" -r "$REALM" \
    -s firstName=Lyreo \
    -s "lastName=$last_name" \
    -s emailVerified=true >/dev/null || return

  # Local-only deterministic credentials. Production never uses this script for real users.
  kcadm set-password -r "$REALM" --userid "$id" --new-password "$password" >/dev/null || return
  kcadm add-roles -r "$REALM" --uid "$id" --rolename LEARNER >/dev/null || return
  if [[ "$role" == "ADMIN" ]]; then
    kcadm add-roles -r "$REALM" --uid "$id" --rolename ADMIN >/dev/null || return
  fi

  printf '%s' "$id"
}

ADMIN_ID=$(create_or_update_user \
  "${KEYCLOAK_SEED_ADMIN_USERNAME:-}" \
  "${KEYCLOAK_SEED_ADMIN_EMAIL:?KEYCLOAK_SEED_ADMIN_EMAIL required}" \
  "${KEYCLOAK_SEED_ADMIN_PASSWORD:?KEYCLOAK_SEED_ADMIN_PASSWORD required}" \
  ADMIN Admin)

LEARNER_ID=$(create_or_update_user \
  "${KEYCLOAK_SEED_LEARNER_USERNAME:-}" \
  "${KEYCLOAK_SEED_LEARNER_EMAIL:?KEYCLOAK_SEED_LEARNER_EMAIL required}" \
  "${KEYCLOAK_SEED_LEARNER_PASSWORD:?KEYCLOAK_SEED_LEARNER_PASSWORD required}" \
  LEARNER Learner)

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
