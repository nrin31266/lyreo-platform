# Lyreo data pipelines

Large datasets are product assets outside Git, not Flyway seed blobs. Flyway owns schema and small stable reference defaults. Importers own acquisition, validation, dry-run, apply, and audit. Exact source URLs, archive shape, environment keys, and commands live in [Data Import](../tools/data-import/README.md).

## Lesson source preparation

[Integrated Lesson authoring](features/lesson-build.md) owns the active Admin workflow: Core inspects/caches the YouTube source, prepares the selected audio range, orchestrates AI capabilities and persists review checkpoints before explicit publication. Media tools are installed on the Core host with `make setup-media`. The obsolete standalone workstation and ZIP export workflow are removed; its remaining local environment/cache/audio artifacts were purged at the owner's request on 2026-10-31. Portable ZIP ingestion remains outside this phase ([OQ-005](requirements/open-questions.md#oq-005--portable-lesson-source-ingestion-policy), closed).

## Dataset import contract

An importer validates shape, provenance, license, checksum/version, and referential integrity before apply. A dry-run reports what would change. Apply records dataset identity, counts, status, and errors, and supports controlled reruns without silently duplicating content. Large source files and temporary fixtures remain outside Git.

Fetch/check acquires or verifies a clean release on disk. An importer dry-run validates and plans
without database or storage writes. APPLY imports into PostgreSQL and, for Grammar/TOEIC, verifies/reuses existing
media in the configured local storage or R2 bucket, uploading only missing keys; activation is a separate explicit option after successful reconciliation. See
[Data Import](../tools/data-import/README.md) for the supported commands.

## Lexicon

Global dictionary entries belong to Lexicon. Vocabulary SRS references Lexicon and owns learner review history. Import normalization, source attribution, and pronunciation variants belong to the Lexicon importer; Lesson lexical annotations can link terms without taking dictionary ownership.

## Grammar and TOEIC

Grammar datasets provide taxonomy, bank sets, questions, answers, and explanations where available. TOEIC datasets provide tests, passages, questions, answer keys, and media references. Importers normalize queryable structure in PostgreSQL and put large media in object storage. Missing or unapproved scaled-score conversion data must not be invented by the importer; see [OQ-004](requirements/open-questions.md#oq-004--toeic-scaled-score-conversion).

The offline builders prepare validated, versioned clean Lexicon and Grammar/TOEIC releases from immutable raw source before any production import. Their package envelopes (manifest, checksums, provenance, issue report) follow the [dataset release convention](../tools/data-import/RELEASE_FORMAT.md). Normal developer setup fetches pinned clean archives into `.data/releases/<domain>/<package-version>/` and verifies archive and package checksums. The production importers read those clean releases, record import runs and exact collection counts, and can explicitly activate a successfully imported release per domain. Catalog publication and access policy remain runtime state across later releases. Raw source under `.data/datasets/` is maintainer input for rebuilding, not a team setup dependency. The legacy raw-source import scripts are not production importers; possessing a clean release alone does not mean Core has loaded it.

### Reviewed language maintenance, 2026-11-18

The high-thinking Flash pilot completed 18 context units: four Dictionary, two Grammar and twelve
TOEIC units across Parts 1–4, 6 and 7. Part 5 already had the targeted language fields. Review of
all 23 proposed fields accepted 20 explanations (one Grammar, nineteen TOEIC) for the source-hash-pinned
Grammar/TOEIC 2.0.1 override. Three proposals were rejected: an unnecessary Dictionary rewording,
a Part 1 answer restatement without photograph evidence, and a Part 6 explanation overstating
“let us know” as a fixed object choice. No answer key, option, passage or media was changed.

The shared ledger records 69 total attempts, including earlier executions: 68 succeeded and one
failed with its reservation retained. Successful token usage priced at the recorded peak rates totals
USD 0.06505350; the failed attempt retains USD 0.00753030 of uncertain charges. These are conservative
usage-based amounts, not a provider invoice. Total pre-call reservations remain USD 0.57560130 of
the authorized USD 1.50 ceiling. A fresh-JVM rerun added zero calls, attempts or reservations after
fixing non-canonical map serialization in the cache key.

The 2.0.0 inventory found 9,937 combined source translations, zero missing translation/explanation
fields among 3,053 Grammar items, 1,228 missing item translations and 2,744 missing explanations
across the shared corpus. The text-only candidate generator excludes 450 Part 1 explanations needing
visual evidence, leaving 2,021 TOEIC context units. Grammar quality review remains optional, distinct
from missing fields. After the accepted 2.0.1 changes, 2,725 missing explanations and 2,012 text
contexts remain; translation counts are unchanged. These counts describe field availability, not language correctness.
The three additional language defects found in TOEIC were subsequently corrected with reviewed,
source-hash-pinned repairs in Grammar/TOEIC 2.0.2. The Dictionary example typo and its offsets were
corrected in Lexicon 2.0.1. The original rows, reviewer evidence and exact before/after values are
retained by the [reviewed source repair contract](../tools/data-import/RELEASE_FORMAT.md#reviewed-source-repairs).
This pilot left the 2019 Test 5 questions 62–64 image reference unresolved. The later source review
confirmed an obsolete reference and recorded the correction in the
[importer documentation](../tools/data-import/README.md#grammartoeic-v2).

Scaling observed per-Part pilot usage gives a rough USD 2.29 estimate for the 2,021 text contexts;
longer output/retry variability is not covered by that point estimate. No full batch was run or
authorized by the pilot ceiling. Operational review/export commands live in
[Data Import](../tools/data-import/README.md#bounded-deepseek-maintenance); spending policy lives in
[BR-AI-006](requirements/ai.md#br-ai-006--bounded-library-maintenance-pilot).

## Curriculum and artifacts

Curriculum content references existing owners rather than copying their durable records. Stable small defaults may use Flyway; large paths and learning content use versioned imports. PostgreSQL owns normalized/queryable records, object storage holds immutable media and raw debug artifacts, and databases persist keys rather than signed URLs.

## Operator setup and editorial conflicts

Package installation runs through the CLI. Each developer selects the content needed after
`make setup` prepares the application. Package validation and import reports remain available
through the importer, and content quality checks show actionable errors in the affected catalog.

Source fingerprints preserve sparse edits when unchanged. Changed or missing source fails
activation until the editor's decision is recorded against the current draft. Admin-created
content and immutable learner sessions survive imports.
See [editorial architecture](architecture/library-editorial.md).

`make setup-data` verifies and installs the selected packages after migration and seed. Use
`DATASET=lexicon` or `DATASET=grammar-toeic` to install one. Existing R2 keys are verified/reused. Team members need bucket credentials through
the private environment channel, not files committed to Git. No development database reset is needed.
