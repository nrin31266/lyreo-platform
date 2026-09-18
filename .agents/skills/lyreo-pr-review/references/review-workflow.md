# Review Workflow

> Read this file once, after input normalization, before dispatching any subagent. This is the single authoritative description of phase order, routing, repair, and terminal behavior. Keep only status summaries in the orchestrator context; raw diffs, command output, API payloads, and fetched web pages stay inside the subagent that produced them.

## Phase Sequence

| Phase | Owner | Continue on |
| --- | --- | --- |
| Intake | Inline (orchestrator) | Inputs normalized; one PR chosen |
| Worktree Setup | Inline (`prepare-pr-worktree.sh`) | `WORKTREE: PASS` (isolated worktree created & snapshot recorded) |
| Context | `pr-context-collector` | `CONTEXT: PASS` (with routing & history digest) |
| Chunk review | One `chunk-reviewer` per dimension | Every chunk returns `CHUNK: PASS` or `CHUNK: NO_FINDINGS` |
| Adjudication | `finding-adjudicator` | `ADJUDICATE: PASS` or `ADJUDICATE: NO_FINDINGS` |
| Worktree Verification | Inline (in `WORKTREE_PATH`) | Targeted test/typecheck/build executed or marked `NOT RUN` |
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
Dimensions: <1-3 dimension names (normal) or 2-4 (strict) from CONTEXT: PASS>
Existing-comment digest: <summary reference, held by adjudicator inputs>
Latest status: <CONTEXT | CHUNK | ADJUDICATE | COMMENTS | VERIFY | WRITE | POST block>
Review verdict candidate: none | 🟢 PASS | 🟡 PASS WITH NOTES
Review verdict (post-verify): 🔴 BLOCK | 🟡 PASS WITH NOTES | 🟢 PASS
Review decision (post-verify): comment | request changes | approve
Posting status: draft | posted | cancelled | failed
Exported artifact path: <persistent path in ~/.local/state/...>
Repair cycles: <0-2>
Narrow-context retries used: <0-1>
Status-parse retries used per dispatch: <0-1>
```

## Fail-Closed Status Handling

Every subagent must return its documented status block. When a reply is missing its status line or the status cannot be parsed, re-dispatch that subagent once with the same inputs and a note that the status block was malformed. If the second reply is also unparseable, treat it as that phase's `ERROR` status and route accordingly. Never guess a status.

## Execution Rules

### Intake & Worktree Setup

1. Require exactly one parseable GitHub PR URL, valid `POSTING_MODE` (`draft-only` or `post-after-confirmation` [default]), `REVIEW_MODE` (`normal` [default] or `strict`), and safe Markdown `OUTPUT_FILE`.
2. Run `../scripts/prepare-pr-worktree.sh <PR_URL>` to create an isolated detached worktree (`WORKTREE_PATH`) and capture an immutable snapshot of original workspace (`branch`, `HEAD`, `git status --porcelain`).
   - If worktree creation fails: stop with `PR_REVIEW: NEEDS_CONTEXT`.
   - Never modify or check out PR files in the caller's working tree. All subsequent file reads and verification checks run inside `WORKTREE_PATH`.

### Context

3. Dispatch `pr-context-collector` with `PR_URL`, `OUTPUT_FILE`, `REVIEW_MODE`, and `REVIEW_FOCUS`.
   - Reads root `AGENTS.md` and `docs/README.md` to route to relevant owner documents.
   - Executes `../scripts/collect-pr-review-history.sh` for reviews, threads, and hidden markers (`domain:defect`).
   - Proposes 1–3 dimensions (normal) or 2–4 (strict), combining related concerns into cohesive scopes.
4. Route context statuses exactly: `CONTEXT: AUTH` → `PR_REVIEW: AUTH`; `CONTEXT: NOT_FOUND` → `PR_REVIEW: NOT_FOUND`; `CONTEXT: ERROR` → `PR_REVIEW: REVIEW_ERROR`. On `CONTEXT: NEEDS_CONTEXT`, satisfy narrow request inline if possible.

### Chunk review

5. Dispatch one `chunk-reviewer` per dimension inside `WORKTREE_PATH`. Reviewers enforce 3-tier severity (`BLOCKER`, `IMPORTANT`, `SUGGESTION`), assign stable fingerprints (`domain:defect`), and discard style/nit notes.
6. Route `CHUNK: ERROR` to `PR_REVIEW: REVIEW_ERROR`. On `CHUNK: NEEDS_CONTEXT`, satisfy once and re-dispatch only that chunk. Proceed when all chunks return `CHUNK: PASS` or `CHUNK: NO_FINDINGS`.

### Adjudication

7. Dispatch `finding-adjudicator` with chunk findings and existing history digest.
   - Confirms, severity-adjusts, or drops candidates with written reasons.
   - Enforces lifecycle: `NEW`, `STILL_OPEN`, `RESOLVED`, `WITHDRAWN`, `OBSOLETE`.
   - Sets posting disposition: `NEW_THREAD`, `FOLLOW_UP`, `SUMMARY_ONLY`.
   - Sets all `SUGGESTION` findings to `SUMMARY_ONLY` (cheap suggestions rule).
8. Route `ADJUDICATE: ERROR` to `PR_REVIEW: REVIEW_ERROR`. On `ADJUDICATE: NO_FINDINGS`, skip `comment-drafter`, set candidate verdict (`🟢 PASS`), and proceed to Verify.

### Worktree Verification

9. In `WORKTREE_PATH`, execute targeted tests/validators based on changed files (e.g. `mvn test -Dtest=...`, `pnpm typecheck`, repo/docs validators). If execution cannot run, record `NOT RUN — <reason>`. Never run mutating commands in the original workspace.

### Comments

10. Dispatch `comment-drafter` to produce canonical package:
    - Verdict: `🔴 BLOCK` (>=1 BLOCKER) | `🟡 PASS WITH NOTES` (0 BLOCKER, >=1 IMPORTANT) | `🟢 PASS` (only SUGGESTION or no findings).
    - Summary includes bulleted list of suggestions and lifecycle counts.
    - Drafts self-contained inline comments for `BLOCKER` and `IMPORTANT` with hidden HTML markers (`<!-- lyreo-review fingerprint: ... reviewed-head: ... -->`).
    - Drafts thread replies for `STILL_OPEN`, `RESOLVED`, and `WITHDRAWN`.

### Verify

11. Dispatch `review-verifier` to validate evidence, 3-tier severity, verdict calculation, cheap suggestions rule, hidden markers, and worktree verification checks.
12. On `VERIFY: FAIL`, route to named `Fix target`. Stop after 2 repair cycles with `PR_REVIEW: VERIFY_FAIL`.

### Write

13. Dispatch `review-writer` to write `OUTPUT_FILE` in worktree and export persistent artifact to:
    `${XDG_STATE_HOME:-$HOME/.local/state}/lyreo-pr-review/<owner>-<repo>/pr-<number>-review.md`
14. In `draft-only` mode, run cleanup and finish with `PR_REVIEW: VERIFIED_DRAFT_SAVED`.

### Post

15. In `post-after-confirmation` mode (default), display the exact verified preview (verdict, summary, new comments with markers, and thread replies) and run `HUMAN_GATE_FINAL_PREVIEW_APPROVAL`.
16. If user declines, update artifact posting status to `cancelled`, run cleanup, and finish with `PR_REVIEW: VERIFIED_DRAFT_SAVED_POSTING_CANCELLED`.
17. On approval, dispatch `review-poster` to post atomic review and thread replies, then update artifact status to `posted`.

### Worktree Cleanup

18. On EVERY terminal outcome, run `../scripts/cleanup-pr-worktree.sh <WORKTREE_PATH> <SNAPSHOT_FILE>`.
    - Prunes worktree and removes temporary directory.
    - Verifies original branch, HEAD, and git status match snapshot.
    - If integrity check fails, report `WORKSPACE_INTEGRITY_MISMATCH` (do NOT run hard reset).

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
New comments: <count>
Follow-up replies: <count>
Review decision: <comment | request changes | approve>
Posting: <skipped | posted | cancelled>
Notes: <one-line residual risk or none>
```
