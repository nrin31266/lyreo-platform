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

The Lexicon v1 clean release is an offline, separate package. Its canonical maintainer-only inputs
are `.data/datasets/lexicon/raw-wiktextract-data.jsonl.gz` (English Wiktionary dump dated
2026-09-02; SHA-256 `5ab411b8859490789d0d002074cc029569648f901db940c0ab10e709932e1632`)
and `.data/datasets/lexicon/vi-extract.jsonl.gz` (optional Vietnamese Wiktionary auxiliary dump
dated 2026-09-01; SHA-256 `10e62bccdf3d85fc9e7472c1f85b2149602f97c75177b09523f4762c57ef7d3d`).
The builder checks these pins by default. Keep raw data and SQLite staging outside Git under
`.data/`; normal `lexicon-fetch` and `lexicon-check` use only the clean release.

From the repository root, after `uv sync --locked --extra dev` in `tools/data-import`, build and
validate the pinned v1 release with the project Python 3.12 environment (Unicode database 15.0.0):

```bash
tools/data-import/.venv/bin/python tools/data-import/build_lexicon_release.py \
  --raw-en .data/datasets/lexicon/raw-wiktextract-data.jsonl.gz \
  --raw-vi .data/datasets/lexicon/vi-extract.jsonl.gz \
  --output .data/releases/lexicon/1.0.0 \
  --archive .data/releases/lexicon-1.0.0.tar.gz \
  --staging-dir .data/tmp \
  --generated-at 2026-09-26T00:00:00Z

tools/data-import/.venv/bin/python tools/data-import/validate_lexicon_release.py \
  --release .data/releases/lexicon/1.0.0 \
  --raw-en .data/datasets/lexicon/raw-wiktextract-data.jsonl.gz \
  --archive .data/releases/lexicon-1.0.0.tar.gz \
  --report .data/releases/lexicon/1.0.0-verification.json
```

The builder refuses existing output paths; use new paths for a rebuild. Reuse the same
`--generated-at` with unchanged inputs and builder when checking byte-for-byte reproducibility.
Package version and schema version are both `1.0.0`. The release directory contains `manifest.json`,
`validation_report.json`, `LICENSE`, and ID-sorted `entries.jsonl`, `items.jsonl`, `senses.jsonl`,
`forms.jsonl`, `pronunciations.jsonl`, and `translations.jsonl`. The archive has one
`lexicon-1.0.0/` root. `manifest.json` records source filenames, byte sizes, checksums, and dates;
`LICENSE` carries attribution for both Wiktionary editions and a CC BY-SA 4.0 notice.
Pronunciations reference external audio URLs; the
archive contains no audio files. Preserve source and license provenance when distributing or
importing the release.

Translations in `translations.jsonl` carry a `link_status`. `DIRECT_SENSE` and
`QUALIFIER_MATCH` have a `sense_id`; the latter is a deterministic qualifier match, not proof of
semantic accuracy. `ITEM_CANDIDATE` and `ENTRY_CANDIDATE` are unresolved candidates at the named
scope. Do not present candidates as sense translations. Senses without a sense-linked translation
retain `translation_status=MISSING`, even if a candidate exists.
Entry IDs are designed to remain stable across snapshots based on case-preserving headword identity,
but this is best effort. Duplicate lexical blocks use source-order `item_seq`; their item IDs are
deterministic within a snapshot and may change if upstream order changes. A later importer must
reconcile IDs across snapshots.

The full pinned build measured about 594 MB peak RSS with SQLite staging; plan for under 750 MB
peak RSS, at least 2 GB RAM, and at least 10 GB free disk on a maintainer machine. The builder is
intended to stream the raw inputs with bounded memory.

For a team member with `tools/data-import/.env` configured, the clean release commands are:

```bash
make lexicon-fetch
make lexicon-check
```

`LEXICON_RELEASE_URL` remains blank until the archive is published. The version, schema version,
installation directory, and archive SHA-256 are pinned in `.env.example`; raw input paths there
are maintainer-only hints and are not read by the fetch/check commands. The older
`import_lexicon.py` reads raw source exports and is not a clean-release PostgreSQL importer.

## Import observability

Every applied importer writes/updates `dataset_import` with a content checksum, status, counts, and
error message. Raw datasets remain outside Git. Failed large imports stay visible instead of being
hidden behind an ad-hoc script run.

Dataset storage/download location does not affect AI token cost. AI cost is created by actual
provider/model capability calls, not by whether source/result bytes live on local disk or R2.
