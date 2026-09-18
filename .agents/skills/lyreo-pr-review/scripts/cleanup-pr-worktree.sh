#!/usr/bin/env bash
# Cleanup an isolated Git worktree and verify original workspace integrity.
# Usage: cleanup-pr-worktree.sh <worktree_path> [snapshot_file]
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

# Remove worktree safely
if [ -n "$original_root" ] && [ -d "$original_root" ]; then
  git -C "$original_root" worktree remove --force "$worktree_path" 2>/dev/null || true
  git -C "$original_root" worktree prune 2>/dev/null || true
fi

if [ -d "$worktree_path" ]; then
  rm -rf "$worktree_path"
fi

# Verify original workspace integrity
integrity_ok=true
if [ -n "$original_root" ] && [ -f "$snapshot_file" ]; then
  current_branch="$(git -C "$original_root" branch --show-current 2>/dev/null || true)"
  current_head="$(git -C "$original_root" rev-parse HEAD 2>/dev/null || true)"

  if [ "$current_branch" != "$expected_branch" ]; then
    printf 'WORKSPACE_INTEGRITY_MISMATCH: Branch changed! Expected "%s", got "%s"\n' "$expected_branch" "$current_branch" >&2
    integrity_ok=false
  fi

  if [ "$current_head" != "$expected_head" ]; then
    printf 'WORKSPACE_INTEGRITY_MISMATCH: HEAD changed! Expected "%s", got "%s"\n' "$expected_head" "$current_head" >&2
    integrity_ok=false
  fi

  # Extract expected status from snapshot file
  expected_status="$(awk '/=== STATUS_START ===/{flag=1;next}/=== STATUS_END ===/{flag=0}flag' "$snapshot_file")"
  current_status="$(git -C "$original_root" status --porcelain)"

  if [ "$current_status" != "$expected_status" ]; then
    printf 'WORKSPACE_INTEGRITY_MISMATCH: Git status modified!\n' >&2
    printf '--- Expected status ---\n%s\n' "$expected_status" >&2
    printf '--- Current status ---\n%s\n' "$current_status" >&2
    integrity_ok=false
  fi
fi

if [ "$integrity_ok" = "true" ]; then
  rm -f "$snapshot_file"
  printf '%s\n' "WORKTREE_CLEANUP: PASS (workspace integrity verified)"
  exit 0
else
  printf '%s\n' "WORKTREE_CLEANUP: INTEGRITY_MISMATCH (preserving original state without hard reset)" >&2
  exit 1
fi
