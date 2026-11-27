# Lyreo development workflow

Lyreo runs application source locally for debugging and hot reload. Docker development provides PostgreSQL and Keycloak. [Root setup](../README.md) owns first-clone and common Make commands; its [Local Guides](../README.md#local-guides) link platform-specific app and tool READMEs.

## First development loop

Run `make init-env`, `make doctor`, and the needed dependency/setup targets from the root README. Start `make dev-infra` before Core. Run `make dev` for Core, AI and Admin in one terminal (`APPS="..."` selects services; Ctrl+C stops them, not Docker), or run `make core`, `make ai`, `make admin`, or `make mobile` in separate terminals as needed. Mobile needs an installed Expo development build; see [Mobile](../apps/mobile/README.md). For integrated YouTube authoring, run `make setup-media`, enable acquisition in the Core local env and restart Core (see [Core](../apps/core-service/README.md#youtube-authoring-tools)). Fresh-clone setup seeds reference data. Run `make setup-data` separately for the content you need. Dataset operations live in [Data Import](../tools/data-import/README.md).

Mock AI and local filesystem storage are the normal development defaults. Live model runtimes and
R2 require owner-specific configuration; full Grammar/TOEIC import integration uses the shared
development R2 bucket as described in [Data Import](../tools/data-import/README.md). Do not
infer model quality or production behavior from mock responses.

Core follows saved Java/resource changes across modules with a compile-and-DevTools restart loop;
see [Core reload behavior](../apps/core-service/README.md#local-run-and-verification). Keep editor
save preferences as they are. `make deps-java` remains a setup/dependency-update step, not a step
repeated by the running watcher.

## Database workflow

Core startup applies Flyway migrations and validates Hibernate mappings. Append a new versioned migration for every schema change; do not edit established migrations or let Hibernate mutate schema. Test PostgreSQL-specific behavior with Testcontainers. `make db-shell` inspects the local database. `make db-reset` only previews a lesson reset.
Stop Core first, then use `make db-reset APPLY=1 CONFIRM=lyreo_dev` (replace with the actual printed
`*_dev` database name) to remove lessons, lesson progress/practice, linked speech attempts and only
those lessons' authoring/build jobs. Active lesson jobs and curriculum/vocabulary source links block
reset. The SQL uses explicit table locks and deletes in one transaction; named publication guards are
restored before commit. It preserves imported TOEIC/lexicon/grammar, active releases/import history,
SRS/unrelated speech history, accounts, AI settings, categories, channels and all cached media/voice samples.

`make db-recreate` previews **whole application DB deletion**, including imports and configuration;
it requires the same `APPLY=1 CONFIRM=<actual_db>` and no application connections to execute.
`make down-v` refuses volume deletion until `CONFIRM=lyreo-volumes`; that command also deletes Keycloak.
The foundation closeout starts with `V001` for schema and `V002` for reference data. Subsequent changes
append from `V003`. Databases from the pre-closeout 31-migration history need a fresh application
database and a new import. Keep a backup of application data you need before resetting. [D-035](DECISIONS.md#d-035--consolidate-the-foundation-schema-before-team-onboarding-2026-11-22) records the cutover.

Development defaults live in [`infra/postgres/seed/dev.sql`](../infra/postgres/seed/dev.sql),
published text lessons live in [`infra/postgres/seed/lessons.sql`](../infra/postgres/seed/lessons.sql), and
curated curriculum paths, badges, learners, and cover media live in [`infra/postgres/seed/demo.sql`](../infra/postgres/seed/demo.sql).
Run `make seed` to migrate and idempotently apply all three layers (`dev.sql` -> `lessons.sql` ->
`seed-demo.sh` + `demo.sql`) against the datasource configured in Core's `.env`. It adds 24 stable
categories with English names and Vietnamese translations, [development AI defaults](CONFIGURATION.md#development-ai-defaults),
published demo lessons, and curated learning paths without overwriting existing edits or routes.
No automatic reseeding runs at startup. Large banks stay under the frozen [import tools](../tools/data-import/README.md).

Set `LESSON_SEED_FILE` to use a separately exported SQL snapshot. Keep private raw corpora and
credentials out of Git.

`make setup` creates three login-capable demo learners in local Keycloak and links
them to the configured development database. The fixture starts with two completed
profiles and one pending profile. Rerunning keeps saved edits and existing demo
passwords. If Keycloak was recreated with the application database intact, setup
links the new demo identities to the existing fixture profiles when possible.
See [local demo accounts](ACCOUNT.md#learner-demo-accounts) for usernames.

Large content datasets are imported through the [data-import tool](../tools/data-import/README.md), not Flyway seed blobs. Store raw datasets outside Git.

## Verification loop

Choose checks by the affected owner and risk. Use fast Java tests for local logic and Java integration
verification for persistence, security, jobs or contract changes. Run the owning app's Python/frontend
checks rather than every workspace by default; shared changes require checking their consumers.
`make test-tooling` covers repository validator changes. Commands live in Make and the component
READMEs; [testing strategy](TESTING.md) owns test types, naming and evidence policy.

Run `make validate` once at the end for offline repository/documentation guards. `make check`
aggregates full verification and already includes validation. Keep logs outside Git; rerun a passed
check only after relevant edits or failures. Do not add tests for copy or styling alone; changed
non-trivial logic needs an observable regression check.

If Docker, external models, datasets, or a device is unavailable, report the exact check that could not run. Do not treat an unrun check as a pass.

## Repository skill maintenance

Registry-managed skills live in `.agents/skills`; `skills-lock.json` records their origins and
content hashes. `.claude/skills` points to the same directory. Update the project scope explicitly:

```bash
DO_NOT_TRACK=1 pnpm dlx skills@latest update --project --yes
```

Review the source/lock diff and any changed runtime helpers. New upstream advice can target APIs
newer than the installed app dependencies; manifests and project contracts still govern changes.
`pr-review` is a customized fork with its own `UPSTREAM.md`: reconcile compatible fixes without
overwriting its worktree or posting safeguards. Do not replace it through a generic registry install.

## CodeGraph pilot

CodeGraph 1.6.2 is an optional, project-scoped navigation tool, not a runtime dependency or a
verification gate. [Project Codex config](../.codex/config.toml) runs its MCP server through pnpm,
exposes only `codegraph_explore`, and sets `DO_NOT_TRACK=1` for that process. Reload Codex to load
project MCP configuration; this repo is trusted in the current desktop profile. Other machines
must trust the project before Codex loads project configuration.

Setup and CLI diagnostics from the repo root:

```bash
DO_NOT_TRACK=1 pnpm dlx @colbymchenry/codegraph@1.6.2 init
DO_NOT_TRACK=1 pnpm dlx @colbymchenry/codegraph@1.6.2 status
```

Use graph queries for an unknown call path, symbol consumers or impact across files. Name concrete
symbols/files, pass this workspace's root as `projectPath`, and start with `maxFiles=3`; request more
only when the answer needs it. Keep `rg` for literal strings, configuration, migrations and small
edits. Graph output helps discovery; code, accepted contracts and fresh tests remain authoritative.
If a query is missing, stale or misleading, fall back to targeted search/read rather than repeatedly
querying the graph. Index creation is opt-in setup, not a step to repeat for every task.

The index stays in ignored `.codegraph/`. [Index configuration](../codegraph.json) excludes agent
skills and datasets; CodeGraph also skips ignored/dependency/build/cache paths. MCP maintains its
index while running. Diagnostics outside MCP should sync after source changes:

```bash
DO_NOT_TRACK=1 pnpm dlx @colbymchenry/codegraph@1.6.2 sync
```

This pilot does not claim token or cost savings. Compare retrieval correctness, output size, calls
and latency against targeted `rg` on representative Java, TypeScript and Python tasks. Real model
token/cost savings require actual usage measurements. See [upstream](https://github.com/colbymchenry/codegraph)
for capabilities and [telemetry policy](https://github.com/colbymchenry/codegraph/blob/main/TELEMETRY.md).

### Local pilot observation — 2026-10-31

The first index contained 550 files, 8,084 nodes and 15,919 edges; indexing reported 674ms
(about 3.1s including CLI startup). Agent skills, datasets and retired workstation files were absent.
A direct stdio MCP check listed only `codegraph_explore`; initialize instructions were 6,786 bytes.
A temporary Python symbol appeared after creation and reflected an edit while MCP was running;
the probe was removed and the index synced afterward.

Each row below is one MCP request (`maxFiles=3`) and one targeted `rg` process, measured once on
this machine with the existing index. Full outputs are local diagnostic logs, not committed artifacts.

| Query | Graph output bytes | Graph ms | rg output bytes | rg ms |
| --- | ---: | ---: | ---: | ---: |
| Java authoring | 22,484 | 599.88 | 7,352 | 4.53 |
| TypeScript theme consumers | 15,979 | 168.97 | 1,745 | 6.54 |
| Python LLM execution | 24,984 | 45.08 | 780 | 4.49 |

The graph returned relevant Java handlers/services, theme providers/header and Python endpoint/
provider source. It also included the Mobile theme provider in an Admin query, and an unrelated
Java provider controller in a Python query. Common names can broaden results despite a file hint;
qualify queries and verify the relevant boundaries before editing.

These are retrieval diagnostics, not an end-to-end agent benchmark: `rg` returned matching lines,
while the graph included source bodies and dependency information. Graph output was larger and
queries slower in these samples. No model token/cost usage was measured, so this is not evidence
of savings. Keep the optional pilot for cross-file discovery and use targeted search for known files.

## Troubleshooting routes

Use [Docker](../infra/docker/README.md) for local services and compose, [Keycloak](../infra/keycloak/README.md) for realm/bootstrap, [Core](../apps/core-service/README.md) for database startup, [AI Service](../apps/ai-service/README.md) for model runtime, and [Mobile](../apps/mobile/README.md) for emulator/device issues. The [jobs protocol](architecture/background-jobs.md) explains claim, recovery, and cancellation behavior.
