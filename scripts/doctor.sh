#!/usr/bin/env bash
set -euo pipefail

STRICT=false
[[ "${1:-}" == "--strict" ]] && STRICT=true

failures=0
warnings=0

ok() { printf 'OK   %s\n' "$1"; }
warn() { printf 'WARN %s\n' "$1"; warnings=$((warnings+1)); }
fail() { printf 'FAIL %s\n' "$1"; failures=$((failures+1)); }

version_major() {
  printf '%s' "$1" | sed -E 's/^[^0-9]*([0-9]+).*/\1/'
}

check_command() {
  local command=$1 label=$2
  if command -v "$command" >/dev/null 2>&1; then ok "$label: $(command -v "$command")"; else warn "$label not found"; fi
}

echo 'Lyreo developer environment doctor'
echo '----------------------------------'

if command -v java >/dev/null 2>&1; then
  java_line=$(java -version 2>&1 | head -n1)
  java_major=$(version_major "$java_line")
  if [[ "$java_major" =~ ^[0-9]+$ ]] && (( java_major >= 25 )); then ok "$java_line"; else warn "$java_line (repo target Java 25)"; fi
else
  warn 'Java not found (repo target 25)'
fi

if command -v node >/dev/null 2>&1; then
  node_version=$(node -v)
  node_major=$(version_major "$node_version")
  if [[ "$node_major" =~ ^[0-9]+$ ]] && (( node_major >= 24 )); then ok "Node $node_version"; else warn "Node $node_version (repo target 24 LTS)"; fi
else
  warn 'Node not found (repo target 24 LTS)'
fi

if command -v pnpm >/dev/null 2>&1; then ok "pnpm $(pnpm -v)"; else warn 'pnpm not found (use Corepack; repo target 12)'; fi
if command -v python3 >/dev/null 2>&1; then ok "$(python3 --version)"; else fail 'python3 not found'; fi
check_command uv 'uv'
check_command docker 'Docker'

for path in \
  infra/docker/.env \
  infra/keycloak/.env \
  apps/core-service/.env \
  apps/ai-service/.env \
  apps/admin-web/.env \
  apps/mobile/.env \
  tools/data-import/.env; do
  if [[ -f "$path" ]]; then ok "$path exists"; else warn "$path missing (run ./scripts/init-dev-env.sh)"; fi
done

if [[ -f pnpm-lock.yaml ]]; then
  if grep -q '^  apps/admin-web:' pnpm-lock.yaml \
    && grep -q '^  apps/mobile:' pnpm-lock.yaml \
    && grep -q '^  packages/design-system:' pnpm-lock.yaml \
    && grep -q '^  packages/i18n:' pnpm-lock.yaml; then
    ok 'pnpm-lock.yaml contains all workspace importers'
  else
    warn 'pnpm-lock.yaml exists but is stale/incomplete for the current workspace; regenerate with pnpm install'
  fi
else
  warn 'pnpm-lock.yaml missing; generate/commit after first networked pnpm install'
fi

# The large Grammar/TOEIC dataset is not required to boot Core, but importer/TOEIC work should be
# immediately actionable on a fresh clone when a shared archive URL is configured.
if [[ -f tools/data-import/.env ]]; then
  if ./scripts/fetch-data.sh --check >/dev/null 2>&1; then
    ok 'Grammar/TOEIC dataset is present'
  else
    DATA_URL=$(
      cd tools/data-import
      set -a
      # shellcheck disable=SC1091
      source ./.env
      set +a
      printf '%s' "${DAUTOEIC_DATA_URL:-}"
    )
    if [[ -n "$DATA_URL" ]]; then
      warn 'Grammar/TOEIC dataset missing (run make data-fetch)'
      if [[ "$DATA_URL" == https://drive.google.com/* ]] && ! command -v uvx >/dev/null 2>&1; then
        warn 'Google Drive dataset URL configured but uvx is unavailable (uv normally provides it)'
      fi
    else
      warn 'Grammar/TOEIC dataset missing; DAUTOEIC_DATA_URL is not configured (optional for app boot)'
    fi
  fi
fi

if $STRICT && (( warnings > 0 )); then failures=$((failures+warnings)); fi

printf '\nSummary: failures=%d warnings=%d\n' "$failures" "$warnings"
(( failures == 0 ))
