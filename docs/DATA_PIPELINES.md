# Lyreo data pipelines

Large datasets are product assets outside Git, not Flyway seed blobs. Flyway owns schema and small stable reference defaults. Importers own acquisition, validation, dry-run, apply, and audit. Exact source URLs, archive shape, environment keys, and commands live in [Data Import](../tools/data-import/README.md).

<a id="0-lesson-prep-tool-acquisition-tool-local-not-a-core-pipeline"></a>
## Lesson source preparation

The [Lesson Prep Tool](../tools/lesson-prep/README.md) acquires and prepares media locally and exports a portable `*.lesson-source.zip` package. It does not write Core, Keycloak, PostgreSQL, or R2 state. Core ingestion is a separate product boundary whose validation and storage policy remains [OQ-005](requirements/open-questions.md#oq-005--portable-lesson-source-ingestion-policy).

## Dataset import contract

An importer validates shape, provenance, license, checksum/version, and referential integrity before apply. A dry-run reports what would change. Apply records dataset identity, counts, status, and errors, and supports controlled reruns without silently duplicating content. Large source files and temporary fixtures remain outside Git.

## Lexicon

Global dictionary entries belong to Lexicon. Vocabulary SRS references Lexicon and owns learner review history. Import normalization, source attribution, and pronunciation variants belong to the Lexicon importer; Lesson lexical annotations can link terms without taking dictionary ownership.

## Grammar and TOEIC

Grammar datasets provide taxonomy, bank sets, questions, answers, and explanations where available. TOEIC datasets provide tests, passages, questions, answer keys, and media references. Importers normalize queryable structure in PostgreSQL and put large media in object storage. Missing or unapproved scaled-score conversion data must not be invented by the importer; see [OQ-004](requirements/open-questions.md#oq-004--toeic-scaled-score-conversion).

The offline builders prepare validated, versioned clean Lexicon and Grammar/TOEIC releases from immutable raw source before any production import. Their package envelopes (manifest, checksums, provenance, issue report) follow the [dataset release convention](../tools/data-import/RELEASE_FORMAT.md). Normal developer setup fetches pinned clean archives into `.data/releases/<domain>/<package-version>/` and verifies archive and package checksums. The production importers read those clean releases, record import runs and exact collection counts, and activate a successfully imported release per domain. Catalog publication and access policy remain runtime state across later releases. Raw source under `.data/datasets/` is maintainer input for rebuilding, not a team setup dependency. The legacy raw-source import scripts are not production importers; possessing a clean release alone does not mean Core has loaded it.

## Curriculum and artifacts

Curriculum content references existing owners rather than copying their durable records. Stable small defaults may use Flyway; large paths and learning content use versioned imports. PostgreSQL owns normalized/queryable records, object storage holds immutable media and raw debug artifacts, and databases persist keys rather than signed URLs.
