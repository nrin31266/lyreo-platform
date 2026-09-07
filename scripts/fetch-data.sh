#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
ENV_FILE="$ROOT/tools/data-import/.env"
REQUIRED=false
FORCE=false
CHECK_ONLY=false

usage() {
  cat <<'USAGE'
Usage: ./scripts/fetch-data.sh [--required] [--force] [--check]

Fetch the external Grammar/TOEIC dataset only when DAUTOEIC_DATA_DIR is missing.
Configuration is read from tools/data-import/.env.

  --required  fail when the dataset is missing and no download URL is configured
  --force     replace an existing incomplete dataset directory
  --check     validate dataset presence/shape without downloading
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --required) REQUIRED=true ;;
    --force) FORCE=true ;;
    --check) CHECK_ONLY=true ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing tools/data-import/.env. Run 'make init-env' first." >&2
  exit 1
fi

# Source from the importer directory so relative DAUTOEIC_DATA_DIR values resolve exactly as they
# do when the importer commands are run from tools/data-import.
pushd "$ROOT/tools/data-import" >/dev/null
set -a
# shellcheck disable=SC1091
source ./.env
set +a

DATA_DIR_RAW=${DAUTOEIC_DATA_DIR:-}
DATA_URL=${DAUTOEIC_DATA_URL:-}
DATA_SHA256=${DAUTOEIC_DATA_SHA256:-}

if [[ -z "$DATA_DIR_RAW" ]]; then
  echo "DAUTOEIC_DATA_DIR is not configured in tools/data-import/.env" >&2
  popd >/dev/null
  exit 1
fi

DATA_DIR=$(python3 - "$DATA_DIR_RAW" <<'PY'
from pathlib import Path
import sys
print(Path(sys.argv[1]).expanduser().resolve())
PY
)
popd >/dev/null

has_dataset_shape() {
  local root=$1
  [[ -d "$root/grammar_data" ]] || return 1
  [[ -d "$root/mock_test_data" ]] || return 1
  [[ -f "$root/grammar_data/grammar_questions_flat.json" ]] || return 1
  [[ -f "$root/grammar_data/grammar_topics.json" ]] || return 1
  [[ -f "$root/grammar_data/grammar_subtopics.json" ]] || return 1
  [[ -f "$root/grammar_data/grammar_bank_sets.json" ]] || return 1
  [[ -f "$root/mock_test_data/all_mock_tests.json" ]] || return 1
  [[ -f "$root/mock_test_data/all_passages.json" ]] || return 1
  [[ -f "$root/mock_test_data/all_questions_updated.json" || -f "$root/mock_test_data/all_questions.json" ]] || return 1
}

if has_dataset_shape "$DATA_DIR"; then
  echo "TOEIC/Grammar dataset ready: $DATA_DIR"
  exit 0
fi

if $CHECK_ONLY; then
  echo "Dataset missing or incomplete: $DATA_DIR" >&2
  exit 1
fi

if [[ -e "$DATA_DIR" && "$FORCE" != true ]]; then
  echo "Dataset directory exists but is incomplete: $DATA_DIR" >&2
  echo "Inspect it manually, or rerun with --force to replace it." >&2
  exit 1
fi

if [[ -z "$DATA_URL" ]]; then
  cat >&2 <<EOF2
Dataset is not present at:
  $DATA_DIR

DAUTOEIC_DATA_URL is empty in tools/data-import/.env.
Set it to a directly downloadable .zip/.tar/.tar.gz archive containing:
  grammar_data/
  mock_test_data/
then run:
  make data-fetch
EOF2
  if $REQUIRED; then exit 1; else exit 0; fi
fi

WORK_ROOT="$ROOT/.data/dataset-fetch"
mkdir -p "$WORK_ROOT"
TMP_DIR=$(mktemp -d "$WORK_ROOT/run.XXXXXX")
ARCHIVE="$TMP_DIR/dataset.archive"
EXTRACT_DIR="$TMP_DIR/extracted"
mkdir -p "$EXTRACT_DIR"
trap 'rm -rf "$TMP_DIR"' EXIT

