# Lyreo Architecture Decisions

This is the compact decision log. If a future change contradicts one of these, update the decision explicitly instead of silently drifting architecture. Unless otherwise noted, D-001–D-016 were locked on 2026-09-06.

## D-001 — Modular Monolith

Status: Accepted.
Use Spring Boot + Spring Modulith. Do not split business modules into network services by default.

## D-002 — No Kafka at MVP

Status: Accepted.
Decision: do not introduce Kafka at MVP.

## D-003 — No Redis at MVP

Status: Accepted.
Decision: do not introduce Redis at MVP.

## D-004 — Java orchestrates AI workflows

Status: Accepted.
Business/product prompts, output expectations and workflow live in Java. FastAPI executes capabilities.

## D-005 — PostgreSQL Job Queue

Status: Accepted.
Use `FOR UPDATE SKIP LOCKED`, lease, heartbeat, retry, cancellation and fencing.

## D-006 — R2 for object artifacts

Status: Accepted.
PostgreSQL stores normalized/queryable state; R2 stores audio/images/recordings/raw artifacts. DB stores object key, not presigned URL.

## D-007 — Flyway owns schema

Status: Accepted.
Hibernate `ddl-auto=validate`; large datasets use importers.

## D-008 — Content / Annotation / Activity separation

Status: Accepted.
Contextual notes are reusable support data and are not automatically dedicated practice modes.

## D-009 — Lexicon and Vocabulary separate

Status: Accepted.
Lexicon is global dictionary; Vocabulary is learner SRS referencing Lexicon.

## D-010 — Progress ownership

Status: Accepted.
Each business module owns detailed progress. Analytics consumes events and projects summaries.

## D-011 — Configurable experience with precedence

Status: Accepted.
Decision: use layered configuration with domain invariants remaining code/schema rules.
Authoritative precedence and override semantics: [`CONFIGURATION.md`](CONFIGURATION.md).

## D-012 — Login required initially

Status: Accepted.
Avoid guest progress/SRS/Curriculum/Diamond merge complexity in first release.

## D-013 — Brand

Status: Accepted.
Product `Lyreo`; mascot `Lyrebird`; Java namespace `com.lyreo`.

## D-014 — Runtime config documents

Status: Accepted.
Admin-tunable module policy uses versioned PostgreSQL JSONB documents behind typed module policy repositories. Do not spread raw config maps through domain code.

## D-015 — Local speech runtime remains a Python capability implementation

Status: Accepted.
WhisperX STT and forced alignment run in FastAPI. A dedicated model host can be introduced without moving business orchestration out of Core. Docker is packaging, not domain architecture.

## D-016 — Chat boundary exists but is low priority

Status: Accepted.
Keep module boundary so future English-only tutor can reuse AI routing, but do not prioritize chat over Lesson/TOEIC/Vocabulary/Curriculum core flows.

## D-017 — Semantic theming and shared localization (2026-09-07)

Status: Accepted.
Use semantic design tokens with light/dark themes and `@lyreo/i18n` for intentional
shared translations. Platform adapters own locale/theme persistence. The shared visual-foundation
requirement is superseded by D-025; localization remains shared.

## D-018 — Platform-specific UI component ownership (2026-09-07)

Status: Accepted.
Admin uses Tailwind/shadcn-style owned Web components; Mobile uses NativeWind/RNR-style owned Native
components. Share semantic contracts, not component implementations/configuration.

## D-019 — External dataset bootstrap (2026-09-07)

Status: Accepted.
Grammar/TOEIC raw data stays outside Git. Developer setup may fetch the configured shared ZIP into a
Git-ignored local data directory and must validate importer-facing structure before use. Semantics: `DATA_PIPELINES.md`.

## D-020 — Task-routed documentation ownership (2026-09-09)

Status: Superseded by D-023.
The repository uses `docs/README.md` as its single task/domain/code route and assigns product,
requirement, story, feature, technical and evidence facts to separate owners. The legacy master path
is a compatibility index only. Source: the documentation setup directive accepted for this change;
this decision governs documentation structure, not unresolved product behavior.

## D-021 — RFC 9457 Problem Details and Core HTTP Foundation (2026-09-09)

Status: Accepted.
Decision: Core public HTTP API uses direct resource/DTO returns for success (no global envelope) and
RFC 9457 Problem Details (`application/problem+json`) with stable uppercase error codes and correlation ID
for errors. Generic Java exceptions are never mapped wholesale to client 4xx responses; infrastructure
failures yield safe 500 responses without leaking internals. Shared baseline semantic application errors
(`ResourceNotFoundException`, `StateConflictException`, `RequestValidationException`) are owned by
`libs/contracts/errors` (an open shared contract module) so that business modules and application services
can express application boundary outcomes without coupling domain logic to Spring MVC or HTTP runtime
classes. Swagger/OpenAPI is generated via springdoc with dev/test enablement and production disabled by default.
Rationale: [`http-api-contract.md`](architecture/http-api-contract.md).

