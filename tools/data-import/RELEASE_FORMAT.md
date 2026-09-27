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
