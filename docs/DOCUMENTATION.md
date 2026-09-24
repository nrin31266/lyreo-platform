# Lyreo documentation governance

Purpose: define documentation ownership, drift resolution, and change conventions for the Lyreo
project. This is the canonical source for documentation rules. Start task routing from
[docs/README.md](README.md); engineering invariants live in [AGENTS.md](../AGENTS.md).

---

## 1. One fact, one canonical owner

Every normative fact has exactly one canonical owner file. Other documents may orient readers and
link to the owner but must not duplicate whole rules, acceptance criteria, endpoint/schema
definitions, configuration tables, or roadmap content.

If a fact appears in two places and they disagree, the canonical owner wins. Fix or delete the
secondary copy; do not leave competing sources.

## 2. Truth-by-question table

| Question | Canonical source |
|---|---|
| Product scope, priorities, non-goals | `docs/product/prd.md` |
| Feature behavior, acceptance criteria | `docs/requirements/*` |
| Unresolved decisions, conflicts, gaps | `docs/requirements/open-questions.md` |
| Architecture boundaries, module structure | `docs/ARCHITECTURE.md` |
| Important technical decisions | `docs/DECISIONS.md` |
| Cross-cutting protocols (jobs, AI, HTTP, storage) | `docs/architecture/*` |
| Current code behavior | Code (read it) |
| Current HTTP endpoints | Code / OpenAPI spec |
| Current database schema | Flyway migrations / database tests |
| Dependencies and versions | Manifests / lockfiles / version files |
| Environment variables | `.env.example` in the owning executable |
| Available commands | `Makefile` / `package.json` / tool READMEs |
| CI enforcement rules | CI configuration files |
| Test results | Fresh CI/test execution (never cached claims) |
| How to run locally | `README.md` nearest the owning component |
| Technology rationale | `docs/DECISIONS.md` |
| Configuration semantics and precedence | `docs/CONFIGURATION.md` |
| Data import and pipeline rules | `docs/DATA_PIPELINES.md` |
| Verification evidence | Fresh CI/test execution |

When a question spans two owners, link both; do not merge them into a single super-document.

## 3. Drift resolution rules

### D1 — Implementation drift

Code does not match an accepted requirement. **Fix the code**, not the requirement. The requirement
defines intent; code that deviates is a bug until an explicit decision changes the requirement.

### D2 — Documentation duplication

The same fact appears in multiple documents. The canonical owner (per the table above) wins.
Merge, correct, or delete the secondary copy. A secondary doc may keep a one-sentence summary
with a link.

### D3 — Intentional contract change

A deliberate change to product behavior, API, schema, or event contract. Update the requirement,
code, and tests **together in the same change set**. Do not land code that silently contradicts
the documented contract.

### D4 — Architectural change

A change to module boundaries, dependency direction, communication protocol, or technology choice.
**Supersede the existing decision first** (append a new `D-NNN` entry in `docs/DECISIONS.md`),
then update the implementation to match.

### D5 — Unresolved conflict

Two sources disagree and no authority resolves the conflict. **Do not guess.** Record the conflict
in `docs/requirements/open-questions.md` with both sources, impact, and next steps. Complete any
independent work that does not require choosing, and ask only when the current action is blocked.

## 4. Scaffold-phase rule

Lyreo is currently in foundation/scaffold phase. Scaffold code exists to prove structure and
enable development; it does not define product truth. If scaffold behavior differs from a
documented requirement, the requirement governs future implementation. Do not cite scaffold code
as evidence that a requirement has been met.

## 5. When documentation must change

Update the canonical owner when any of these change:

- **Product behavior** — requirements, acceptance criteria, user-facing workflows.
- **Architecture** — module boundaries, dependency rules, communication protocols.
- **Commands and workflows** — developer-facing build/run/test instructions.
- **Dependencies** — new technology, removed library, version policy change.
- **Configuration** — new environment variable, changed precedence, removed key.
- **Public API or event contracts** — endpoints, schemas, published events.