echo "Fetching TOEIC/Grammar dataset..."
case "$DATA_URL" in
  file://*)
    cp "${DATA_URL#file://}" "$ARCHIVE"
    ;;
  http://*|https://*)
    if [[ "$DATA_URL" == https://drive.google.com/* ]]; then
      if ! command -v uvx >/dev/null 2>&1; then
        echo "uvx is required for Google Drive share links (installed with uv)" >&2
        exit 1
      fi
      # The shared Drive object must be one archive file, not a Drive folder. gdown handles the
      # confirmation flow that large Drive files often require and remains an ephemeral tool here.
      uvx --from 'gdown==6.2.0' gdown "$DATA_URL" -O "$ARCHIVE"
    else
      if ! command -v curl >/dev/null 2>&1; then
        echo "curl is required to download DAUTOEIC_DATA_URL" >&2
        exit 1
      fi
      curl --fail --location --retry 3 --retry-delay 2 --output "$ARCHIVE" "$DATA_URL"
    fi
    ;;
  *)
    if [[ -f "$DATA_URL" ]]; then
      cp "$DATA_URL" "$ARCHIVE"
    else
      echo "Unsupported DAUTOEIC_DATA_URL. Use http(s), file://, or an existing local archive path." >&2
      exit 1
    fi
    ;;
esac

if [[ -n "$DATA_SHA256" ]]; then
  if ! command -v sha256sum >/dev/null 2>&1; then
    echo "sha256sum is required when DAUTOEIC_DATA_SHA256 is configured" >&2
    exit 1
  fi
  ACTUAL_SHA=$(sha256sum "$ARCHIVE" | awk '{print $1}')
  if [[ "${ACTUAL_SHA,,}" != "${DATA_SHA256,,}" ]]; then
    echo "Dataset checksum mismatch." >&2
    echo "expected: $DATA_SHA256" >&2
    echo "actual:   $ACTUAL_SHA" >&2
    exit 1
  fi
fi

# Extract with the Python standard library so the helper does not add a permanent project
# dependency. Reject path traversal and tar links before writing files.
python3 - "$ARCHIVE" "$EXTRACT_DIR" <<'PY'
from __future__ import annotations

import sys
import tarfile
import zipfile
from pathlib import Path

archive = Path(sys.argv[1])
destination = Path(sys.argv[2]).resolve()


def safe_target(name: str) -> Path:
    target = (destination / name).resolve()
    try:
        target.relative_to(destination)
    except ValueError as exc:
        raise SystemExit(f"Unsafe archive member path: {name}") from exc
    return target


if zipfile.is_zipfile(archive):
    with zipfile.ZipFile(archive) as zf:
        for member in zf.infolist():
            safe_target(member.filename)
        zf.extractall(destination)
elif tarfile.is_tarfile(archive):
    with tarfile.open(archive) as tf:
        members = tf.getmembers()
        for member in members:
            safe_target(member.name)
            if member.issym() or member.islnk():
                raise SystemExit(f"Archive links are not allowed: {member.name}")
        tf.extractall(destination, members=members)
else:
    raise SystemExit(
        "DAUTOEIC_DATA_URL must resolve to a zip or tar archive. "
        "For shared Drive storage, publish/download one archive rather than a raw folder link."
    )
PY

DATASET_ROOT=$(python3 - "$EXTRACT_DIR" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
candidates = []
for candidate in [root, *[p for p in root.rglob("*") if p.is_dir()]]:
    if (candidate / "grammar_data").is_dir() and (candidate / "mock_test_data").is_dir():
        candidates.append(candidate)

if len(candidates) != 1:
    raise SystemExit(
        f"Expected exactly one extracted dataset root containing grammar_data/ and mock_test_data/; "
        f"found {len(candidates)}"
    )
print(candidates[0].resolve())
PY
)

if ! has_dataset_shape "$DATASET_ROOT"; then
  echo "Downloaded archive does not match the importer-required dataset shape." >&2
  exit 1
fi

mkdir -p "$(dirname "$DATA_DIR")"
if [[ -e "$DATA_DIR" ]]; then
  rm -rf "$DATA_DIR"
fi
mv "$DATASET_ROOT" "$DATA_DIR"

echo "OK   Grammar/TOEIC dataset installed: $DATA_DIR"
echo "Run 'make data-check' or the importer dry-runs before applying data to PostgreSQL."
