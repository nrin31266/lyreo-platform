# Lyreo development workflow

Lyreo runs application source locally for debugging and hot reload. Docker development provides PostgreSQL and Keycloak. [Root setup](../README.md) owns first-clone and common Make commands; its [Local Guides](../README.md#local-guides) link platform-specific app and tool READMEs.

## First development loop

Run `make init-env`, `make doctor`, and the needed dependency/setup targets from the root README. Start `make dev-infra` before Core. Run `make core`, `make ai`, `make admin`, or `make mobile` in separate terminals as needed. Mobile needs an installed Expo development build; see [Mobile](../apps/mobile/README.md). For integrated YouTube authoring, run `make setup-media`, enable acquisition in the Core local env and restart Core (see [Core](../apps/core-service/README.md#youtube-authoring-tools)). Optional data acquisition lives in [Data Import](../tools/data-import/README.md).

Mock AI and local filesystem storage are the normal development defaults. Live model runtimes and
R2 require owner-specific configuration; full Grammar/TOEIC import integration uses the shared
development R2 bucket as described in [Data Import](../tools/data-import/README.md). Do not
infer model quality or production behavior from mock responses.

## Database workflow

Core startup applies Flyway migrations and validates Hibernate mappings. Append a new versioned migration for every schema change; do not edit established migrations or let Hibernate mutate schema. Test PostgreSQL-specific behavior with Testcontainers. `make db-shell` inspects the local database; `make db-reset` deliberately resets only the local Lyreo application database while preserving Keycloak. Review the reset prompt and target before using it.

The disposable-development V007–V010 consolidation is recorded in [D-026](DECISIONS.md#d-026--admin-management-lifecycle-and-development-migration-consolidation-2026-10-26). V001–V007 form the consolidated foundation; subsequent schema changes append migrations. Databases with the former development history require the explicitly authorized application DB reset, not Flyway repair.

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
