# Review Workflow

> Read this file once, after input normalization, before dispatching any subagent. This is the single authoritative description of phase order, routing, repair, and terminal behavior. Keep only status summaries in the orchestrator context; raw diffs, command output, API payloads, and fetched web pages stay inside the subagent that produced them.

## Phase Sequence

| Phase | Owner | Continue on |
| --- | --- | --- |
| Intake | Inline (orchestrator) | Inputs normalized; one PR chosen |
| Worktree Setup | Inline (`prepare-pr-worktree.sh`) | `WORKTREE: PASS` (isolated worktree created & snapshot recorded) |
| Context | `pr-context-collector` | `CONTEXT: PASS` (with history digest & dimension proposal) |
| Chunk review | One `chunk-reviewer` per dimension | Every chunk returns `CHUNK: PASS` or `CHUNK: NO_FINDINGS` |
| Adjudication | `finding-adjudicator` | `ADJUDICATE: PASS` or `ADJUDICATE: NO_FINDINGS` |
| Worktree Verification | Inline (in `WORKTREE_PATH`) | Targeted checks executed or marked `NOT RUN` |
| Comments | `comment-drafter` | `COMMENTS: PASS`, or skipped on the no-findings path |
| Verify | `review-verifier` | `VERIFY: PASS` |
| Write | `review-writer` | `WRITE: PASS` (saved in worktree & exported to persistent storage) |
| Post | `review-poster` | `POST: PASS`, or skipped in `draft-only` |
| Artifact update | `review-writer` (update mode) | `WRITE: PASS` after posting or cancellation |
| Worktree Cleanup | Inline (`cleanup-pr-worktree.sh`) | Worktree pruned & workspace integrity verified |

## State Envelope

Carry this compact state between phases:

```text
Inputs: PR_URL, OUTPUT_FILE, POSTING_MODE, REVIEW_MODE, LANGUAGE_STYLE, REVIEW_FOCUS
Worktree: WORKTREE_PATH, SNAPSHOT_FILE, PR_HEAD_SHA
Dimensions: <1–3 dimension names (normal) or 2–4 (strict) from CONTEXT: PASS>
Existing-comment digest: <summary reference, held by adjudicator inputs>
Latest status: <CONTEXT | CHUNK | ADJUDICATE | COMMENTS | VERIFY | WRITE | POST block>
Review verdict candidate: none | 🟢 PASS | 🟡 PASS WITH NOTES   (no-findings path only)
Review verdict (post-verify): 🔴 BLOCK | 🟡 PASS WITH NOTES | 🟢 PASS
Review decision (post-verify): comment | request changes | approve
Posting status: draft | posted | cancelled | failed
Exported artifact path: <persistent path in ~/.local/state/...>
Repair cycles: <0–2>
Narrow-context retries used: <0–1>
Status-parse retries used per dispatch: <0–1>
```

`🔴 BLOCK` / `request changes` is never a review verdict/decision candidate. It appears only after verified BLOCKER findings exist, via `comment-drafter` and `review-verifier`.

## Fail-Closed Status Handling

Every subagent must return its documented status block. When a reply is missing its status line or the status cannot be parsed, re-dispatch that subagent once with the same inputs and a note that the status block was malformed. If the second reply is also unparseable, treat it as that phase's `ERROR` status and route accordingly. Never guess a status.

## Execution Rules

### Intake & Worktree Setup

1. Require exactly one parseable GitHub PR URL, valid `POSTING_MODE` (`draft-only` or `post-after-confirmation` [default]), `REVIEW_MODE` (`normal` [default] or `strict`), `REVIEW_FOCUS` values, and a safe workspace-relative Markdown `OUTPUT_FILE` (relative, `.md`, no `..`, not under `.git/`, resolves inside the workspace). If multiple PR URLs are present, run `HUMAN_GATE_CHOOSE_ONE_PR`; if a valid single PR is not chosen, stop with `PR_REVIEW: NEEDS_CONTEXT`.
2. Run `../scripts/prepare-pr-worktree.sh <PR_URL>` to create an isolated detached worktree (`WORKTREE_PATH`) and capture an immutable snapshot of the original workspace (`ORIGINAL_ROOT`, `ORIGINAL_BRANCH`, `ORIGINAL_HEAD`, `git status --porcelain`). If worktree creation fails, stop with `PR_REVIEW: NEEDS_CONTEXT`. All subsequent file reads, diff inspections, and verification checks run inside `WORKTREE_PATH`; never modify or check out PR files in the caller's working tree.

### Context

