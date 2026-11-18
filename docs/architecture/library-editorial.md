# Library editorial and source releases

The [Lexicon](../requirements/lexicon-vocabulary.md), [Grammar/TOEIC](../requirements/grammar-toeic.md)
and [AI](../requirements/ai.md) requirements own product behavior. This document owns the runtime
storage and cutover contract; [data pipelines](../DATA_PIPELINES.md) owns package operations.

## Ownership and revisions

Lexicon owns dictionary structure, Grammar owns taxonomy and memberships, TOEIC owns test/group
placement. Assessment owns reusable question content and learner sessions, exposed through named
APIs. Platform content supplies technical JSON documents, immutable publications, assets and
suggestion ledgers; it does not call business modules. Dataset owns operator package jobs.

Unedited imported content stays in immutable source tables. Editing allocates one sparse
`content_document`, with optimistic revision on every mutation. Save never changes its publication.
Publication copies reviewed content to immutable `content_publication`, advances the pointer and
records actor/time and an optional note. Admin DTOs include `hasUnpublishedChanges`, comparing
draft JSON with its publication or immutable source; visibility-only revisions are not content
changes. Repeated publication of equal content is a no-op. Applying content updates preserves
hidden/archived visibility. Restore creates a new publication. Hidden/archived items retain history.
Only unpublished, unreferenced, non-source drafts may be permanently deleted. Audit survives deletion.

Catalog publications pin question publication IDs. A null question publication ID refers to the
immutable imported question ID, including after release changes. New question publications do not
silently change an existing catalog. Admin explicitly adopts an update and republishes the catalog.

## Import reconciliation

`content_source_binding` records the original identity and release for sparse edits.
`content_source_alias` maps later release IDs to that editorial identity. Compare normalized source
fingerprints, including question-position links and media, before activation. Changed or missing
source requires KEEP_EDIT, TAKE_SOURCE or EDITED with a reason and current draft revision.

TAKE_SOURCE disables the override only in the activation transaction. Saving after that choice
starts a draft with no publication pointer; old publications remain historical. Activation and
rollback retain locks through the switch and refuse stale/unresolved decisions. Admin-created
content has no source binding and survives release changes.

## Learner sessions and preproduction cutover

The catalog/session APIs replace the removed scaffold discovery/direct-submit controllers and unused
service stacks; no Mobile caller existed at cutover. Source attempts remain in their original tables.
New sessions snapshot question text/options/answer, group documents and opaque asset IDs at start.
They last 24 hours and are private to their learner. Save rejects stale revisions. Submit locks the
session, grades server-side and emits the existing completion fact exactly once in the transaction.
Repeated submit returns the stored result. Open discovery/session DTOs omit answers/transcripts;
results include only the requesting learner's pinned evidence. Scaled TOEIC scores remain null.

## Media and AI

Assets use content-addressed keys through ObjectStoragePort. Replacements create new asset IDs;
no referenced object is deleted. Upload inspects signatures and ffprobe streams and decodes with FFmpeg, with bounded files,
timeouts and protocol restrictions. HTML uses a jsoup safelist; Admin previews run in sandboxed
iframes. Tiptap normalizes harmless serialization differences automatically; text/cell-span loss preserves the
source and blocks unsafe editing with a precise warning. Raw source HTML stays separate. Discard
restores the saved snapshot and remounts editor/assistant state; late upload/AI responses cannot
adopt into a discarded editor. Remove/undo restores the removed item without overwriting newer edits.

TOEIC readiness deduplicates asset IDs and checks their object existence with at most eight parallel
checks per request and a 20-second batch limit. S3 HEAD uses a five-second call timeout and a
three-second attempt timeout. Missing metadata/objects remain blocking content issues; interrupted,
timed-out or unavailable storage checks return `MEDIA_CHECK_UNAVAILABLE`, never a false pass.

AI uses existing durable jobs and AiInvocationService. Core validates allowed fields and candidates;
acceptance writes a draft only, checks its revision and records individual applied results for
idempotency. TTS requires reviewed saved text and a supported Kokoro voice, produces a synthetic
asset for preview, and requires explicit acceptance. No generation occurs on page load. Mock WAV
is decodable test audio; it does not establish real provider quality.

Question suggestions within TOEIC carry test revision, placement, Part, question number/gap and
current saved group documents/transcript. Acceptance rejects stale test context; saving a question
pins its publication only into the selected test draft. Other catalog publications remain unchanged.

Lexicon Admin drafts retain item etymology numbers, recording attribution/cache references and
translation scope, tags, unlinked reasons, source context and original association. Missing metadata
in historical sparse drafts is hydrated by matching immutable child identities. Saves preserve that
provenance; learner mapping exposes sense source identity and pronunciation usage without storage keys. Candidate association enforces matching item
and sense IDs and never moves an item-scoped source candidate into another item.

Dictionary AI operations MATCH/TRANSLATE/EXAMPLE use a strict Core-owned `library_lexicon_v2`
JSON schema. MATCH may only select an available supplied candidate with exact text and compatible
source scope. Acceptance explicitly replaces/appends translation text, links that candidate and
marks NEEDS_REVIEW; generated translation is AI_GENERATED. TTS records its selected item scope.
The existing request ledger owns the active-job lock, stale revision guard and idempotent acceptance.

## Source-owned lexicon v2 and quality reports

V018 adds language-keyed definitions and source context to senses, and pronunciation context to
recordings. VI senses own stable source IDs and do not require English definitions. Variants group
recordings only with supplied notation/scope evidence, never accent-only inference. V020 carries
unresolved source issues into TOEIC groups so an absent source image remains a publication blocker.

V019 stores quality reports and the existing job ID. Domain handlers own rules; platform only stores
status/issues, fingerprints, rule version and timestamps. Fingerprints conservatively include sparse
editorial documents and active releases, so unrelated edits may also request a fresh check. Long
runs support cancellation and surface storage failures. Publication always validates current content.
V022 exposes current report state to catalog adapters through the technical `content_quality_current`
view; business modules do not query job internals. V024 and V025 invalidate reports from older rule
versions, in addition to content fingerprints. V025 requires rules 2.1.1, including exact TOEIC Part 2
option keys A/B/C, so a pass under the earlier option-count-only rule must be checked again.

V021 stores a separate maintenance pilot ledger. The Core operator command reserves peak-price
input/output bounds (including reasoning tokens) transactionally before each call; a single durable pilot is limited to 100
attempts and USD 1.50. Successful input/context/model/prompt hashes are cached. Failures keep the
reservation (uncertain provider charges), stop the run, and do not automatically retry. Results
are scoped language-field proposals with evidence; source answers and passages cannot be changed.
No HTTP paid-maintenance endpoint exists. Regular app routes retain their free-only policy.
