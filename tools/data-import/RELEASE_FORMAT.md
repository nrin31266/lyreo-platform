# Lyreo dataset release convention

This document defines the small envelope shared by independently versioned data packages. It does not define a universal content schema or a generic ingestion framework. Each domain owns its builder, records, validation rules, and release lifecycle.

Archive filenames use the domain and full package version, for example `grammar-toeic-1.0.1.tar.gz` and `lexicon-1.0.0.tar.gz`. Install each version beside earlier versions under `.data/releases/<domain>/<package-version>/` so rollback does not depend on overwriting a mutable path.

## Package envelope

Every archive contains one root directory with:

- `manifest.json`: `format=lyreo.dataset-release`, `manifest_schema_version`, `package` (`domain`, `version`, `schema_version`, `generated_at`), `builder` (`name`, `version`), `source`, `files`, `counts`, `validation`.
- `validation_report.json`: validation status, domain counts, issue summary, and issues with a stable code, status, source ID, and explanation.
- Domain-owned JSONL collections and any media. Every collection has one JSON object per line, stable IDs, UTF-8, and deterministic sorting by ID.

The `files` array inventories every payload file except `manifest.json`, with package-relative path, SHA-256, byte size, and JSONL record count when applicable. The manifest is excluded from its own inventory to avoid a circular checksum. Archive checksum is reported outside the archive. Paths must be relative and must not escape the package root.

For the current Grammar/TOEIC package, about 4,900 payload entries produce a roughly 1 MB manifest beside a roughly 2.7 GB archive. Keeping the inventory inline makes a complete path/size/hash check direct. Reconsider a separate inventory only if a future domain package makes this materially costly; each domain package has its own manifest.

`source.files` records immutable input path, byte size, and SHA-256; `source.media_sha256_manifest` hashes the sorted source media path/hash pairs. Source links, browser sessions, credentials, and signed URLs are not release dependencies. Domain records may retain a source reference as provenance, but serving content uses release-owned asset IDs and paths.

## Version and reproducibility

- `package.version` changes for a changed dataset release within its own domain.
- `package.schema_version` changes when the clean record contract changes.
- `manifest_schema_version` changes only when this envelope changes.
- `builder.version` identifies transformation logic. The Grammar/TOEIC builder accepts `--generated-at`; pass the same timestamp with unchanged raw source and builder to reproduce byte-identical JSONL, manifest, and tar.gz.
- Tar members are sorted, regular files only, with zero mtime/uid/gid and fixed mode; gzip mtime is zero. Media names use content SHA-256 and detected MIME extension.

The builder refuses an existing output path and never writes under raw source. A release is published only after the domain validator passes checksum, relation, content, media, and representative reconstruction checks. Keep a package version immutable after publication; fix a release by issuing a new version and recording its source/builder lineage.

The canonical Grammar/TOEIC archive uses `grammar-toeic-<package-version>/` (for example `grammar-toeic-1.0.1/`) as its single tar member root. The fetcher verifies this root and installs it under `.data/releases/grammar-toeic/<package-version>/` after checking the manifest's exact package and schema versions. Future packages similarly use their canonical domain and version as the tar member root.

## Reviewed source repairs

A changed version may include `editorial/source_repairs.json` with
`format=lyreo.reviewed-source-repairs.v1`, its domain and an explicit list of repairs.
`source.source_repairs` records its path/count; the normal file inventory verifies the bundle.
Each repair identifies a collection, stable row ID and allowed field, the canonical
SHA-256 of the full original row, exact before/after values, reviewer, time, reason and evidence.
Builders reject stale rows, wrong domains, duplicate targets and unsupported fields.

The narrow allowlist covers question Vietnamese explanations/translations, group Vietnamese
translations, Lexicon examples and corroborated truncated endings of incorrect TOEIC choices.
A choice repair preserves the order, every other choice and the keyed answer. For an entire
question demonstrably transcribed from the wrong context, a TOEIC-only transcription repair
requires two independent source references and replaces the stem, four choices, translation and
rationale together. The original keyed answer and option order are preserved. Source passages,
media and identities are protected. The independent validator reverses repairs, verifies every original row hash, then
compares against raw source when supplied. The report retains `accepted_source_repairs`; the bundle
retains the original text. Raw files and earlier releases remain immutable. AI-reviewed changes
stay in their separate `editorial/language_overrides.json` bundle.

## Issue states

Use `FIXED`, `NORMALIZED`, `DERIVED`, `QUARANTINED`, `SOURCE_ONLY`, or `UNRESOLVED`. An issue code describes the anomaly; the state describes its treatment. `QUARANTINED` means the source row is retained in a quarantine collection and excluded from active content. `SOURCE_ONLY` means the source value is retained in a named source-only collection or field and has no inferred active relationship or behavior. `UNRESOLVED` is allowed only if the domain validator establishes that the affected content still has a safe, complete fallback or the record is withheld. A release report must retain issues even when transformations repair them.

The domain-specific importer later needs only the clean package contract. Download/verification tooling can first check the common envelope and inventory, then hand the package to its domain importer. New domains can reuse these conventions without inheriting Grammar/TOEIC parsing code.

## Lexicon clean schema 1.1.0

Lexicon package `1.0.1` uses canonical schema `1.1.0` and builder `1.0.1`; import and
validation also support immutable schema `1.0.0` for old snapshots and rollback.
Entry/item/sense/form/pronunciation records and English-native translation semantics
are unchanged. `QUALIFIER_MATCH` remains a deterministic heuristic, not verified meaning.

