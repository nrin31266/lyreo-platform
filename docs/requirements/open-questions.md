# Open questions

Only unresolved product or architecture decisions belong here. Implementation tasks and test gaps belong in the work tracker. Historic `gap-*` anchors preserve inbound links; `OQ-*` is the canonical ID.

<a id="gap-001--youtube-p0-hay-sau-lesson-mvp"></a>
## OQ-001 — YouTube in the first Lesson release

- **Status**: open
- **Type**: conflict

The [PRD scope](../product/prd.md#6-scope-va-uu-tien) includes YouTube in Core Lesson, while its earlier release direction listed Text/Audio first. Product ownership must decide the release boundary before the first Lesson release is scoped. The technical adapter alone does not decide priority or policy approval.

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

- **Status**: open
- **Type**: decision-needed

The [Lesson Prep package](../DATA_PIPELINES.md#0-lesson-prep-tool-acquisition-tool-local-not-a-core-pipeline) needs a Core ingestion contract. Before implementing import, decide validation, media checksum verification, and storage handling. Endpoint and UI implementation are separate tasks.

## OQ-006 — Production versus deploy terminology

- **Status**: open
- **Type**: question

`compose.prod.yml` and `scripts/verify-prod-env.sh` imply a production contract. Decide whether they describe true production or a deployable baseline before renaming or documenting production guarantees.