## 6. When documentation should NOT change

Do not update docs for:

- Internal refactors that preserve all external behavior, contracts, and boundaries.
- Code formatting, style, or package-internal reorganization.
- Test-only changes that do not alter documented coverage expectations.
- Dependency patch upgrades within an existing version policy.

State _why_ the change is behavior-preserving if it touches a boundary-adjacent area.

## 7. Stable ID conventions

IDs are immutable. Never renumber, reuse, or reassign an existing ID to a new meaning. Retired
IDs are marked `superseded` with a reference to their replacement.

| Prefix | Meaning | Example |
|---|---|---|
| `FR-<AREA>-NNN` | Functional requirement | `FR-LSN-001` |
| `BR-<AREA>-NNN` | Business rule | `BR-LSN-001` |
| `NFR-<AREA>-NNN` | Non-functional requirement | `NFR-SEC-001` |
| `US-<AREA>-NNN` | User story | `US-LSN-001` |
| `AC-<AREA>-NNN` | Acceptance criterion | `AC-LSN-001` |
| `FEAT-<SLUG>` | Feature contract | `FEAT-LESSON-BUILD` |
| `OQ-NNN` | Open question | `OQ-001` |
| `D-NNN` | Decision log entry | `D-001` |

**Area codes**: `IDN`, `LSN`, `AI`, `LEX`, `VOC`, `GRM`, `TOE`, `CUR`, `GAM`, `ANL`, `NTF`,
`CHT`, `SEC`, `OPS`, `DAT`, `UI`.

Definition headings start directly with the ID:

```markdown
### FR-LSN-001 — Lesson source selection
#### AC-LSN-001 — Build acceptance
## D-042 — Switch to PostgreSQL-backed jobs
```

## 8. Canonical owner rule

Each normative fact has one owner. Other documents orient and link.

Acceptable in a non-owner document:
- A one-sentence summary linking to the owner.
- A contextual reference ("see [FR-LSN-001](requirements/lesson.md#fr-lsn-001--lesson-source)").

Not acceptable:
- Copying an entire rule, AC, endpoint/schema definition, or config table.
- Maintaining a parallel version that must be kept in sync.

## 9. Open-question workflow

When a product or architecture decision cannot be resolved immediately, record it:

```markdown
### OQ-NNN — <short title>

- **Type**: `question` | `conflict` | `decision-needed`
- **Description**: What is unresolved.
- **Sources**: Links to the conflicting documents or code.
- **Impact**: What is blocked or at risk.
- **Next steps**: Who or what can resolve this.
- **Status**: `open` | `resolved` (with resolution reference)
```

Do not call something a "confirmed bug" without supporting evidence. Close entries by marking
them resolved with a link to the resolution.

## 10. PR documentation-impact checklist

Before merging, verify:

- [ ] Changes to product behavior have updated the canonical requirement owner.
- [ ] New or changed public APIs are reflected in code and contract docs.
- [ ] Schema migrations are appended (not edited); Flyway naming is correct.
- [ ] New environment variables are added to the owning `.env.example`.
- [ ] New decisions are appended as `D-NNN` in `docs/DECISIONS.md`.
- [ ] No ID has been renumbered, reused, or removed without `superseded` marking.
- [ ] Links and anchors resolve correctly (run `make validate-docs` if available).
- [ ] Scaffold code is not cited as requirement evidence.

## 11. Link and anchor conventions

- Use **relative Markdown links** from the linking file. Absolute paths break forks and moves.
- Linux filesystems are **case-sensitive**; match the exact filename case.
- Prefer **stable heading anchors** or explicit `<a id="...">` anchors for cross-document links.
- Use real file paths, not placeholder `...` paths. Mark planned-but-unbuilt paths with `planned`.
- External links support methodology references; they do not validate Lyreo features.
- Do not reference machine-local scratch directories, home paths, or out-of-repo plans.

---

Routing lives in [docs/README.md](README.md); engineering invariants live in [AGENTS.md](../AGENTS.md).
