# Lyreo Data Import

Large/reference datasets are **not Flyway migrations** and are **not committed** to this repo.
Flyway owns schema + tiny stable reference rows; importer tools own large mutable datasets.

## Setup

From the repository root, initialize env files first:

```bash
make init-env
```

`tools/data-import/.env` controls dataset paths/download source, importer database access, and
optional R2 credentials.

The project-owner Grammar/TOEIC archive is configured in `.env.example` and installs into the
Git-ignored `.data/datasets/toeic` path by default. A fresh clone can fetch it only when it is
missing:

```bash
make data-fetch
make data-check
```

`make setup` also fetches the dataset by default. Developers who are not doing Grammar/TOEIC data
work can defer the multi-GB download:

```bash
SKIP_DATA=1 make setup
```

Then prepare the Python project environment:

```bash
cd tools/data-import
uv sync --locked --extra dev
set -a; source .env; set +a
```

## Grammar (`grammar_data`)

`import_grammar.py` currently reads exactly:

```text
grammar_questions_flat.json
grammar_topics.json
grammar_subtopics.json
grammar_bank_sets.json
```

The broader source export may contain CSV copies and additional metadata, but the importer only
requires the files above today. Source explanations/translations/vocabulary notes are preserved;
nullable topic/subtopic classification is valid input; `prefer_ai_explanation` maps to an
explanation policy instead of regenerating the question bank.

Dry-run first:

```bash
uv run python import_grammar.py --data-dir "$DAUTOEIC_DATA_DIR"
```

Apply only after reviewing the counts/checksum:

```bash
uv run python import_grammar.py --data-dir "$DAUTOEIC_DATA_DIR" --apply
```

## TOEIC (`mock_test_data`)

`import_toeic.py` reads aggregate mock-test/passages/questions JSON:

```text
all_mock_tests.json
all_passages.json
all_questions_updated.json  # preferred when present
all_questions.json          # supported fallback
```

`mock_test_data/downloads/` is catalogued when present so media can optionally be uploaded to R2;
its absence does not invalidate JSON-only dry-run/import work.

```bash
uv run python import_toeic.py --data-dir "$DAUTOEIC_DATA_DIR"
uv run python import_toeic.py --data-dir "$DAUTOEIC_DATA_DIR" --apply
uv run python import_toeic.py --data-dir "$DAUTOEIC_DATA_DIR" --apply --upload-media
```

Database rows store normalized metadata/object keys, never absolute developer paths or signed URLs.

## Shared Grammar/TOEIC dataset acquisition

The current project-owner source is published as one Google Drive archive:

```text
https://drive.google.com/file/d/1FQgEswv3hUmT0Wv8Tyy9Jl_p9iLfoLZs/view?usp=sharing
```

Default importer env:

```dotenv
DAUTOEIC_DATA_DIR=../../.data/datasets/toeic
DAUTOEIC_DATA_URL=https://drive.google.com/file/d/1FQgEswv3hUmT0Wv8Tyy9Jl_p9iLfoLZs/view?usp=sharing
DAUTOEIC_DATA_SHA256=
```

`scripts/fetch-data.sh`:

1. skips the download when the existing dataset already matches the importer contract;
2. supports HTTP(S), Google Drive file shares, `file://`, and local archive paths;
3. runs Google Drive download ephemerally through `uvx --from gdown gdown --fuzzy`;
4. verifies `DAUTOEIC_DATA_SHA256` when pinned;
5. accepts ZIP/tar archives, rejects path traversal, and rejects tar links;
6. finds exactly one extracted root containing `grammar_data/` + `mock_test_data/`;
7. validates the exact JSON files the current importers require before installing the dataset;
8. installs atomically enough that the destination is only replaced after archive validation.

Useful modes:

```bash
./scripts/fetch-data.sh --check
./scripts/fetch-data.sh --force
./scripts/fetch-data.sh --required
```

Do not put private download credentials into `.env.example`. If the shared provider later requires
private authentication, add an explicit secure mechanism instead of embedding credentials in URLs.

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