## D-022 — Standalone Lesson Prep Tool and Portable Package Format (2026-09-13)

Status: Superseded by D-024. The former standalone operator workstation exported portable lesson
source packages without mutating Core, identity or object storage. Its source and hooks were retired
on 2026-10-26 after integrated functional verification; the owner authorized purging remaining local
environment/cache/audio artifacts on 2026-10-31. Detailed former contracts remain in Git history.
Active source preparation belongs to [Lesson Build](features/lesson-build.md); portable ZIP ingestion
is outside this phase.

## D-023 — Lean documentation and repository command ownership (2026-09-25)

Status: Accepted. Supersedes D-020's document taxonomy and evidence register.

Context: The foundation scaffold produced duplicate technology inventories, implementation-status documentation, and commands repeated across Make, scripts, and CI.

Decision: Route tasks through `docs/README.md`; keep one owner per normative fact. Requirements state intended behavior, code and manifests state current implementation, and fresh checks state current evidence. Keep architecture at C4-lite boundaries. The root Makefile is the stable command interface, owner scripts implement complex actions, and CI consumes Make targets. Academic traceability is a snapshot outside canonical engineering docs.

Consequences: Document merges and valid implementation refactors are possible without updating a fixed leaf-doc taxonomy. Link/ID checks remain structural; behavior and module boundaries are verified by tests and targeted validators.

Confirmation: `make validate-docs`, `make validate-repo`, `make test-tooling`, and relevant application checks.


## D-024 — Integrated YouTube Lesson authoring and free-only research (2026-10-25)

Status: Accepted. Supersedes D-022 for the current authoring phase.

Admin authors one contiguous YouTube range per Lesson, reviews transcript and karaoke before
semantic generation, reviews contextual annotations/candidates, and publishes explicitly. Core owns
business prompts, lifecycle, validation and PostgreSQL checkpoints; FastAPI only executes technical
capabilities. Text/Audio authoring and mobile playback are deferred, not new work for this phase.

Draft revision and transcript revision are separate: candidate selection cannot invalidate approved
content. Publication atomically projects immutable content and selected candidate membership. Old
writers must not mutate published content or cascade-delete learner attempts/progress. Additive Flyway
migrations preserve existing records; scaffold APIs and implementation are not product contracts.

Free-only execution permits only benchmarked allowlisted models and requires operator confirmation
of the external projects' free billing path. No billing changes or paid fallback are automatic.
Quota waits are durable, resumable jobs. Existing encrypted Core credentials remain authoritative.
See the bounded [ASR evidence](research/lesson-authoring-asr.md) and
[semantic evidence](research/lesson-authoring-semantic.md); these are measured research, not release guarantees.

Standalone workstation retirement required regression coverage, replacement workflow verification
and no runtime dependency. These conditions were met on 2026-10-26: the real OIDC workflow
completed publication, reload showed immutable content, direct mutation returned 409, and replacement
regressions cover cancellation, late results, quota/resume and atomic publication. The standalone
source and setup/CI hooks were retired. Remaining local environment/cache/audio artifacts were
subsequently purged at the owner's request on 2026-10-31; persisted business data remains protected.
This does not approve human timing/pedagogical quality or independently verify provider billing.
The old package ingestion proposal is not part of this phase. Feature owner:
[Lesson Build](features/lesson-build.md).

## D-025 — Independent Admin foundation and conditional timing policy (2026-10-26)

Status: Accepted.
Admin adopts the full shadcn `b1aIuOuyO` preset (Radix Luma, Stone/Blue, Inter, small radius)
and owns its colors, typography, spacing and component states independently of Mobile. Remove
unused prototype components/adapters after consumer and regression checks; preserve real domain/API
contracts. This supersedes D-017 only for cross-platform visual-token sharing.

Lesson timing reuse is a Core quality-policy decision, independent of STT/alignment providers.
Keep raw draft evidence; require local alignment temporarily until human-referenced benchmarks and
karaoke listening review support reuse. This temporary default is configuration, not an architectural
requirement to align every lesson forever. Source preview prepares only an explicitly confirmed
range; a long-source full download is not a prerequisite.

## D-026 — Admin management lifecycle and development migration consolidation (2026-10-26)

Status: Accepted.