3. Dispatch `pr-context-collector` with `PR_URL`, `OUTPUT_FILE`, `REVIEW_MODE`, and `REVIEW_FOCUS`. It reads `../references/project-profile.md` for project-routing hints and language default, executes `collect-pr-review-history.sh` (parses `<!-- agent-pr-review ... -->` and legacy `<!-- lyreo-review ... -->` markers), and proposes 1–3 dimensions (normal) or 2–4 (strict). Detects incremental re-review when a previous reviewed HEAD is found in markers.
4. Route context statuses exactly: `CONTEXT: AUTH` → `PR_REVIEW: AUTH`; `CONTEXT: NOT_FOUND` → `PR_REVIEW: NOT_FOUND`; `CONTEXT: ERROR` → `PR_REVIEW: REVIEW_ERROR`.
5. `CONTEXT: NEEDS_CONTEXT` is a narrow request the orchestrator may be able to satisfy. Satisfy it inline and re-dispatch the collector at most once per run; if the need persists or cannot be satisfied, stop with `PR_REVIEW: NEEDS_CONTEXT`.

### Chunk Review

6. Dispatch one `chunk-reviewer` per dimension, each with the context summary, its assigned dimension, `DIMENSION_FILES`, `REVIEW_MODE`, `REVIEW_FOCUS`, and `LANGUAGE_STYLE`. Dispatch concurrently when the runtime supports it; otherwise serially in dimension order. Chunk reviewers enforce 3-tier severity (`BLOCKER`, `IMPORTANT`, `SUGGESTION`) and assign stable semantic fingerprints (`domain:behavioral-defect`). Never let a chunk reviewer dispatch other subagents.
7. Route `CHUNK: ERROR` for any dimension to `PR_REVIEW: REVIEW_ERROR`. On `CHUNK: NEEDS_CONTEXT`, dispatch `pr-context-collector` once with the narrow request (this consumes the single narrow-context retry), then re-dispatch only that chunk reviewer. A second `CHUNK: NEEDS_CONTEXT` from any reviewer stops with `PR_REVIEW: NEEDS_CONTEXT`.
8. Proceed when every chunk returns `CHUNK: PASS` or `CHUNK: NO_FINDINGS`.

### Adjudication

9. Dispatch `finding-adjudicator` with all chunk findings and the existing history digest. It confirms, severity-adjusts, or drops each candidate with a written reason; merges cross-dimension duplicates by fingerprint; reconciles lifecycle states (`NEW`, `STILL_OPEN`, `RESOLVED`, `WITHDRAWN`, `OBSOLETE`); and sets posting dispositions. All findings are consolidated entirely into the single review body — there are zero inline comments and zero thread replies:
   - `NEW` BLOCKER/IMPORTANT → `BODY_SECTION` (described in dedicated sections of `CANONICAL_BODY`)
   - `STILL_OPEN`/`RESOLVED`/`WITHDRAWN`/`OBSOLETE` → `BODY_SECTION` (rendered in `## Previous Findings` section of `CANONICAL_BODY`)
   - All `SUGGESTION` → `SUMMARY_ONLY` (cheap suggestions policy; bullet list in `## Suggestions`, no dedicated section)
10. Route `ADJUDICATE: ERROR` to `PR_REVIEW: REVIEW_ERROR`. On `ADJUDICATE: NO_FINDINGS` (nothing survived, no previous findings owed), skip `comment-drafter`, set the review verdict candidate — `🟢 PASS` if residual risks are non-blocking, otherwise `🟡 PASS WITH NOTES` — and go to Worktree Verification.

### Worktree Verification

11. In `WORKTREE_PATH`, execute targeted verification commands. Discover commands from CI config, build metadata, and documented repo instructions — do not hardcode project-specific commands. Distinguish check types: `TEST`, `BUILD`, `TYPECHECK`, `LINT`, `GUARDRAIL`, `SYNTAX`, `REPRODUCTION`. Record each as `PASS`, `FAIL`, or `NOT RUN — <reason>`. Bug reproduction is `REPRODUCED`, never `PASS`. Never run mutating commands in the caller's original workspace.

### Comments

12. Dispatch `comment-drafter` with adjudicated findings, context summary, `HEAD_SHA`, and `LANGUAGE_STYLE`. It loads `../assets/review-file-template.md` and produces ONE `CANONICAL_BODY` Markdown string — the exact text that will be posted to GitHub — containing: verdict header, all findings in their appropriate template sections (with hidden per-finding markers), suggestion bullets, and the global tracking marker `<!-- agent-pr-review reviewed-head: ... findings: ... -->` at the end. Zero inline comment threads and zero thread replies are produced.
    - Global finding budget guidance (enforced by `finding-adjudicator`):
      - `normal`: all verified BLOCKER, ~4 strongest IMPORTANT total, max ~2 useful SUGGESTION
      - `strict`: all verified BLOCKER, ~6 IMPORTANT total, max ~3 SUGGESTION
      - Never hide a serious independent defect solely because of budget
