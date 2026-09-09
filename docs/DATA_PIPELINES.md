# Lyreo Data Pipelines

Large datasets are first-class product assets, not Flyway seed blobs. Every importer follows dry-run → validate → checksum/version → apply → import audit.

## 1. Dataset import contract

Every importer should support:

- dry-run by default;
- input structure validation;
- deterministic/external ID mapping;
- checksum/version;
- idempotent rerun;
- batch insert/update;
- `dataset_import` audit row;
- actionable failure report;
- media object-key mapping instead of absolute developer paths.

Raw datasets stay outside Git.

## 2. Lexicon pipeline

Current status: the Lyreo Lexicon dataset has **not been scraped/downloaded yet**. It is separate
from the current Grammar/TOEIC scraper output. The intended source is pre-existing
Wiktextract/Kaikki JSONL, as specified in
[`requirements/lexicon-vocabulary.md`](requirements/lexicon-vocabulary.md#br-lex-002--provenance-and-coverage).

Goal: broad global dictionary, independent from one lesson, while still allowing lesson runtime discovery of unknown phrases.

```text
English Wiktionary/Kaikki
       +
Vietnamese Wiktionary/Kaikki
       ↓
stream parser
       ↓
normalize
├─ LexiconEntry
├─ forms
├─ senses
├─ pronunciation/audio source
└─ source/license provenance
       ↓
PostgreSQL
```

Rules:

- do not load entire dump into memory;
- do not call LLM once per word during seed;
- missing Vietnamese meaning is valid state (`MISSING`), not import failure;
- keep source/license provenance;
- pronunciation source audio may be lazy-cached to R2;
- runtime lesson phrase that is not in Lexicon can stay unresolved and be linked later.

Corpus data (TOEIC/Curriculum/Lessons) may update relevance/frequency, but does not define the only dictionary coverage.

## 3. Lesson lexical enrichment

AI/NLP detects context-important words/phrases, especially multi-word units.

```text
Lesson sentence
  ↓
lexical detector
  ↓
"take into account"
  ↓
lookup Lexicon
  ├─ hit  → link lexiconEntryId
  └─ miss → keep surface/context meaning + UNRESOLVED marker
             → lazy dictionary/enrichment resolution later
```

Lesson remains usable even before global Lexicon catches up.

## 4. Grammar importer (`grammar_data`)

The current dataset on the development machine contains both CSV and JSON exports:

```text
grammar_bank_sets.{csv,json}
grammar_difficulty_levels.{csv,json}
grammar_question_memberships.{csv,json}
grammar_questions.{csv,json}
grammar_questions_flat.{csv,json}
grammar_subtopics.{csv,json}
grammar_topics.{csv,json}
manifest.json
schema.sql
```

`tools/data-import/import_grammar.py` currently reads **only** these JSON files:

```text
grammar_questions_flat.json
grammar_topics.json
grammar_subtopics.json
grammar_bank_sets.json
```

The script does not currently read the CSV copies, `grammar_difficulty_levels.json`,
`grammar_question_memberships.json`, `grammar_questions.json`, `manifest.json`, or `schema.sql`
directly. Membership information is taken from flat-question fields such as `bank_set_id` and
`order_index` when present.

Fields consumed from the flat question rows include `question_id`/`id`, question/options/correct
answer, explanations/translations, vocabulary note, difficulty, nullable topic/subtopic, bank-set
metadata, and original test/source metadata. `prefer_ai_explanation` maps to the importer
explanation policy.

AI is useful for **missing topic/subtopic classification or on-demand explanation**, not for
regenerating a bank that already exists.

## 5. TOEIC importer (`mock_test_data`)

The current dataset on the development machine contains aggregate CSV/JSON exports and downloaded
media:

```text
all_mock_tests.{csv,json}
all_passages.{csv,json}
all_questions.{csv,json}
all_difficulty_stats.{csv,json}
downloads/{2019..2026}/Test {1..10}/{audio,data,images}/
```

`tools/data-import/import_toeic.py` currently reads JSON only:

```text
all_mock_tests.json
all_passages.json
all_questions_updated.json  # preferred if present
all_questions.json          # fallback; present in the current dataset
```

`all_difficulty_stats.*` and the CSV copies are not consumed by the current importer. The importer
recursively catalogs files under `mock_test_data/downloads/` for media resolution.

The integrity check expects test IDs from `id`/`test_id`, passage IDs from `id`/`passage_id`, and
question IDs from `id`/`question_id`; passages/questions reference `test_id`, while a question may
optionally reference `passage_id`. Media references are resolved from the first available field in:

```text
audio: audio_path | audio_file | audio | local_audio_path
image: image_path | image_file | image | local_image_path
```

Mapping:

```text
structured metadata/questions/passages → PostgreSQL
local media (with --upload-media)       → configured R2 bucket
raw source directory                    → external filesystem, not Git
```

The importer stores generated object keys in PostgreSQL, never developer-machine absolute paths or
presigned URLs.

## 6. Curriculum seed

Curriculum is a separate module that references existing content IDs:

```text
CurriculumPath
└─ Section
   └─ Item(type, content_reference_id)
```

Item types can reference Lesson, Grammar Practice, TOEIC Drill/Mini Test and future Speaking Scenario without copying content tables.

Initial 100-lesson curriculum can be shipped as a controlled importer/content bundle rather than giant Flyway SQL.

## 7. R2 raw artifacts vs database state

Raw model/provider response:

```text
jobs/{jobId}/stt/raw.json
jobs/{jobId}/alignment/raw.json
...
```

Normalized UI/query data:

```text
lesson_sentence
lesson_word_timestamp
lesson_annotation
...
```

Raw R2 file is for debug/audit, **not** resume checkpoint/source of truth.

## 8. Import environment

The project owner's original Grammar/TOEIC dataset is still available locally at
`~/KeepDownloads/toeic`. A shared `toeic-dataset.zip` is also available for team bootstrap:

```text
https://drive.google.com/file/d/1FQgEswv3hUmT0Wv8Tyy9Jl_p9iLfoLZs/view?usp=sharing
```

`tools/data-import/.env.example` defaults `DAUTOEIC_DATA_DIR` to the Git-ignored
`../../.data/datasets/toeic` path and configures that Drive file as `DAUTOEIC_DATA_URL`.

`make data-fetch` / `scripts/fetch-data.sh` implements the developer bootstrap path:

1. validate an existing dataset and skip download when it already matches the importer contract;
2. use ephemeral `uvx --from gdown gdown --fuzzy` for the Google Drive large-file flow;
3. optionally verify `DAUTOEIC_DATA_SHA256` when the team has pinned it;
4. accept ZIP/tar archives while rejecting path traversal and tar links before extraction;
5. locate exactly one extracted root containing both `grammar_data/` and `mock_test_data/`;
6. validate the exact JSON files required by the current Grammar/TOEIC importers; `downloads/` media is catalogued when present but is not required for JSON-only work;
7. install the dataset into `DAUTOEIC_DATA_DIR` only after archive and shape validation succeed.

`make data-check` performs only local importer-facing validation. `SKIP_DATA=1 make setup` lets a
developer defer the multi-GB download when working on areas that do not need this dataset.

Lexicon remains a **separate, not-yet-downloaded dataset pipeline**. The TOEIC archive does not make
Kaikki/Wiktextract data available. Populate `KAIKKI_EN_JSONL` / `KAIKKI_VI_JSONL` only after those
sources have actually been obtained.

Use `tools/data-import/.env` for:

- `DAUTOEIC_DATA_DIR`, `DAUTOEIC_DATA_URL`, optional `DAUTOEIC_DATA_SHA256`;
- DB connection;
- optional R2 credentials;
- Kaikki paths when Lexicon source files become available.

Storage location does not determine AI token cost. Token/inference cost belongs to capability calls
(STT/TTS/LLM/etc.); local-vs-R2 persistence only changes where artifact bytes are stored.

Keep importer config separate from Core `.env` so a data-maintenance machine does not need every
application secret.
