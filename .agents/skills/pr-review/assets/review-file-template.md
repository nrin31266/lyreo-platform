# Review File Template

> **Ownership**: `comment-drafter` is the ONLY agent that reads this file and renders `CANONICAL_BODY` from it. `review-writer` saves the resulting bytes verbatim and never re-renders from this template. `review-verifier` validates the body. `review-poster` posts the body exactly as written. No other subagent reads this file.
>
> Do NOT render internal debugging mechanics (internal line-metadata dumps, dedup markers, draft-comment wrappers) — keep the document high-signal and readable.

The review file must stand alone without chat context. It is findings first, concise, and explicit about dimensions reviewed, residual risks, and posting status. The posting-status vocabulary is exactly `draft`, `posted`, `cancelled`, `failed`; `review-writer` update mode rewrites that value after the posting decision.

## Format 1: First Review

````markdown
# PR #<number> Review: <title>

## <🔴 BLOCK | 🟡 PASS WITH NOTES | 🟢 PASS>

<blocker_count> BLOCKER · <important_count> IMPORTANT · <suggestion_count> SUGGESTION

- **PR**: <PR_URL>
- **Reviewed**: base `<base_sha>` → head `<head_sha>`
- **Dimensions**: <comma-separated dimension names>
- **Mode**: `<normal | strict>`
- **Posting status**: `<draft | posted | cancelled | failed>`

---

## Verification

| Check | Target | Status | Notes |
|---|---|---|---|
| <check name> | <target path or command> | <PASS \| FAIL \| NOT RUN> | <notes or reason> |

---

## Must Fix (BLOCKER)

### 1. [<path>:<line>] <finding title>

- **Issue**: <concise description of defect and failure scenario>
- **Impact**: <why this matters>
- **Fix direction**: <concrete resolution guidance>
- **Sources**: <URL or omitted if internal>

(or: *None*)

---

## Important

### 1. [<path>:<line>] <finding title>

- **Issue**: <concise description of issue>
- **Impact**: <why this matters>
- **Fix direction**: <concrete resolution guidance>
- **Sources**: <URL or omitted if internal>

(or: *None*)

---

## Suggestions

- **[<path>:<line>]**: <concise non-blocking suggestion and rationale>

(or: *None*)

---

## Confirmed Good

- <Specific boundary, security control, contract, or test verified as sound>

(or: *None specifically recorded.*)

---

## Final Decision

### <🔴 BLOCK | 🟡 PASS WITH NOTES | 🟢 PASS>

<Short summary sentence explaining the final decision and recommended GitHub review action: REQUEST_CHANGES | COMMENT | APPROVE>.
````

## Format 2: Incremental Re-review

````markdown
# PR #<number> Re-review: <title>

## <🔴 BLOCK | 🟡 PASS WITH NOTES | 🟢 PASS>

<blocker_count> BLOCKER · <important_count> IMPORTANT · <suggestion_count> SUGGESTION

- **PR**: <PR_URL>
- **Reviewed incremental delta**: previous `<previous_head_sha>` → current `<head_sha>`
- **Dimensions**: <comma-separated dimension names>
- **Mode**: `<normal | strict>`
- **Posting status**: `<draft | posted | cancelled | failed>`

---

## Verification

| Check | Target | Status | Notes |
|---|---|---|---|
| <check name> | <target path or command> | <PASS \| FAIL \| NOT RUN> | <notes or reason> |

---

## Previous Findings

- ✅ **RESOLVED** [`<fingerprint>`]: <description> (verified in `<short_sha>`)
- ⚠️ **STILL OPEN** [`<fingerprint>`]: <description> (still unresolved because `<evidence>`)
- ↩️ **WITHDRAWN** [`<fingerprint>`]: <description> (withdrawn: `<reason>`)
- 🗑️ **OBSOLETE** [`<fingerprint>`]: <description> (code refactored/removed)

(or: *None*)

---

## New Findings in Delta

### 1. [<BLOCKER | IMPORTANT>] [<path>:<line>] <finding title>

- **Fingerprint**: `<domain:defect>`
- **Issue**: <concise description>
- **Impact**: <why this matters>
- **Fix direction**: <guidance>
- **Sources**: <URL or omitted if internal>

(or: *No new regressions found in incremental delta*)

---

## Suggestions

- **[<path>:<line>]**: <concise non-blocking suggestion>

(or: *None*)

---

## Confirmed Good

- <Specific verified boundary or regression check in delta>

(or: *None specifically recorded.*)

---

## Final Decision

### <🔴 BLOCK | 🟡 PASS WITH NOTES | 🟢 PASS>

<Short summary sentence explaining the final decision and recommended GitHub review action>.
````

## Required Sections Checklist (for `comment-drafter` and `review-verifier`)

`comment-drafter` must produce a `CANONICAL_BODY` that contains all of these sections (omit empty ones, but include the header if the section has any content). `review-verifier` must confirm all required sections are present:
- Top verdict header (`## <verdict emoji>`)
- `## Verification` (or equivalent — may be in the writer artifact wrapper, not the GitHub body)
- Findings sections (Format 1: `## Must Fix` / `## Important`; Format 2: `## Previous Findings` / `## New Findings in Delta`)
- `## Suggestions`
- `## Confirmed Good`
- `## Final Decision` with bottom verdict
