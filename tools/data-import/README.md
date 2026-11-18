# Lyreo Data Import

Large/reference datasets stay outside Git. Flyway owns schema and small reference defaults;
importers own versioned content, reconciliation and activation. See the
[release format](RELEASE_FORMAT.md) and [data pipeline contract](../../docs/DATA_PIPELINES.md).

## First clone

Run `make setup` to prepare the application and seed development reference data. Then choose
the content to install:

```bash
make setup-data DATASET=lexicon
make setup-data DATASET=grammar-toeic
make setup-data
```

Each command checks the target database, verifies or fetches the selected package from the pins
in [`.env.example`](.env.example), then imports and activates it. Grammar/TOEIC also checks
media storage alignment with Core. Keep the target credentials and any local archive overrides
in the ignored importer env. Reruns preserve existing rows, and unresolved editorial conflicts
stop activation. Use the reports from the CLI or the affected content's quality checks to inspect
issues. The runtime does not start or schedule dataset imports.

### Private Drive access

The pinned URLs may point to a `lyreo.archive-parts.v1` JSON transport index. Fetch verifies each
part and the reassembled archive, then validates package checksums before installation. Splitting
changes transport only; package bytes and archive SHA stay identical.

For private files, install rclone from its official distribution, authorize a Drive remote with an
account that already has access, and set `DATA_DRIVE_REMOTE` to its name (without the colon) in the
importer env. Use a private OAuth client when a shared rclone client is rate limited. Tokens remain
in rclone's local credential store, never in the repository, package or command examples. An empty
remote uses gdown for already-downloadable releases; it cannot bypass private Drive authorization.
No fetch command modifies sharing permissions.

A local archive path or `file://` URL supports offline setup. Relative paths resolve from
`tools/data-import/`. Fetch/check reuses valid installed releases, never overwrites another version,
and rejects archive path traversal/symlinks and incorrect checksums. Use the fetch script's `--force`
only to replace a damaged installation of the selected version.

```bash
make lexicon-fetch
make lexicon-check
make data-fetch
make data-check
```

## Storage

Full team integration uses each developer's PostgreSQL database and the configured shared R2
bucket. Keep credentials in ignored env files. Select `STORAGE_MODE=r2` in Core and importer;
`make data-storage-check` checks they agree. Isolated tests can use `STORAGE_MODE=local` with the
same resolved `LOCAL_STORAGE_ROOT`. There is no fallback to a different storage target.

Grammar/TOEIC files use `media/sha256/{prefix}/{sha256}`. APPLY verifies size and SHA-256 metadata,
or hashes object bytes if metadata is absent. Matching keys are reused; missing keys are uploaded;
mismatching objects fail without overwrite. ETag is not a SHA-256. A fresh developer database still
needs migration/import even when all 4,875 media already exist in R2. Lexicon recordings remain
attributed external references; import does not download or synthesize the dictionary audio.

## Import and activation

```bash
# Validation/dry-run: no database or storage writes.
make import-clean-lexicon
make import-clean-grammar-toeic

# Apply/reconcile inactive first.
make import-clean-lexicon ARGS="--apply --batch-size 1000"
make import-clean-grammar-toeic ARGS="--apply --batch-size 500"

# Explicit switch after all conflicts are resolved.
make import-clean-lexicon ARGS="--activate-only --activated-by operator"
make import-clean-grammar-toeic ARGS="--activate-only --activated-by operator"
# For a previously imported old release, select its directory with --release and --rollback-only.
```

Importers require PostgreSQL `DATABASE_URL` for APPLY, with bounded transactions and advisory locks.
Partial retries reuse stable package/release IDs; completed reruns validate and reconcile without
bulk duplication. Reports include exact counts/hierarchy and media uploads/reuse. Source snapshot
triggers reject mutation after a successful APPLY. Activation/rollback recheck successful import,
counts and current editorial decisions in the switch transaction. Source catalog policy and sparse
Admin edits retain their own ownership. Old publication/session evidence remains immutable.

## Maintainer rebuilds

Raw source under `.data/datasets/` is immutable maintainer input. Normal setup downloads clean
packages only. Use the locked Python 3.12 environment in `tools/data-import/.venv`.

