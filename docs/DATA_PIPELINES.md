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

## 4. Grammar importer (`dautoeic/grammar_data`)

Expected files include:

```text
grammar_topics.json
grammar_subtopics.json
grammar_difficulty_levels.json
grammar_bank_sets.json
grammar_questions.json
grammar_question_memberships.json
grammar_questions_flat.json
manifest.json
```

Preserve existing:

- source question/options/correct answer;
- Vietnamese explanation/translation;
- answer translation;
- vocabulary annotation;
- difficulty;
- bank/test/source metadata;
- nullable topic/subtopic.

AI is useful for **missing topic/subtopic classification or on-demand explanation**, not for regenerating a bank that already exists.

## 5. TOEIC importer (`dautoeic/mock_test_data`)

Expected aggregates:

```text
all_mock_tests.json
all_passages.json
all_questions.json
all_difficulty_stats.json
```

Media structure currently includes years 2019–2026:

```text
downloads/<year>/Test <n>/
├── audio/
├── data/
└── images/
```

Mapping:

```text
structured metadata/questions/passages → PostgreSQL
audio/images                           → R2
raw source directory                   → external filesystem, not Git
```

Importer should not store `/home/user/...` paths in DB.

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

Use `tools/data-import/.env` for:

- `DAUTOEIC_DATA_DIR`;
- DB connection;
- optional R2 credentials;
- Kaikki paths.

Keep importer config separate from Core `.env` so a data-maintenance machine does not need every application secret.