Auxiliary VI translation records add nullable `source_pos` and internal `source_context`.
Exactly one item compatible with a known source POS permits `ITEM_CANDIDATE` with
that item and no sense. Multiple/zero compatible items or missing/unknown source POS
require `ENTRY_CANDIDATE` with null item/sense. English-native candidates remain
source-owned item candidates, irrespective of how many other same-POS items exist.
`source_scope` describes origin, not target certainty. `DIRECT_SENSE` requires explicit
source evidence for a sense; `UNLINKED` carries no item/sense association. Neither is
newly emitted by this builder. All hierarchy references must belong to the same entry/item.

Every eligible auxiliary raw gloss occurrence becomes a separate row. Its UUIDv5 in
`uuid.NAMESPACE_URL` uses
`lyreo:lexicon:trans:viwiktionary:occurrence:{dump_sha}:{line}:{sense_ordinal}:{gloss_ordinal}`.
Line is the physical decompressed JSONL line, before blank/language filtering; all
ordinals are positive one-based source positions. Identity never depends on target
item, link status or gloss normalization. Duplicate text remains separate, and ID
collision fails instead of replacing a row. Source order is provenance, never alignment.

`source_context` has exactly `record_line`, `sense_ordinal`, `gloss_ordinal`,
`record_context`, and `sense_context`. Filename/dump SHA come from the immutable
manifest's auxiliary VI source identity; a record UUID can be derived, not duplicated
on every occurrence. Selected values preserve order, explicit nulls and absent fields.

| Context | Allowed source fields |
|---|---|
| Record | `pos`, `pos_title`, `tags`, `raw_tags`, `etymology_texts`, `etymology_links`, `notes` |
| Sense | `glosses`, `tags`, `raw_tags`, `topics`, `categories`, `form_of`, `alt_of`, `examples` |
| Each example | `text`, `translation`, `roman`, `ref` |

No raw record/sense blob or catch-all is allowed. Rendering offsets and unrelated
record metadata (page categories, forms, sounds, other translations and lexical
relation lists) are deliberately omitted. Known omissions are enumerated in
`lexicon_vi_context.py`. Preserve/omit/unknown field counts are recorded separately
at record, sense and example levels in `validation_report.vi_source_field_inventory`.
Unknown fields require classification before publication; they are not auto-copied.
Etymology/notes/labels are selected source evidence, not cross-source item discriminators.

Clean-only validation checks structure, types, scopes, compatible-item cardinality,
occurrence IDs, inventory and accounting. Publication additionally supplies `--raw-vi`
to independently verify the checksum, complete eligible occurrence set, selected
projection and source accounting. Clean-only validation does not claim to independently
prove raw completeness. Raw source is maintainer evidence, not an import dependency.

PostgreSQL persists selected context internally without backfilling frozen old rows.
Learner Detail selects only `source_pos` and exposes `sourcePos`; occurrence context
and locators are absent from its domain projection/DTO/OpenAPI. Existing release-level
`sourceMetadataJson` and attribution retain their prior contract.

Canonical structural IDs remain stable on the same pinned input/EN algorithm; public
PostgreSQL/API snapshot IDs are release-scoped and change for a new manifest/release.
Only `headwordId` is the lifetime Vocabulary reference. VI occurrence IDs are stable
for the pinned dump, not across upstream reordering or recompression.


## Lexicon clean schema 2.0.0

Package 2.0.0 retains one case-sensitive entry/headword identity, adding source-owned VI items,
senses, examples, forms and sounds. `definition_en` may be null when `definitions.vi` supplies a
valid meaning. `definitions` is a language-keyed object; `source_context` identifies source/locator
for internal reconciliation. A language string does not imply an equivalent meaning in another
source. Old sense/candidate links are not promoted by POS/ordinal/lexical similarity. Each native
sense and occurrence keeps its own stable package ID; the importer maps snapshot IDs within the
release while retaining lifetime `headwordId`.

Pronunciations add `pronunciation_context`: form, notation/system, exact source regions/labels,
usage/scope evidence and source record identity. `IPA` and `SOURCE` notation systems remain distinct.
An unspecified scope stays `UNSPECIFIED`; one record containing both notation and audio supplies
link evidence, while matching accents across records do not. Rendering may group duplicate
recordings only within the same proven usage/notation context and source identity/checksum.
Selected provenance stays internal; learner DTOs expose the source name and usage groups without
source locators or storage keys. The source field inventory and raw validator check coverage.

## Grammar/TOEIC package 2.0.0

The additive clean schema remains 1.0.0. Shared annotations retain
`question_options_translation_vi` even when a separate question/options translation cannot be
reliably split. Normalized ordered `toeic/documents.jsonl` preserves visible text, tables and
source question links. Hash-pinned source corrections are recorded as fixed issues; unresolved
required source media remains in group `source_issues`, not silently removed after import.

## Multipart transport

A private Drive file can contain a JSON index with `format=lyreo.archive-parts.v1`,
`archive_sha256` and ordered `parts` (`url`, `size_bytes`, `sha256`). The transport reader checks
part bounds and hashes, concatenates in order and verifies the original archive SHA before normal
package validation. Remote index children must be HTTPS Drive files. Index URLs are not package
content or credentials. An archive split never changes its package/manifest version or checksum.