13. Route `COMMENTS: ERROR` to `PR_REVIEW: REVIEW_ERROR`. On `COMMENTS: NEEDS_METADATA`, collect only the requested context inline and retry drafting once; a repeated `NEEDS_METADATA` or `ERROR` stops with `PR_REVIEW: REVIEW_ERROR`.

### Verify

14. Dispatch `review-verifier` with the review package (or, on the no-findings path, the candidate verdict and residual risks). On `VERIFY: FAIL`, the verifier names exactly one `Fix target`; increment the repair-cycle counter on each `VERIFY: FAIL` and stop with `PR_REVIEW: VERIFY_FAIL` when a third failure would begin. Route repairs:
    - `orchestrator-decision`: reset the candidate from the verifier's issues, then re-run `review-verifier`.
    - `pr-context-collector`: repair the context packet, then re-run `finding-adjudicator` (with prior chunk findings), `comment-drafter` when findings exist, and `review-verifier`. Do not re-run chunk reviewers during repair.
    - `finding-adjudicator`: repair the named adjudication defect, then re-run `comment-drafter` when findings exist and `review-verifier`.
    - `comment-drafter`: repair the named comments, then re-run `review-verifier`.
15. Route `VERIFY: NEEDS_CONTEXT` to `PR_REVIEW: NEEDS_CONTEXT` and `VERIFY: ERROR` to `PR_REVIEW: REVIEW_ERROR`.

### Write

16. Dispatch `review-writer` only after `VERIFY: PASS`, with posting status `draft`. It writes `OUTPUT_FILE` in the worktree and exports a persistent copy to `${XDG_STATE_HOME:-$HOME/.local/state}/pr-review/<owner>-<repo>/pr-<number>-review.md`. Route `WRITE: ERROR` to `PR_REVIEW: WRITE_ERROR`.
17. In `draft-only` mode, run cleanup and finish with `PR_REVIEW: VERIFIED_DRAFT_SAVED`.

### Post

18. In `post-after-confirmation` mode, resolve the authenticated GitHub user vs PR author BEFORE building the preview. If the authenticated user is the PR author (self-review), set effective GitHub event to `COMMENT` regardless of internal verdict; record this in the preview. Build the preflight packet from the canonical review package and show the user the exact preview:
    ```
    Internal verdict: <🔴 BLOCK | 🟡 PASS WITH NOTES | 🟢 PASS>
    Effective GitHub event: <REQUEST_CHANGES | COMMENT | APPROVE>

    --- EXACT BODY START ---
    <exact Markdown review body that will be posted verbatim>
    --- EXACT BODY END ---
    ```
    Run `HUMAN_GATE_FINAL_PREVIEW_APPROVAL`. Never preview `REQUEST_CHANGES` when self-review would cause a 422.
19. If the user declines, re-dispatch `review-writer` in update mode to set posting status to `cancelled`, run cleanup, then finish with `PR_REVIEW: VERIFIED_DRAFT_SAVED_POSTING_CANCELLED`.
20. On approval, dispatch `review-poster`. It posts ONE atomic review event: the exact `CANONICAL_BODY` as the review body, with `comments[]` always empty (zero inline finding comments) and zero thread replies. For a summary-only review, `../scripts/post-pr-review.sh` can also be used. Reads back the posted review to verify.
    - `Inline finding comments posted` and `Thread replies posted` in the poster output must both always be 0. Any non-zero value is a contract violation — stop with `PR_REVIEW: POST_ERROR`.
21. Route `POST: PASS` to artifact update; route `POST: PREVIEW_REQUIRED`, `POST: AUTH`, `POST: METADATA_INVALID`, and `POST: ERROR` to `PR_REVIEW: POST_ERROR` with the poster's `Reason` and `Next step`, and update the artifact's posting status to `failed`.

### Artifact Update

22. After `POST: PASS`, re-dispatch `review-writer` in update mode to set the artifact's posting status to `posted`, then finish with `PR_REVIEW: VERIFIED_REVIEW_POSTED`. If the update itself fails, still report the posted success but include the stale-artifact warning in `Notes`.

### Re-review Lifecycle

A re-review is triggered when `pr-context-collector` detects a previous reviewed HEAD (from markers `<!-- agent-pr-review ... -->` or legacy `<!-- lyreo-review ... -->`).

