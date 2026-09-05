# Lyreo Data Import

Large/reference datasets are **not Flyway migrations** and are **not committed** to this repo.
Flyway owns schema + tiny stable reference rows; importer tools own large mutable datasets.

## Setup

```bash
cd tools/data-import
cp .env.example .env
uv sync
set -a; source .env; set +a
```

## Grammar (`dautoeic/grammar_data`)

`import_grammar.py` reads the real exported structure discussed during design:

- topics/subtopics;
- difficulty/bank membership metadata;
- `grammar_questions_flat.json`;
- source explanation/translation/vocabulary notes;
- nullable topic/subtopic classification;
- `prefer_ai_explanation` mapped to an explanation policy rather than regenerating questions.

```bash
uv run python import_grammar.py --data-dir "$DAUTOEIC_DATA_DIR"
uv run python import_grammar.py --data-dir "$DAUTOEIC_DATA_DIR" --apply
```

The first command is dry-run and prints counts/checksum.

## TOEIC (`dautoeic/mock_test_data`)

`import_toeic.py` reads aggregate mock-test/passages/questions JSON. `downloads/2019..2026`
remains external and can optionally be uploaded to R2.

```bash
uv run python import_toeic.py --data-dir "$DAUTOEIC_DATA_DIR"
uv run python import_toeic.py --data-dir "$DAUTOEIC_DATA_DIR" --apply
uv run python import_toeic.py --data-dir "$DAUTOEIC_DATA_DIR" --apply --upload-media
```

Database stores normalized metadata/object keys, never absolute developer paths.

## Global Lexicon

Strategy:

1. broad English seed from English Wiktionary via Kaikki/Wiktextract;
2. optional Vietnamese Wiktionary pass to fill Vietnamese glosses where possible;
3. retain `translation_status=MISSING` when source data is incomplete;
4. lazy enrichment later; do **not** spend one LLM call per dictionary word during seed;
5. preserve data source/license provenance;
6. keep pronunciation external audio metadata for lazy R2 caching.

```bash
uv run python import_lexicon.py \
  --english "$KAIKKI_EN_JSONL" \
  --vietnamese "$KAIKKI_VI_JSONL"

uv run python import_lexicon.py \
  --english "$KAIKKI_EN_JSONL" \
  --vietnamese "$KAIKKI_VI_JSONL" \
  --apply
```

Use `--limit 1000` while developing importer changes. The importer commits large lexicon
batches instead of holding the entire dictionary transaction open.

> Vietnamese Wiktionary merge is deliberately best-effort: upstream dump structure must be
> reviewed in dry-run before applying a new dump revision. The importer never claims missing
> translations are errors.

## Import observability

Every applied importer writes/updates `dataset_import` with a content checksum, status,
counts and error message. Raw datasets remain outside Git. Failed large imports are visible
instead of disappearing behind an ad-hoc script run.
