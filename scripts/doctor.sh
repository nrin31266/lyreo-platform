#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"
strict=false
[[ "${1:-}" == "--strict" ]] && strict=true
required_missing=0

required() {
  local binary=$1 label=$2
  if command -v "$binary" >/dev/null 2>&1; then
    printf 'OK   %s: %s\n' "$label" "$(command -v "$binary")"
  else
    printf 'WARN %s is missing\n' "$label"
    required_missing=$((required_missing+1))
  fi
}
optional() {
  local binary=$1 label=$2
  if command -v "$binary" >/dev/null 2>&1; then
    printf 'OK   %s: %s\n' "$label" "$(command -v "$binary")"
  else
    printf 'INFO %s is not installed\n' "$label"
  fi
}
file_status() {
  local file=$1
  if [[ -f "$file" ]]; then printf 'OK   %s\n' "$file"; else printf 'INFO %s missing (make init-env)\n' "$file"; fi
}

echo 'REQUIRED CORE / REPOSITORY'
required java Java
required python3 Python
required docker Docker
required uv uv
if command -v java >/dev/null 2>&1; then
  expected_java=$(cat .java-version)
  actual_java=$(java -version 2>&1 | head -1)
  [[ "$actual_java" == *\"$expected_java.* ]] || { printf 'WARN Java differs from .java-version (%s): %s\n' "$expected_java" "$actual_java"; required_missing=$((required_missing+1)); }
fi
file_status infra/docker/.env
file_status infra/keycloak/.env
file_status apps/core-service/.env

echo 'FRONTEND'
required node Node
required pnpm pnpm
if command -v node >/dev/null 2>&1; then
  expected_node=$(cat .nvmrc)
  actual_node=$(node -v)
  [[ "$actual_node" == "v$expected_node" ]] || printf 'INFO Node %s; .nvmrc requests %s\n' "$actual_node" "$expected_node"
fi
if command -v pnpm >/dev/null 2>&1; then
  expected_pnpm=$(sed -nE 's/.*"packageManager": "pnpm@([^"]+)".*/\1/p' package.json)
  actual_pnpm=$(pnpm -v)
  [[ "$actual_pnpm" == "$expected_pnpm" ]] || printf 'INFO pnpm %s; packageManager requests %s\n' "$actual_pnpm" "$expected_pnpm"
fi
file_status apps/admin-web/.env

echo 'OPTIONAL AI'
file_status apps/ai-service/.env

echo 'OPTIONAL LESSON PREP / MEDIA'
optional ffmpeg ffmpeg
optional ffprobe ffprobe
file_status tools/lesson-prep/.env

echo 'OPTIONAL DATA IMPORT'
file_status tools/data-import/.env
if [[ -f tools/data-import/.env ]]; then
  if [[ ! -x tools/data-import/.venv/bin/python ]]; then
    echo 'INFO Grammar/TOEIC release check needs data-import dependencies (make deps)'
  elif ./tools/data-import/scripts/fetch-data.sh --check >/dev/null 2>&1; then
    echo 'OK   Grammar/TOEIC clean release ready'
  else
    echo 'INFO Grammar/TOEIC clean release absent or invalid (make data-fetch)'
  fi
fi

echo 'OPTIONAL MOBILE'
optional adb adb
file_status apps/mobile/.env
printf 'Core toolchain findings: %d\n' "$required_missing"
if $strict && (( required_missing > 0 )); then exit 1; fi
