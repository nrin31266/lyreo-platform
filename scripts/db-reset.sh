#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
COMPOSE=(docker compose --env-file "$ROOT/infra/docker/.env" -f "$ROOT/compose.dev.yml")
SCOPE=lessons
if [[ "${1:-}" == "--database" ]]; then SCOPE=database; shift; fi
[[ $# -eq 0 ]] || { echo 'Usage: db-reset.sh [--database]' >&2; exit 1; }

# Resolve the actual container target without evaluating .env as shell code.
SETTINGS=$("${COMPOSE[@]}" exec -T postgres sh -c 'printf "%s\n%s\n%s\n" "$POSTGRES_DB" "$POSTGRES_USER" "${KEYCLOAK_DB:-lyreo_keycloak}"')
TARGET_DB=$(sed -n '1p' <<< "$SETTINGS")
TARGET_USER=$(sed -n '2p' <<< "$SETTINGS")
KEYCLOAK_DB_NAME=$(sed -n '3p' <<< "$SETTINGS")
case "$TARGET_DB" in
  ''|postgres|template0|template1|"$KEYCLOAK_DB_NAME"|lyreo_keycloak)
    echo "ERROR: Refusing unsafe database: '$TARGET_DB'" >&2; exit 1;;
esac
[[ "$TARGET_DB" == *_dev ]] || { echo 'ERROR: Reset requires a local database name ending in _dev.' >&2; exit 1; }
PSQL=("${COMPOSE[@]}" exec -T postgres psql -X -v ON_ERROR_STOP=1 -U "$TARGET_USER" -d "$TARGET_DB")
echo "Database: $TARGET_DB | Scope: $SCOPE"
if [[ "$SCOPE" == lessons ]]; then
  echo 'DELETE: lessons, their authoring/build jobs, practice/progress and linked speech attempts.'
  echo 'KEEP: imported TOEIC/lexicon/grammar, releases/import history, users, AI settings, categories, channels and all storage files.'
else
  echo 'DELETE: the WHOLE application database, including imported TOEIC/lexicon/grammar and all application configuration/history.'
fi
if [[ "$SCOPE" == lessons ]]; then
  COUNTS=''
  for TABLE in lesson lesson_authoring lesson_sentence lesson_word_timestamp lesson_annotation lesson_activity lesson_activity_sentence sentence_pronunciation lesson_metadata lesson_category_membership lesson_authoring_job lesson_build_job lesson_build_job_step lesson_progress lesson_activity_progress lesson_practice_attempt; do
    [[ -z "$COUNTS" ]] || COUNTS+=' UNION ALL '
    COUNTS+="SELECT '$TABLE' AS affected_table,count(*) AS rows FROM $TABLE"
  done
  COUNTS+=" UNION ALL SELECT 'speech_attempt',count(*) FROM speech_attempt WHERE lesson_id IN (SELECT id FROM lesson) OR sentence_id IN (SELECT id FROM lesson_sentence)"
  COUNTS+=" UNION ALL SELECT 'background_job',count(*) FROM background_job WHERE owner_module='lesson' AND job_type IN ('LESSON_AUTHORING','LESSON_BUILD') AND owner_reference_id IN (SELECT id FROM lesson)"
  "${PSQL[@]}" -c "$COUNTS;"
else
  "${PSQL[@]}" -c 'SELECT relname AS affected_table,n_live_tup AS estimated_rows FROM pg_stat_user_tables ORDER BY relname;'
fi
if [[ "${APPLY:-0}" == 0 ]]; then
  echo "Preview only. To execute, pass APPLY=1 CONFIRM=$TARGET_DB."
  exit 0
fi
[[ "${APPLY:-}" == 1 && "${CONFIRM:-}" == "$TARGET_DB" ]] || { echo "ERROR: Require APPLY=1 CONFIRM=$TARGET_DB." >&2; exit 1; }
CONNECTIONS=$("${PSQL[@]}" -Atc 'SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid();')
[[ "$CONNECTIONS" == 0 ]] || { echo 'ERROR: Stop Core and other application database connections before reset.' >&2; exit 1; }
if [[ "$SCOPE" == lessons ]]; then
  "${PSQL[@]}" < "$ROOT/infra/postgres/reset-lessons.sql"
  echo 'Lesson reset completed. Imported banks and reference data were preserved.'
else
  "${COMPOSE[@]}" exec -T postgres psql -X -v ON_ERROR_STOP=1 -U "$TARGET_USER" -d postgres -v target="$TARGET_DB" -v owner="$TARGET_USER" <<'SQL'
SELECT format('DROP DATABASE %I', :'target') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'target', :'owner') \gexec
SQL
  echo 'Database recreated. Start Core to apply Flyway migrations; imported content must be restored or imported again.'
fi
