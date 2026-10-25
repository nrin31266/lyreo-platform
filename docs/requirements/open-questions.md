# Open questions

Only unresolved product or architecture decisions belong here. Implementation tasks and test gaps belong in the work tracker. Historic `gap-*` anchors preserve inbound links; `OQ-*` is the canonical ID.

<a id="gap-001--youtube-p0-hay-sau-lesson-mvp"></a>
## OQ-001 — YouTube in the first Lesson release

- **Status**: resolved, 2026-10-25
- **Decision**: YouTube-first Admin authoring; Text/Audio UI and mobile playback/practice deferred.
  See [D-024](../DECISIONS.md#d-024--integrated-youtube-lesson-authoring-and-free-only-research-2026-10-25).

<a id="gap-002--nguong-hoan-thanh-dictation-70"></a>
## OQ-002 — Dictation completion threshold

- **Status**: open
- **Type**: decision-needed

[Lesson requirements](lesson.md#dictation) distinguish attempt score from completion, but do not approve a passing threshold. Product ownership must define the threshold or policy before completion events and rewards rely on it. The current scaffold value is implementation evidence only.

<a id="gap-007--kpi-va-nguong-nfr-chua-duoc-chot"></a>
## OQ-003 — NFR targets and success metrics

- **Status**: open
- **Type**: decision-needed

The [PRD](../product/prd.md) and [non-functional requirements](non-functional.md) do not set measurable latency, availability, learning outcome, or model quality targets. Collect a baseline and decide targets before claiming compliance.

<a id="gap-008--toeic-scaled-score-conversion-table"></a>
## OQ-004 — TOEIC scaled score conversion

- **Status**: open
- **Type**: decision-needed

[TOEIC requirements](grammar-toeic.md#toeic) require an approved, versioned conversion source before presenting scaled scores. Select its provenance and versioning policy; raw counts must not be presented as official scaled scores meanwhile.

<a id="gap-011--ingestion-portable-lesson-source"></a>
## OQ-005 — Portable Lesson source ingestion policy

- **Status**: closed, 2026-10-25
- **Decision**: integrated Admin authoring replaces the standalone ZIP workflow; portable package
  ingestion is outside this phase. Lesson Prep source and hooks were retired on 2026-10-26 after
  replacement regression coverage and functional OIDC publication/read-only verification; local
  artifacts remain intact. Human teaching/timing quality and independent billing checks remain
  separate unverified gates. See [Lesson Build](../features/lesson-build.md).

## OQ-006 — Production versus deploy terminology

- **Status**: open
- **Type**: question

`compose.prod.yml` and `scripts/verify-prod-env.sh` imply a production contract. Decide whether they describe true production or a deployable baseline before renaming or documenting production guarantees.