In a re-review:
- Provide incremental diff (`previous_head...current_head`) to chunk reviewers.
- Each surviving previous finding carries a lifecycle state: `STILL_OPEN`, `RESOLVED`, `WITHDRAWN`, or `OBSOLETE`.
- `finding-adjudicator` verifies developer-claimed fixes against current code before marking `RESOLVED`; developer replies are evidence to verify, not ground truth.
- `comment-drafter` produces one `CANONICAL_BODY` using **Format 2 (Incremental Re-review)** from `review-file-template.md`: containing `## Previous Findings` (reconciled lifecycle), `## New Findings in Delta`, `## Suggestions`, and the global tracking marker.
- Post ONE review event: `CANONICAL_BODY` as review body, `comments[]` always empty, zero thread replies. Existing GitHub inline threads are strictly read-only history.

### Worktree Cleanup

23. On EVERY terminal outcome (success or failure), run `../scripts/cleanup-pr-worktree.sh <WORKTREE_PATH> <SNAPSHOT_FILE>`.
    - Prunes worktree and removes temporary directory.
    - Verifies original branch, HEAD, and git status match snapshot.
    - If integrity check fails, report `WORKSPACE_INTEGRITY_MISMATCH` (do NOT run hard reset); include in `Notes`.

## Terminal Outcomes

Success:

```text
PR_REVIEW: VERIFIED_DRAFT_SAVED
PR_REVIEW: VERIFIED_DRAFT_SAVED_POSTING_CANCELLED
PR_REVIEW: VERIFIED_REVIEW_POSTED
```

Failure envelope:

```text
PR_REVIEW: AUTH | NOT_FOUND | NEEDS_CONTEXT | REVIEW_ERROR | VERIFY_FAIL | WRITE_ERROR | POST_ERROR
Reason: <one line>
Next step: <one clear action>
```

## Final Output Contract

Final success replies include:

```text
Review verdict: <🔴 BLOCK | 🟡 PASS WITH NOTES | 🟢 PASS>
Review file: <EXPORTED_PERSISTENT_FILE_PATH>
Workspace integrity: verified unchanged (branch <branch>, HEAD <sha>)
Findings: <count or 0>
- BLOCKER: <count>
- IMPORTANT: <count>
- SUGGESTION: <count>
New inline comments: 0
Thread replies: 0
Review decision: <comment | request changes | approve>
Posting: <skipped | posted | cancelled>
Notes: <one-line residual risk or none>
```

## Dispatch Example

<example>
Re-review on a previously reviewed PR, `POSTING_MODE=post-after-confirmation`, `REVIEW_MODE=normal`:

1. Worktree created at `/tmp/pr-worktree-abc12345-1020`.
2. `pr-context-collector` → `CONTEXT: PASS`; dimensions `security`, `tests`; compact history digest shows 3 prior findings (1 resolved), previous reviewed HEAD `a1b2c3d`.
3. Two `chunk-reviewer` dispatches → 5 candidate findings.
4. `finding-adjudicator` → `ADJUDICATE: PASS`: 2 BLOCKER (STILL_OPEN BODY_SECTION), 1 IMPORTANT (NEW BODY_SECTION), 1 SUGGESTION (SUMMARY_ONLY), 1 RESOLVED (BODY_SECTION); 1 dropped.
5. Worktree verification: `NOT RUN — no discoverable test command`.
6. `comment-drafter` → `COMMENTS: PASS` with verdict `🔴 BLOCK`; `CANONICAL_BODY` rendered from Format 2 of `review-file-template.md` containing `## Previous Findings` (2 STILL_OPEN, 1 RESOLVED), `## New Findings in Delta` (1 IMPORTANT), `## Suggestions` (1 bullet), and global tracking marker; zero inline comments and zero thread replies.
7. `review-verifier` → `VERIFY: PASS`.
8. `review-writer` → `WRITE: PASS` (draft); `CANONICAL_BODY` saved verbatim; exported to `~/.local/state/pr-review/org-repo/pr-1020-review.md`.
9. Resolve auth user vs PR author → not self-review; effective event `REQUEST_CHANGES`.
10. Show exact preview with `--- EXACT BODY START ---` / `--- EXACT BODY END ---`; user approves.
11. `review-poster` → `POST: PASS` (1 atomic review, `comments[]` empty, 0 thread replies); read-back verified.
12. `review-writer` update mode → posting status `posted`; `PR_REVIEW: VERIFIED_REVIEW_POSTED`.
13. `cleanup-pr-worktree.sh` → `WORKTREE_CLEANUP: PASS (workspace integrity verified)`.
</example>
