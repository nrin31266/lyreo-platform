# Lyreo dataset release convention (v1)

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
