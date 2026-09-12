#!/usr/bin/env bash
set -euo pipefail

DIR=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=_common.sh
source "$DIR/_common.sh"

"$DIR/wait-for-keycloak.sh"
login_admin

# Realm import already creates these objects. We verify/create roles idempotently so a partially
# initialized local realm can be repaired without clicking around the Keycloak UI.
for role in ADMIN LEARNER; do
  if ! kcadm get "roles/$role" -r "$REALM" >/dev/null 2>&1; then
    kcadm create roles -r "$REALM" -s "name=$role" >/dev/null
  fi
done

# The realm JSON contains topology. The real local confidential secret comes from env and is
# synchronized with Core Service by scripts/init-dev-env.sh.
CORE_CLIENT_UUID=$(
  kcadm get clients -r "$REALM" -q clientId=lyreo-core-service --fields id --format csv --noquotes \
    | head -n1 || true
)
if [[ -n "$CORE_CLIENT_UUID" && -n "${LYREO_CORE_CLIENT_SECRET:-}" ]]; then
  kcadm update "clients/$CORE_CLIENT_UUID" -r "$REALM" -s "secret=$LYREO_CORE_CLIENT_SECRET" >/dev/null
fi

# Keep the installed development realm aligned with the exact native callback used by AuthSession.
# Realm import only runs on first initialization, so existing developer databases need reconciliation.
MOBILE_CLIENT_UUID=$(
  kcadm get clients -r "$REALM" -q clientId=lyreo-mobile --fields id --format csv --noquotes \
    | head -n1 || true
)
if [[ -z "$MOBILE_CLIENT_UUID" ]]; then
  echo "ERROR: lyreo-mobile client is missing from realm=$REALM" >&2
  exit 1
fi
kcadm update "clients/$MOBILE_CLIENT_UUID" -r "$REALM" \
  -s 'redirectUris=["lyreo://auth/callback"]' \
  -s 'attributes={"pkce.code.challenge.method":"S256","post.logout.redirect.uris":"lyreo://auth/callback"}' \
  >/dev/null

echo "Lyreo Keycloak topology verified: realm=$REALM roles=ADMIN,LEARNER mobileRedirect=lyreo://auth/callback"