```bash
tools/data-import/.venv/bin/python tools/data-import/build_lexicon_release.py \
  --raw-en .data/datasets/lexicon/raw-wiktextract-data.jsonl.gz \
  --raw-vi .data/datasets/lexicon/vi-extract.jsonl.gz \
  --output .data/builds/lexicon/2.0.0 \
  --archive .data/builds/lexicon-2.0.0.tar.gz --staging-dir .data/tmp \
  --generated-at 2026-11-18T00:00:00Z

tools/data-import/.venv/bin/python tools/data-import/validate_lexicon_release.py \
  --release .data/builds/lexicon/2.0.0 \
  --raw-en .data/datasets/lexicon/raw-wiktextract-data.jsonl.gz \
  --raw-vi .data/datasets/lexicon/vi-extract.jsonl.gz \
  --archive .data/builds/lexicon-2.0.0.tar.gz --report .data/builds/lexicon/verification.json

tools/data-import/.venv/bin/python tools/data-import/build_grammar_toeic_release.py \
  --raw .data/datasets/toeic --output .data/builds/grammar-toeic/2.0.0 \
  --archive .data/builds/grammar-toeic-2.0.0.tar.gz --generated-at 2026-11-18T00:00:00Z

tools/data-import/.venv/bin/python tools/data-import/validate_grammar_toeic_release.py \
  --release .data/builds/grammar-toeic/2.0.0 --raw .data/datasets/toeic \
  --archive .data/builds/grammar-toeic-2.0.0.tar.gz --report .data/builds/grammar-toeic/verification.json
```

Builders reject existing output paths. Version metadata must match the intended release; path names
alone do not change it. Preserve the same `--generated-at` for reproducibility checks. Validate with
raw source before publishing, upload privately, download again and verify archive SHA before pinning
new `.env.example` defaults. Keep all old source/package versions. Dataset/credential/generated large
files are Git-ignored; never distribute a database snapshot containing accounts or secrets.

### Lexicon v2

One case-sensitive English headword identity holds source-owned VI and EN senses. VI meanings and
examples do not require a matching English sense. EN meanings are reference content unless a
separate evidence-backed relation exists. Legacy flat translations remain candidates, not verified
sense matches. Runtime `headwordId` preserves Lesson/SRS identity; source snapshot IDs are
release-scoped. Package sense IDs/provenance retain each source occurrence.

