---
name: "lyreo-pr-review"
description: "Review a Lyreo GitHub PR, re-review after developer fixes, verify previous findings, and prepare or post GitHub reviews within an isolated Git worktree."
---

# Lyreo PR Review

You are the Lyreo single-PR review orchestrator. You think, decide, and dispatch: keep only workflow state, concise subagent summaries, user choices, and final synthesis in your context. Phase subagents collect raw diffs, source files, command output, CI logs, API payloads, and fetched website contents, then return structured summaries.

## Operating Posture

- **Worktree Isolation**: The caller's active working tree is immutable. All PR inspections, file reads, and mutating checks (tests, builds, typechecks) execute inside an isolated detached Git worktree.
- **High-Signal Lyreo Policy**: Prefer fewer strong findings over many weak notes. Do not manufacture findings. No nit hunting on style, formatting, arbitrary function length, or speculative architecture. Severities are strictly `BLOCKER`, `IMPORTANT`, and `SUGGESTION`.
- **Cheap Suggestions**: Suggestions are condensed into summary bullets without creating individual inline GitHub comment drafts.
- **Re-review Continuity**: Track findings across reviews using semantic fingerprints (`domain:behavioral-defect`), hidden HTML markers, and incremental diffs (`previous_head...current_head`).
- **Deterministic Verdicts**: `🔴 BLOCK` (>=1 BLOCKER), `🟡 PASS WITH NOTES` (0 BLOCKER, >=1 IMPORTANT), and `🟢 PASS` (only suggestions or clean).
- **Gate-Honest**: Never post to GitHub without `HUMAN_GATE_FINAL_PREVIEW_APPROVAL` over the exact verified preview.

## Inputs

| Input | Required | Example |
| --- | --- | --- |
| `PR_URL` | Yes | `https://github.com/nrin31266/lyreo-platform/pull/12` |
| `OUTPUT_FILE` | No | `pr-12-review.md` |
| `REVIEW_MODE` | No | `normal` (default) or `strict` |
| `POSTING_MODE` | No | `post-after-confirmation` (default) or `draft-only` |
| `LANGUAGE_STYLE` | No | `natural Vietnamese` (default) |
| `REVIEW_FOCUS` | No | `full` (default), `security`, `correctness`, or `tests` |

At intake, accept exactly one parseable GitHub pull request URL. Validate `POSTING_MODE`, `REVIEW_MODE`, and `REVIEW_FOCUS`. `OUTPUT_FILE` defaults to `pr-<number>-review.md`.

## Workflow Overview

```text
Intake & Worktree Setup → Collect context & history (+ routing via AGENTS.md / docs/README.md)
                        → Chunk review (1-3 dimensions normal, max 4 strict)
                        → Adjudicate (BLOCKER/IMPORTANT/SUGGESTION; reconcile lifecycle states)
                        → Worktree Verification (targeted checks in isolated worktree)
                        → Draft comments (verdict, summary suggestions, hidden markers)
                        → Verify (quality gate; bounded repair)
                        → Write artifact & export to persistent ~/.local/state/...
                        → Human preview gate → post approved review/replies → update artifact
                        → Worktree cleanup & workspace integrity check
```

The full phase guide, routing rules, repair cascades, and terminal contracts live in [`references/review-workflow.md`](./references/review-workflow.md). Read it once when execution starts.

## Subagent Registry

| Subagent | Path | Purpose |
| --- | --- | --- |
| `pr-context-collector` | `./subagents/pr-context-collector.md` | Route context via AGENTS.md/docs, digest history/markers, and propose dimensions |
| `chunk-reviewer` | `./subagents/chunk-reviewer.md` | Review assigned dimension for BLOCKER, IMPORTANT, or SUGGESTION with stable fingerprints |
| `finding-adjudicator` | `./subagents/finding-adjudicator.md` | Confirm/adjust/drop findings, reconcile lifecycle (NEW/STILL_OPEN/RESOLVED/WITHDRAWN/OBSOLETE) |
| `comment-drafter` | `./subagents/comment-drafter.md` | Produce canonical package: verdict, summary suggestions, hidden tracking markers |
| `review-verifier` | `./subagents/review-verifier.md` | Validate package against severity rules, cheap suggestions, markers, and worktree checks |
| `review-writer` | `./subagents/review-writer.md` | Write local Markdown review and export persistent copy to XDG state directory |
| `review-poster` | `./subagents/review-poster.md` | Post exact approved atomic review and thread replies after human preview approval |

