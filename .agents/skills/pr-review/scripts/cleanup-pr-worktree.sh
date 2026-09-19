#!/usr/bin/env bash
# Cleanup an isolated Git worktree and verify original workspace integrity.
# Usage: cleanup-pr-worktree.sh <worktree_path> [snapshot_file]
#
# Safety contract: NEVER delete a directory unless git confirms it is a registered
# worktree belonging to this repository. A /tmp path check alone is insufficient —
# a caller could pass any /tmp path and the old code would silently delete it.
set -euo pipefail

if [ "$#" -lt 1 ]; then
  printf '%s\n' "usage: $0 <worktree_path> [snapshot_file]" >&2
  exit 64
fi

worktree_path="$1"
snapshot_file="${2:-${worktree_path}.snapshot}"

original_root=""
expected_branch=""
expected_head=""

if [ -f "$snapshot_file" ]; then
  original_root="$(grep '^ORIGINAL_ROOT=' "$snapshot_file" | cut -d= -f2- || true)"
  expected_branch="$(grep '^ORIGINAL_BRANCH=' "$snapshot_file" | cut -d= -f2- || true)"
  expected_head="$(grep '^ORIGINAL_HEAD=' "$snapshot_file" | cut -d= -f2- || true)"
fi

if [ -z "$original_root" ]; then
  original_root="$(git rev-parse --show-toplevel 2>/dev/null || true)"
fi

# ---------------------------------------------------------------------------
# SAFETY: Prove that worktree_path is a MANAGED GIT WORKTREE before touching it.
#
# Strategy:
#   1. Resolve both the candidate path and every git worktree list path to
#      canonical real paths to defeat symlink confusion.
#   2. Only proceed with removal when we find an exact match in the list.
#   3. If we cannot prove membership, DO NOT delete — report cleanup error.
#
# git worktree list --porcelain output example:
#   worktree /abs/path/main-worktree
#   ...
#   worktree /tmp/pr-worktree-abc123-42
#   ...
# ---------------------------------------------------------------------------
is_managed_worktree() {
  local candidate="$1"
  local git_root="$2"

  # Canonicalise the candidate path (resolve symlinks if possible)
  local real_candidate
  real_candidate="$(realpath "$candidate" 2>/dev/null || echo "$candidate")"

  # List all worktrees registered with this repo
  local worktree_list
  worktree_list="$(git -C "$git_root" worktree list --porcelain 2>/dev/null)" || return 1

  # Extract "worktree <path>" lines and compare after canonicalisation
  while IFS= read -r line; do
    if [[ "$line" =~ ^worktree[[:space:]]+(.+)$ ]]; then
      local wt_path="${BASH_REMATCH[1]}"
      local real_wt
      real_wt="$(realpath "$wt_path" 2>/dev/null || echo "$wt_path")"
      if [ "$real_candidate" = "$real_wt" ]; then
        return 0  # confirmed managed worktree
      fi
    fi
  done <<< "$worktree_list"

  return 1  # not found in worktree list
}

# Remove worktree safely
cleanup_ok=true
if [ -n "$original_root" ] && [ -d "$original_root" ]; then
  # First attempt: let git remove it (preferred — updates .git/worktrees index too)
  if [ -d "$worktree_path" ]; then
    if is_managed_worktree "$worktree_path" "$original_root"; then
      git -C "$original_root" worktree remove --force "$worktree_path" 2>/dev/null || true
      git -C "$original_root" worktree prune 2>/dev/null || true
    else
      printf 'SAFETY: "%s" is NOT a registered git worktree for repo at "%s" — refusing to delete.\n' \
        "$worktree_path" "$original_root" >&2
      printf 'SAFETY: If this path is stale, remove it manually after verifying it is safe.\n' >&2
      cleanup_ok=false
    fi
  fi
else
  printf 'SAFETY: Cannot determine repository root — skipping worktree removal.\n' >&2
  cleanup_ok=false
fi

# If the directory still exists after git worktree remove, it was already proven
# to be a managed worktree above, so rm -rf is safe here.
if [ -d "$worktree_path" ] && [ "$cleanup_ok" = "true" ]; then
  if is_managed_worktree "$worktree_path" "$original_root" 2>/dev/null; then
    # Still in the list (git worktree remove failed silently) — force remove
    rm -rf "$worktree_path"
    git -C "$original_root" worktree prune 2>/dev/null || true
  else
    # git worktree remove succeeded and unregistered it; the directory may linger
    # only if the OS delayed the unlink. Safe to remove if proven clean.
    rm -rf "$worktree_path"
  fi
fi

# Verify original workspace integrity
# Three outcomes:
#   PASS               — snapshot present, all fields match
#   INTEGRITY_MISMATCH — snapshot present, one or more fields differ
#   INTEGRITY_UNVERIFIED — snapshot missing (cannot confirm nothing changed)
integrity_status="PASS"
if [ -n "$original_root" ] && [ -f "$snapshot_file" ]; then
  current_branch="$(git -C "$original_root" branch --show-current 2>/dev/null || true)"
  current_head="$(git -C "$original_root" rev-parse HEAD 2>/dev/null || true)"

  if [ "$current_branch" != "$expected_branch" ]; then
    printf 'WORKSPACE_INTEGRITY_MISMATCH: Branch changed! Expected "%s", got "%s"\n' "$expected_branch" "$current_branch" >&2
    integrity_status="MISMATCH"
  fi

  if [ "$current_head" != "$expected_head" ]; then
    printf 'WORKSPACE_INTEGRITY_MISMATCH: HEAD changed! Expected "%s", got "%s"\n' "$expected_head" "$current_head" >&2
    integrity_status="MISMATCH"
  fi

  # Extract expected status from snapshot file
  expected_status="$(awk '/=== STATUS_START ===/{flag=1;next}/=== STATUS_END ===/{flag=0}flag' "$snapshot_file")"
  current_status="$(git -C "$original_root" status --porcelain)"

  if [ "$current_status" != "$expected_status" ]; then
    printf 'WORKSPACE_INTEGRITY_MISMATCH: Git status modified!\n' >&2
    printf '%s\n%s\n' "--- Expected status ---" "$expected_status" >&2
    printf '%s\n%s\n' "--- Current status ---" "$current_status" >&2
    integrity_status="MISMATCH"
  fi
elif [ -n "$original_root" ]; then
  # Snapshot file was not found — cannot verify workspace integrity.
  # This could mean prepare-pr-worktree.sh did not record a snapshot, or it was
  # already removed by a prior cleanup run. Either way: report honestly.
  printf 'WORKSPACE_INTEGRITY_UNVERIFIED: snapshot file not found at "%s" — cannot confirm workspace is unchanged.\n' "$snapshot_file" >&2
  integrity_status="UNVERIFIED"
fi

if [ "$cleanup_ok" = "false" ]; then
  printf '%s\n' "WORKTREE_CLEANUP: SAFETY_ERROR (refused to delete unverified path — see stderr for details)" >&2
  exit 2
fi

case "$integrity_status" in
  PASS)
    rm -f "$snapshot_file"
    printf '%s\n' "WORKTREE_CLEANUP: PASS (workspace integrity verified)"
    exit 0
    ;;
  MISMATCH)
    printf '%s\n' "WORKTREE_CLEANUP: INTEGRITY_MISMATCH (preserving original state without hard reset)" >&2
    exit 1
    ;;
  UNVERIFIED)
    printf '%s\n' "WORKTREE_CLEANUP: INTEGRITY_UNVERIFIED (snapshot missing — workspace change cannot be confirmed)" >&2
    exit 3
    ;;
esac
