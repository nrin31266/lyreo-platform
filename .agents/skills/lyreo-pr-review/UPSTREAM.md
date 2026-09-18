# Upstream Skill Provenance

- **Source repository**: `https://github.com/b-mendoza/agent-skills`
- **Source skill path**: `skills/review-pull-request/`
- **Upstream commit SHA**: `14d79d993b448aac50f2f7493e0903e629350155`
- **Vendor date**: 2026-09-18

## Lyreo-Specific Customizations

1. **Isolated PR worktree lifecycle**: Review execution and verification run inside a temporary detached Git worktree; the original working tree is treated as immutable and strictly protected.
2. **Persistent external artifact storage**: Review artifacts are stored outside the Git repository in `${XDG_STATE_HOME:-$HOME/.local/state}/lyreo-pr-review/<owner>-<repo>/` to prevent untracked file pollution.
3. **Lyreo review policy and 3-tier severity model**: Replaced `nit`/`blocking` with `BLOCKER`, `IMPORTANT`, and `SUGGESTION`. Token-conscious: suggestions are summary-only bullets without individual inline GitHub threads.
4. **Deterministic top/bottom verdicts**: `🔴 BLOCK`, `🟡 PASS WITH NOTES`, `🟢 PASS` mapped to GitHub review actions (`REQUEST_CHANGES`, `COMMENT`, `APPROVE`) with a mandatory human preview gate.
5. **Review modes (`normal` / `strict`)**: `normal` (default, 1–3 dimensions, max ~4 IMPORTANT, max 2 SUGGESTIONs) vs `strict` (2–4 dimensions, deeper failure/boundary inspection).
6. **Re-review continuity and finding lifecycle**: Extended finding lifecycle (`NEW`, `STILL_OPEN`, `RESOLVED`, `WITHDRAWN`, `OBSOLETE`), stable semantic fingerprints (`domain:behavioral-defect`), hidden HTML markers for thread tracking, and incremental diff analysis (`previous_head...current_head`).
7. **Repository context routing**: Context collector consults root `AGENTS.md` and `docs/README.md` as routers to load only relevant owner documentation.