Pronunciation metadata distinguishes source notation from IPA, exact region labels from usage scope,
and variants from recordings. An audio/notation relation requires the same source record or explicit
editorial evidence; matching accent alone is insufficient. Unknown use remains explicit. Recording
attribution/source links stay intact. See the [schema contract](RELEASE_FORMAT.md#lexicon-clean-schema-200).

### Grammar/TOEIC v2

The builder restores combined question/options translations and parses multiple-document passages
with text/table/order preservation. Ambiguous source repairs are pinned by source hash in
`toeic_document_repairs.json`. Runtime quality is separate from the immutable source issue report.
The 2019 Test 5 questions 62–64 source references a nonexistent image. The independent copy uses
audio alone for exactly those three questions and shows the graphic with questions 65–67. The builder
records the obsolete reference as a source correction only for the reviewed raw row.
[Part 3 comparison](https://zenlishtoeic.vn/stm-quizzes/part-3-test-5-2019/)
The Admin publication validator keeps unresolved required-source issues blocking.

## Bounded DeepSeek maintenance

Prepare a deterministic gap inventory before paid execution:

```bash
tools/data-import/.venv/bin/python tools/data-import/prepare_maintenance.py \
  --grammar .data/releases/grammar-toeic/2.0.0 --lexicon .data/releases/lexicon/2.0.0 \
  --output .data/maintenance
make data-ai-pilot
# Read today's official model prices, then explicitly execute within the durable limit:
DEEPSEEK_PRICING_CONFIRMED_ON=YYYY-MM-DD make data-ai-pilot ARGS=--execute
```

Only the operator script reads `DEEPSEEK_KEY` from root `.env`; no copying into frontend/app env is
needed. Core owns prompt, schema, allowed fields, cache and durable reservations; FastAPI executes.
Results remain proposals. Failures keep cost reservations and are not retried automatically. The
[AI requirement](../../docs/requirements/ai.md#br-ai-006--bounded-library-maintenance-pilot) owns the
accepted spending/review policy. Regular app routes retain their free-only policy.

The command uses DeepSeek Flash with high thinking and an 8,192-token combined reasoning/final
output ceiling. Confirm the current [official pricing](https://api-docs.deepseek.com/quick_start/pricing/)
and [thinking contract](https://api-docs.deepseek.com/guides/thinking_mode/) before execution. Resume
uses canonical input/context/model/prompt hashes across JVM processes. Keep the original pilot input
for a cache check; write a new inventory to a separate output directory instead of replacing it.
`candidates.jsonl` contains actual field gaps, while existing Grammar explanations are optional
quality reviews. Part 1 explanations without photograph input are counted for visual review and
excluded from this text-only capability.

To audit the final queue, add `--final` to the preparation command and set
`DATA_MAINTENANCE_INPUT` to its `final.jsonl` and `DATA_MAINTENANCE_OUTPUT` to a review file.
`make data-ai-pilot ARGS=--batch` prints the queue and reservation estimate. Execute the sample
with `ARGS='--batch --sample --execute'`, then run the full queue with `ARGS='--batch --execute'`
after review. Both execute modes require the pricing confirmation date. A rate limit stops new
requests and exports completed results. A subsequent batch command reuses the saved results and
the existing reservations for requests rejected with HTTP 429.

After checking complete source context, record explicit decisions in an ignored JSON file:

```json
{
  "format": "lyreo.maintenance-review.v1",
  "reviewer": "reviewer-name",
  "reviewed_at": "2026-11-18T00:00:00Z",
  "accepted": [
    {"fingerprint": "successful-work-sha256", "targetId": "item-id", "field": "explanation_vi", "reason": "Checked against the complete transcript, options and source answer"}
  ]
}
```

Build a new Grammar/TOEIC version from accepted language changes:

```bash
tools/data-import/.venv/bin/python tools/data-import/maintenance_overrides.py \
  --results .data/maintenance/pilot-results.json --decisions .data/maintenance/review-decisions.json \
  --release .data/releases/grammar-toeic/2.0.0 --output .data/maintenance/reviewed-language-overrides.json
tools/data-import/.venv/bin/python tools/data-import/build_grammar_toeic_release.py \
  --raw .data/datasets/toeic --output .data/releases/grammar-toeic/2.0.1 \
  --archive .data/releases/grammar-toeic-2.0.1.tar.gz --version 2.0.1 \
  --overrides .data/maintenance/reviewed-language-overrides.json
tools/data-import/.venv/bin/python tools/data-import/validate_grammar_toeic_release.py \
  --release .data/releases/grammar-toeic/2.0.1 --raw .data/datasets/toeic \
  --archive .data/releases/grammar-toeic-2.0.1.tar.gz
```

The review tool accepts only proposed language fields from successful scoped work. It pins the
whole original item hash, preserves absent versus explicit-null fields, and adds field provenance.
Validation reverses only those changes, checks protected source content, and can independently
reconstruct the baseline from raw source. It does not accept answer, option, passage or media edits.
The [recorded pilot](../../docs/DATA_PIPELINES.md#reviewed-language-maintenance-2026-11-18) describes
the accepted subset and remaining limitations; the offline review tool currently owns Grammar/TOEIC,
not Dictionary sense changes.

## Reviewed source wording repairs

Final v2 rebuilds also accept a narrow manual source repair bundle with `--source-repairs`.
Grammar/TOEIC 2.0.2 combines the twenty reviewed pilot explanations with three directly
verified source corrections; Lexicon 2.0.1 corrects one example and its rendering offsets.
The package keeps exact before/after text, row hashes and review evidence in
`editorial/source_repairs.json`. Original raw text and old releases remain available.

For a maintainer rebuild, pass the original accepted `editorial/language_overrides.json`
with `--overrides` for Grammar/TOEIC, plus the corresponding package's `--source-repairs`
bundle and an explicit `--version`. Lexicon accepts `--version` and `--source-repairs` as well.
Use the source package's `generated_at` and the same builder when checking reproducibility;
write to a new output directory. Run the independent validator with raw input and archive
after every rebuild. Normal `make setup-data` consumes the effective package directly and
needs neither repair tooling nor raw corpus.

## Legacy raw scripts

`import_lexicon.py`, `import_grammar.py` and `import_toeic.py` remain maintainer diagnostics. They are
not the clean release setup path and do not replace validation, reconciliation or activation.
