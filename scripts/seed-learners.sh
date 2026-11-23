#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
ids=$(
  set -a
  source infra/keycloak/.env
  set +a
  bash infra/keycloak/scripts/seed-dev-users.sh --demo-learners
)
mapfile -t subjects <<< "$ids"
[[ ${#subjects[@]} == 3 ]]
for subject in "${subjects[@]}"; do
  [[ $subject =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]]
done
seed=$(mktemp)
trap 'rm -f "$seed"' EXIT
sed -e "s/__MINH_ANH_SUBJECT__/${subjects[0]}/g" \
    -e "s/__GIA_HUY_SUBJECT__/${subjects[1]}/g" \
    -e "s/__NEW_LEARNER_SUBJECT__/${subjects[2]}/g" infra/postgres/seed/learners.sql > "$seed"
set -a
source apps/core-service/.env
set +a
./mvnw -B -pl apps/core-service spring-boot:run \
  -Dspring-boot.run.main-class=com.lyreo.platform.bootstrap.DatabaseSetup \
  -Dspring-boot.run.arguments="--seed-dev \"$seed\""