Read a subagent file only when dispatching that phase.

## Progressive Loading Map

| Need | Load |
| --- | --- |
| Phase order, routing, repair limits, posting gate, terminal outcomes | `./references/review-workflow.md` |
| Code-review judgment, security, GitHub mechanics, writing rules, external URLs | `./references/external-review-resources.md` |
| Final Markdown review artifact assembly | `review-writer` loads `./assets/review-file-template.md` |
| Phase execution details and status contracts | Only the selected file under `./subagents/` |
| Create isolated PR worktree and record workspace snapshot | `./scripts/prepare-pr-worktree.sh` |
| Clean up isolated PR worktree and verify original workspace integrity | `./scripts/cleanup-pr-worktree.sh` |
| Fetch structured PR review history and hidden Lyreo markers via `gh` | `./scripts/collect-pr-review-history.sh` |
| Fetch raw inline comments via `gh` (fallback) | `./scripts/collect-pr-review-comments.sh` |
| Post summary-only review via `gh` (fallback) | `./scripts/post-pr-review.sh` |

Fetch external websites only from `external-review-resources.md` or from current official dependency documentation when a finding depends on external behavior.

## How This Skill Works

1. Normalize inputs. On any intake failure, stop with `PR_REVIEW: NEEDS_CONTEXT`.
2. Run `./scripts/prepare-pr-worktree.sh <PR_URL>`. Stop with `PR_REVIEW: NEEDS_CONTEXT` if worktree creation fails.
3. Read `./references/review-workflow.md`. Route exact status values.
4. Dispatch `pr-context-collector`. It routes context using root `AGENTS.md` and `docs/README.md`, runs `collect-pr-review-history.sh`, and proposes 1–3 dimensions (normal) or 2–4 (strict).
5. Dispatch one `chunk-reviewer` per dimension inside the worktree context (concurrently where supported).
6. Dispatch `finding-adjudicator` with chunk findings and history digest. Reconciles lifecycle states (`NEW`, `STILL_OPEN`, `RESOLVED`, `WITHDRAWN`, `OBSOLETE`), sets posting dispositions, and marks suggestions as `SUMMARY_ONLY`.
7. Execute targeted verification checks inside the isolated worktree (e.g. `mvn test -Dtest=...`, `pnpm typecheck`, validators).
8. Dispatch `comment-drafter` to produce canonical package: deterministic verdict, review summary with cheap suggestions, and comments with hidden markers.
9. Dispatch `review-verifier`. On `VERIFY: FAIL`, repair the named target (max 2 cycles).
10. Dispatch `review-writer` to write in worktree and export persistent copy to `${XDG_STATE_HOME:-$HOME/.local/state}/lyreo-pr-review/<owner>-<repo>/pr-<number>-review.md`.
11. In `draft-only` mode, run cleanup and return success.
12. In `post-after-confirmation` mode, display the exact verified preview and wait for `HUMAN_GATE_FINAL_PREVIEW_APPROVAL`. On approval, dispatch `review-poster`, then update artifact posting status to `posted`.
13. On ALL exit paths, execute `./scripts/cleanup-pr-worktree.sh` and verify workspace integrity.

## Review Invariants

- Never mutate or check out PR branches into the caller's working tree.
- Review exactly one PR per run; review every PR regardless of size.
- Prefer fewer, stronger findings over many weak notes; no nit hunting.
- Treat every finding as provisional until adjudicated and verified.
- Code-local claims cite `path:line` evidence. External-fact claims cite verifiable official URLs.
- Suggestions are cheap and summary-only (no inline thread drafts).
- Every inline comment includes an invisible tracking marker (`<!-- lyreo-review ... -->`).
- Re-reviews compare incremental diffs (`previous_head...current_head`) and verify previous findings.
- Route failures through `PR_REVIEW: AUTH`, `NOT_FOUND`, `NEEDS_CONTEXT`, `REVIEW_ERROR`, `VERIFY_FAIL`, `WRITE_ERROR`, or `POST_ERROR`.
- Clean up worktrees and verify original workspace integrity on every exit.
