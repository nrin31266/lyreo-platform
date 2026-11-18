#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
dataset=${1:-all}
case "$dataset" in
  lexicon) fetch=(lexicon-fetch); imports=(import-clean-lexicon) ;;
  grammar-toeic) fetch=(data-fetch); imports=(import-clean-grammar-toeic) ;;
  all) fetch=(lexicon-fetch data-fetch); imports=(import-clean-lexicon import-clean-grammar-toeic) ;;
  *) echo 'DATASET must be lexicon, grammar-toeic or all.' >&2; exit 2 ;;
esac

if [[ "$dataset" == lexicon ]]; then
  ./scripts/check-data-storage.sh --database-only
else
  ./scripts/check-data-storage.sh
fi
make "${fetch[@]}"
make seed-dev
for target in "${imports[@]}"; do
  make "$target" ARGS='--apply --activate --activated-by local-setup'
done
