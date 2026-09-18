# Review File Template

> Read this file only from `review-writer` while assembling `OUTPUT_FILE`. Clean, concise user-facing format without internal mechanical debug noise (no internal line metadata or dedup dumps).

## Format 1: First Review

````markdown
# PR #<number> Review: <title>

## <🔴 BLOCK | 🟡 PASS WITH NOTES | 🟢 PASS>

<blocker_count> BLOCKER · <important_count> IMPORTANT · <suggestion_count> SUGGESTION

- **PR**: <PR_URL>
- **Reviewed**: base `<base_sha>` → head `<head_sha>`
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

- **[<path>:<line>]**: <concise non-blocking suggestion and learning rationale>
(or: *None*)

---

## Confirmed Good

- <Specific boundary, security control, contract, or test verified as sound>

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

---

## New Findings in Delta

### 1. [<BLOCKER | IMPORTANT>] [<path>:<line>] <finding title>

- **Fingerprint**: `<domain:defect>`
- **Issue**: <concise description>
- **Fix direction**: <guidance>

(or: *No new regressions found in incremental delta*)

---

## Suggestions

- **[<path>:<line>]**: <concise non-blocking suggestion>
(or: *None*)

---

## Confirmed Good

- <Specific verified boundary or regression check in delta>

---

## Final Decision

### <🔴 BLOCK | 🟡 PASS WITH NOTES | 🟢 PASS>

<Short summary sentence explaining the final decision and recommended GitHub review action>.
````

## Required Post-Write Check

After writing the file, confirm these sections exist:
- Top verdict header
- `## Verification`
- Findings / Previous findings
- `## Suggestions`
- `## Confirmed Good`
- `## Final Decision` with bottom verdict