Admin keeps the unmodified shadcn `b1aIuOuyO` palette initialized with the pointer option. Application
styles do not override preset colors. Axios owns HTTP transport, TanStack Query owns server state and
active-job polling, and React Hook Form/Zod own forms and client validation. Local editor state stays
with the editor. Future sidebar entries are explicitly unavailable, not implemented feature claims.

Display metadata and availability are separate from immutable published learning content. Drafts may
be soft-deleted after jobs stop; published lessons may be unlisted/relisted while learner history is
preserved. This extends D-024/D-025 without authorizing in-place published-content editing.

The owner confirmed V007–V010 exist only in disposable development databases and authorized their
consolidation into V007 plus a local application DB reset. V001–V006 remain unchanged. This is a
one-time development exception; future schema changes append migrations and deployed histories must
not be rewritten or repaired to hide changed checksums.

## D-027 — Direct source editing without rights attestation (2026-10-26)

Status: Accepted.

Source URL input opens the editor after metadata inspection; no rights-attestation checkbox or
boolean is required. Source selection is a selection timestamp, not proof of permission. Explicit
waveform preparation and AI processing remain separate actions. This supersedes D-024's mandatory
operator acknowledgment step without establishing source publication rights. Workflow actions share
one persistent bar; only workspace contents scroll at narrow widths as well as desktop.

## D-028 — Luma Stone/Blue presentation for Admin and Mobile (2026-10-31)

Status: Accepted.

Admin adopts the full shadcn `b1aIuQ2XC` preset: Radix Luma, Stone/Blue, Raleway, Remix Icon,
large radius and translucent menus. Existing component behavior, translations and accessibility
extensions survive preset replacement. Admin layout and visual verification prioritize desktop/laptop
at 1280px and 1440px; retain existing responsive behavior without a separate phone/tablet design pass.

Mobile follows the same visual direction through its own learner semantic tokens, NativeWind and
React Native components. Embed Raleway through the Expo font plugin, use native-compatible colors
and a lighter dark-mode blue for text contrast, and preserve safe areas and touch targets.

This supersedes the old preset selection in D-025/D-026, while retaining their independent platform
ownership and all API, workflow and timing policies. The owning READMEs describe each foundation.


## D-029 — App-local theme ownership; shared translations only (2026-10-31)

Status: Accepted.

Remove the shared design-system workspace package. Mobile owns its existing palette, foundation,
brand metadata and theme types in `apps/mobile/src/theme`; Admin owns success/warning colors in
its theme CSS alongside the shadcn preset. Each app consumes its own theme without importing
presentation values from the other app. Keep existing visual values, font configuration and theme
preferences unchanged. Locale metadata and translations remain shared through `packages/i18n`.

Repository guardrails allow color definitions only in the designated platform token owners and
continue to reject raw colors or direct primitive access in feature code. This completes the physical
separation of the visual foundations described in D-025/D-028; their other policies remain in effect.


## D-030 — Consolidate unreleased authoring Admin migrations (2026-11-17)

Status: Superseded in numbering/history handling by D-031; consolidation scope remains accepted.

V012–V015 belong to one uncommitted/unreleased Admin authoring phase. Consolidate their SQL in
original order into V015, retaining the highest version so future migrations start at V016 and
never reuse a removed version. V001–V011 remain unchanged. Fresh databases apply twelve files.

The local development database already applied all four original scripts. After checking that
exact history and the completed schema, back up its history and use Flyway repair once to align
the consolidated V015 and mark V012–V014 deleted; preserve all application data. Validate history
and check for pending migrations before restarting Core. This narrow owner-authorized development
exception does not apply to deployed databases, partial histories or later migration changes.


## D-031 — Number the consolidated Admin migration contiguously (2026-11-17)

Status: Accepted by the owner; supersedes D-030 numbering/history handling.

The owner requests contiguous numbering after V011 for this unreleased, local-only Admin phase.
Rename the consolidated V015 to V012 without changing its SQL; fresh databases apply V001–V012
and future migrations append from V013. V001–V011 remain unchanged.

For the one verified local development database already repaired under D-030, back up Flyway
history, verify the consolidated checksum and complete schema, and replace only that phase's
original/deleted history entries with one successful V012 entry. Validate Flyway and Hibernate
before resuming development. Preserve all application data. This one-time exception does not
authorize automatic repair, rewriting deployed databases or changing partially applied histories.

## D-032 — Sparse library drafts and pinned learner sessions (2026-11-17)

Status: Accepted in the Admin library implementation plan.

