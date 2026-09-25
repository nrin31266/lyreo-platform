# Lyreo Data Import

Large/reference datasets are **not Flyway migrations** and are **not committed** to this repo.
Flyway owns schema + tiny stable reference rows; importer tools own large mutable datasets.

## Setup

From the repository root, initialize env files first:

```bash
make init-env
```

`tools/data-import/.env` controls the Grammar/TOEIC clean release version, archive URL and SHA-256,
installation path, importer database access, and optional R2 credentials. The uploaded release URL
is intentionally blank until the archive is available on Google Drive. Fill it in, then run:

```bash
make data-fetch
make data-check
```

`make data-fetch` checks an installed release first. If missing, it downloads the pinned archive,
checks its SHA-256, safely extracts one release root, and runs the domain validator before installing
under `.data/releases/grammar-toeic/1.0.1/`. `make data-check` verifies the installed release
without downloading. Both commands leave valid existing releases untouched. To replace a damaged
installation after fixing the URL and SHA, run `./tools/data-import/scripts/fetch-data.sh --force`.
Other package versions live beside `1.0.1`; the fetcher never overwrites a different version.

To include the release in first-clone setup, use `WITH_DATA=1 make setup`. Setup installs Python
dependencies before it fetches. Raw Grammar/TOEIC source is not part of normal team setup.

## Release configuration and Google Drive upload

The canonical archive ready for upload is `.data/releases/grammar-toeic-1.0.1.tar.gz`. Upload that
single file to Google Drive and share it as a downloadable file. In `tools/data-import/.env`, set:

```dotenv
GRAMMAR_TOEIC_RELEASE_VERSION=1.0.1
GRAMMAR_TOEIC_RELEASE_SCHEMA_VERSION=1.0.0
GRAMMAR_TOEIC_RELEASE_DIR=../../.data/releases/grammar-toeic/1.0.1
GRAMMAR_TOEIC_RELEASE_URL=https://drive.google.com/file/d/YOUR_FILE_ID/view
GRAMMAR_TOEIC_RELEASE_SHA256=95f4bb9471e7b7b07f17ccec21a4434e25c18c9adf3dd3b28829eebe21c5adf0
```

The URL may instead be a `file://` URL or a local archive path for offline verification; relative
local paths resolve from `tools/data-import/`. Google
Drive file links use ephemeral `uvx gdown`; other HTTP(S) links use `curl`. The SHA pin is mandatory
for download. Do not put private credentials or a source archive URL into `.env.example`.

## Clean Grammar/TOEIC release (offline builder)

Maintainers may rebuild a future clean package from immutable raw input at `.data/datasets/toeic/`.
`build_grammar_toeic_release.py` prepares a self-contained package;
`validate_grammar_toeic_release.py` checks content, relations, checksums, and raw-source
preservation. Neither tool writes the production database or uploads media. See the
[release envelope convention](RELEASE_FORMAT.md) for versioning and manifest rules. Raw input stays
under `.data/datasets/`; package versions live under Git-ignored `.data/releases/`.

From the repo root, after `uv sync --locked --extra dev` in `tools/data-import`:

```bash
tools/data-import/.venv/bin/python tools/data-import/build_grammar_toeic_release.py \
  --raw .data/datasets/toeic \
  --output .data/releases/grammar-toeic/1.0.2 \
  --archive .data/releases/grammar-toeic-1.0.2.tar.gz \
  --generated-at 2026-09-25T15:44:28Z

tools/data-import/.venv/bin/python tools/data-import/validate_grammar_toeic_release.py \
  --release .data/releases/grammar-toeic/1.0.2 \
  --raw .data/datasets/toeic \
  --archive .data/releases/grammar-toeic-1.0.2.tar.gz \
  --report .data/releases/grammar-toeic/1.0.2-verification.json
```

Change the builder's package version before building `1.0.2`; the example paths alone do not
change manifest metadata. Pass the same `--generated-at` when checking reproducibility. Package
version and clean schema version are separate fields; the schema version changes only when the
record contract changes.

## Legacy raw scripts (maintainers only)

The existing `import_grammar.py` and `import_toeic.py` read source exports under
`GRAMMAR_TOEIC_RAW_DIR` or an explicit `--data-dir`. They do not read the clean release. Keep raw
files only for release engineering and legacy diagnostics; `make data-fetch` never downloads them.
No PostgreSQL import from the clean release is implemented yet.

## Global Lexicon

Lexicon is a completely separate pipeline. Its source dataset has **not been downloaded/scraped for
Lyreo yet**. Intended inputs are Kaikki/Wiktextract JSONL files from English/Vietnamese Wiktionary.

Strategy:

1. broad English seed from English Wiktionary via Kaikki/Wiktextract;
2. optional Vietnamese Wiktionary pass to fill Vietnamese glosses where possible;
3. retain `translation_status=MISSING` when source data is incomplete;
4. lazy enrichment later; do **not** spend one LLM call per dictionary word during seed;
5. preserve source/license provenance;
6. keep pronunciation external-audio metadata for lazy R2 caching.

After those external files exist:

```bash
uv run python import_lexicon.py \
  --english "$KAIKKI_EN_JSONL" \
  --vietnamese "$KAIKKI_VI_JSONL"

uv run python import_lexicon.py \
  --english "$KAIKKI_EN_JSONL" \
  --vietnamese "$KAIKKI_VI_JSONL" \
  --apply
```

Use `--limit 1000` while developing importer changes. The importer commits large lexicon batches
instead of holding the entire dictionary transaction open.

Vietnamese Wiktionary merge is best-effort: source revisions must be reviewed in dry-run before an
applied import, and missing translations are a valid state rather than an import failure.

## Import observability

Every applied importer writes/updates `dataset_import` with a content checksum, status, counts, and
error message. Raw datasets remain outside Git. Failed large imports stay visible instead of being
hidden behind an ad-hoc script run.

Dataset storage/download location does not affect AI token cost. AI cost is created by actual
provider/model capability calls, not by whether source/result bytes live on local disk or R2.
