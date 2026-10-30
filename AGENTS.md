# AGENTS.md — Lyreo engineering contract

Lyreo is in the foundation/scaffold phase. Starter code and tests prove development rails,
not product behavior or feature completeness. Replace scaffold behavior for accepted requirements.

## Read for the task

| Change | Read and verify |
| --- | --- |
| Small copy, CSS or isolated edit | Applicable instructions, affected files and diff; relevant checks without a documentation sweep |
| Feature logic, multiple files or unclear bug | [Docs map](docs/README.md), relevant owners and callers; brief plan and regression checks |
| Schema, auth, security, concurrency, API, CI or major dependency | Requirements, architecture, consumers and current official docs where version-sensitive; integration checks or full gate |

Use targeted `rg` searches and bounded reads. CodeGraph is optional for indexed cross-file
exploration; see the [development workflow](docs/DEVELOPMENT.md#codegraph-pilot).
Choose skills for the actual change, not just its file extension. A small styling edit does not
require a new design process or a performance audit. Read supporting references on demand.
Generic skill advice does not override project contracts or authorize stack changes.
Keep verbose logs outside the repo; report summaries and relevant errors. Reuse chat findings.

## Respect the owner

The docs map routes to scope, requirements, architecture and decisions. Code describes current
behavior; Flyway owns schema, manifests own versions, `.env.example` files own environment keys,
and fresh checks supply evidence. Fix code/tests that differ from accepted requirements.
Intentional contract changes update the owner and consumers together; link instead of duplicating.
Internal refactors need no ceremonial docs edit. Follow [documentation governance](docs/DOCUMENTATION.md);
record unresolved product/architecture choices in `docs/requirements/open-questions.md`, not backlog.

## Keep the foundation intact

- Core is a Spring Boot modular monolith with `api`, `application`, `domain` and `infrastructure`
  concerns; dependencies point inward. Cross-module access uses public/named APIs, shared contracts
  or Modulith events, never internal repositories/adapters. Technical `platform/*` cannot
  depend on business modules. [Architecture](docs/ARCHITECTURE.md) owns package guidance.
- Core owns business intent, prompts, orchestration, routing and durable transitions;
  FastAPI executes AI capabilities. PostgreSQL is authoritative workflow state. Use affected
  [AI](docs/architecture/ai-execution.md), [jobs](docs/architecture/background-jobs.md) and
  [HTTP](docs/architecture/http-api-contract.md) contracts.
- Append Flyway migrations; keep Hibernate in validation mode. Kafka/Redis require a new decision.
- Validate external input; enforce authorization, scores, XP and rewards server-side. Keep provider credentials
  encrypted and server-side, out of Git/logs/client code including `VITE_*` and `EXPO_PUBLIC_*`.
  Do not expose internal failures or learner-private artifacts.
- Admin and Mobile own separate semantic theme tokens/components; shared translations use i18n.

## Work and finish with evidence

Work in one agent unless the user requests delegation. Preserve unrelated work and history.
Commit/push only the authorized scope and verify the staged content when unrelated work remains.
Decide reversible implementation details and repository-consistent mechanics. Ask for ambiguous
product semantics, unauthorized destructive data operations, security/credential policy,
irreversible external actions or conflicting contracts; finish independent work first.

Run affected checks and `make validate` once before finishing. Use `make check` for broad/high-impact
verification; it includes validation. Repeat checks only after relevant changes or failures.
Report commands actually run, exact blockers and remaining risks; never claim an unrun check passed.
Exclude secrets, datasets, generated artifacts and unrelated files. Review the final diff/status
and [documentation impact](docs/DOCUMENTATION.md#10-pr-documentation-impact-checklist).