Keep immutable imported snapshots and allocate editorial documents only for changed/new content.
Separate draft revision, immutable publication and visibility/access. Assessment owns shared question
content and session snapshots; Grammar/TOEIC own membership/placement. Source IDs remain immutable
references and catalog publications explicitly pin editorial question revisions. Session submission
uses the pinned answer and emits completion once. Retire scaffold direct-submit HTTP paths in this
preproduction cutover. See [editorial architecture](architecture/library-editorial.md).

## D-033 — Reuse cloud media and isolate authoring files from datasets (2026-11-17)

Status: Accepted; existing R2 objects were confirmed by the owner.

Fresh-clone setup migrates, seeds and imports both clean releases, reusing verified content-addressed
media already present in the configured R2 bucket. Core and importer must target the same database
and storage. Admin starts fixed package-ID operations from server staging, through a durable job and
fenced importer; it never accepts shell commands or arbitrary paths. Small CSV/versioned JSON files
create drafts through domain validation and are separate from dataset release activation.

E2E infrastructure is deferred under the owner's explicit allowance while transactional integration,
component checks and real local browser flows establish this cutover. This does not declare the
planned full cross-browser/CI E2E matrix complete. See [testing](TESTING.md).

## D-034 — Source-owned bilingual senses and bounded maintenance (2026-11-18)

Status: Accepted in the final library completion plan.

Use the Vietnamese source sense structure for Vietnamese discovery, within one stable headword
identity. English senses remain separately evidenced references unless correspondence is established;
POS/order/text similarity do not prove equivalence. Preserve pronunciation record scope, exact region
labels and source notation. Correct deterministic parser/projection losses before paid enrichment.

Keep full TOEIC L&R tests only; learner Part selection does not create another catalog. Quality is a
revision-bound report, never an imported flag or manual checkbox. Reuse Tiptap and existing UI tokens.
The maintenance pilot alone may use DeepSeek with a durable USD 1/100-attempt ceiling and official
price confirmation; normal app AI policy is unchanged. See [editorial architecture](architecture/library-editorial.md)
and [data pipeline operations](DATA_PIPELINES.md).

## D-035 — Consolidate the foundation schema before team onboarding (2026-11-22)

**Decision.** Replace the unreleased development history with two Flyway migrations: the effective
PostgreSQL schema and its required reference data. Fresh databases start from those two files.
Use an empty application database and the pinned content imports after the cutover. Existing
development databases and their history can be kept as backups. Append future migrations from `V003`.

**Reason.** Team onboarding starts from a known schema and a small bootstrap. Previous schema
iterations remain in Git. The fresh schema was compared with the final schema from the prior
migration chain. Real lesson samples use a separate idempotent development seed linked to shared audio.

## D-036 — Learner profile and three-step onboarding (2026-11-23)

Decision: collect a learning goal, self-assessed level and separate daily study time
during initial onboarding. Display name and optional skill focus belong to profile
editing. Core returns a rule-based starting suggestion. Admin can look up learners
after they use the Learner API, including profiles pending onboarding. Keycloak owns
roles and Identity owns email snapshots.

See [Identity and Learner requirements](requirements/identity-learner.md) for behavior
and the [HTTP contract](architecture/http-api-contract.md#learner-profile-onboarding-and-admin-directory)
for handoff and wire examples.

## D-037 — Learner profile appearance editing (2026-11-23)

Learners edit their own appearance. Admin can help edit the display name and avatar
of learners already in the directory. Refer to [profile requirements](requirements/identity-learner.md#fr-idn-006--learner-profile-and-admin-directory)
and the [HTTP contract](architecture/http-api-contract.md#learner-profile-onboarding-and-admin-directory).

## D-038 — Azure Speech for the Admin pronunciation pilot (2026-11-23)

Use Azure Speech F0 Continuous Recognition for Admin Shadowing trials. Keep the accepted sentence
revision and private trial data in Core for 24 hours. The pilot establishes evidence for any later
Learner scoring policy. See [Lesson requirements](requirements/lesson.md#fr-lsn-006a--admin-pronunciation-pilot)
and the [execution boundary](architecture/ai-execution.md).

## D-039 — Vocabulary owns curated study units (2026-11-24)

- **Decision.** Vocabulary owns the topic, unit, and learning entry identities. The release
  uses the pinned Word Atlas source, Vietnamese translations, enriched word relations and Lyreo
  TOEIC context notes. Admin edits the installed catalog and flags exceptions with
  revision checks, while the release files preserve the source snapshot.
- **Effect.** A vocabulary entry has its own teaching context. Dictionary search is an optional
  cross-check. The existing learner SRS contract stays in service until the saved-word model
  supports entries from multiple contexts.
- **Owner.** [Lexicon and Vocabulary requirements](requirements/lexicon-vocabulary.md#fr-voc-003--admin-quản-lý-bộ-từ-vựng).
