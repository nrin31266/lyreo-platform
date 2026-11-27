#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
dataset=${1:-all}
case "$dataset" in
  lexicon) fetch=(lexicon-fetch); imports=(import-clean-lexicon) ;;
  grammar-toeic) fetch=(data-fetch); imports=(import-clean-grammar-toeic) ;;
  vocabulary) fetch=(); imports=(import-clean-vocabulary) ;;
  all) fetch=(lexicon-fetch data-fetch); imports=(import-clean-lexicon import-clean-grammar-toeic import-clean-vocabulary) ;;
  *) echo 'DATASET must be lexicon, grammar-toeic, vocabulary or all.' >&2; exit 2 ;;
esac

case "$dataset" in
  lexicon) ./scripts/check-data-storage.sh --database-only ;;
  vocabulary|all) ./scripts/check-data-storage.sh --require-r2 ;;
  grammar-toeic) ./scripts/check-data-storage.sh ;;
esac
if ((${#fetch[@]})); then make "${fetch[@]}"; fi
make migrate
for target in "${imports[@]}"; do
  make "$target" ARGS='--apply'
done
if [[ "$dataset" == "all" ]]; then
  make seed
fi
