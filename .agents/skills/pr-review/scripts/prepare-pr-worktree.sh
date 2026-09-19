#!/usr/bin/env bash
# Prepare an isolated Git worktree for reviewing a GitHub PR without mutating
# the caller's active working tree.
# Usage: prepare-pr-worktree.sh <owner>/<repo>/pull/<number>|PR_URL [target_worktree_path]
set -euo pipefail

if [ "$#" -lt 1 ]; then
  printf '%s\n' "usage: $0 <owner>/<repo>/pull/<number>|PR_URL [target_worktree_path]" >&2
  exit 64
fi

raw="$1"
if [[ "$raw" =~ github\.com/([^/]+)/([^/]+)/pull/([0-9]+) ]]; then
  owner="${BASH_REMATCH[1]}"
  repo="${BASH_REMATCH[2]}"
  number="${BASH_REMATCH[3]}"
elif [[ "$raw" =~ ^([^/]+)/([^/]+)/pull/([0-9]+)$ ]]; then
  owner="${BASH_REMATCH[1]}"
  repo="${BASH_REMATCH[2]}"
  number="${BASH_REMATCH[3]}"
elif [[ "$raw" =~ ^([0-9]+)$ ]]; then
  number="${BASH_REMATCH[1]}"
  owner=""
  repo=""
else
  printf '%s\n' "unrecognized PR reference: $raw" >&2
  exit 65
fi

if ! command -v git >/dev/null 2>&1; then
  printf '%s\n' "git required" >&2
  exit 2
fi

original_root="$(git rev-parse --show-toplevel 2>/dev/null || true)"
if [ -z "$original_root" ]; then
  printf '%s\n' "current directory is not inside a git repository" >&2
  exit 2
fi

# Validate PR URL belongs to current repository (when owner/repo are known)
if [ -n "$owner" ] && [ -n "$repo" ]; then
  remote="origin"
  if ! git -C "$original_root" remote get-url "$remote" >/dev/null 2>&1; then
    remote="$(git -C "$original_root" remote | head -n 1)"
  fi
  if [ -n "$remote" ]; then
    remote_url="$(git -C "$original_root" remote get-url "$remote" 2>/dev/null || true)"
    # Normalize remote URL: remove trailing .git and leading protocol+host
    remote_slug="$(printf '%s' "$remote_url" | sed 's/\.git$//' | grep -oE '[^/:]+/[^/:]+$' || true)"
    pr_slug="${owner}/${repo}"
    if [ -n "$remote_slug" ] && [ "$remote_slug" != "$pr_slug" ]; then
      printf 'PR URL repository (%s) does not match current repository (%s)\n' "$pr_slug" "$remote_slug" >&2
      exit 66
    fi
  fi
fi

original_branch="$(git -C "$original_root" branch --show-current 2>/dev/null || true)"
original_head="$(git -C "$original_root" rev-parse HEAD 2>/dev/null || true)"

# Determine remote to fetch from
remote="origin"
if ! git -C "$original_root" remote get-url "$remote" >/dev/null 2>&1; then
  remote="$(git -C "$original_root" remote | head -n 1)"
fi

if [ -z "$remote" ]; then
  printf '%s\n' "no git remote found to fetch PR" >&2
  exit 2
fi

if [ "$#" -ge 2 ] && [ -n "$2" ]; then
  worktree_path="$2"
else
  # Deterministic path: based on repo root hash + PR number, no random suffix
  root_hash="$(printf '%s' "$original_root" | sha1sum | cut -c1-8)"
  worktree_path="/tmp/pr-worktree-${root_hash}-${number}"
fi

snapshot_file="${worktree_path}.snapshot"
mkdir -p "$(dirname "$worktree_path")"

# Record snapshot of original workspace
{
  printf 'ORIGINAL_ROOT=%s\n' "$original_root"
  printf 'ORIGINAL_BRANCH=%s\n' "$original_branch"
  printf 'ORIGINAL_HEAD=%s\n' "$original_head"
  printf '=== STATUS_START ===\n'
  git -C "$original_root" status --porcelain
  printf '=== STATUS_END ===\n'
} > "$snapshot_file"

# Attempt worktree creation
# Try `gh pr checkout --worktree` if supported, otherwise fallback to safe git fetch + worktree add
worktree_created=false
if command -v gh >/dev/null 2>&1; then
  if gh pr checkout "$number" --worktree "$worktree_path" --detach 2>/dev/null; then
    worktree_created=true
  fi
fi

if [ "$worktree_created" != "true" ]; then
  # Fetch PR head into FETCH_HEAD without updating any local branches or current working tree
  git -C "$original_root" fetch "$remote" "pull/${number}/head" --quiet
  pr_head_sha="$(git -C "$original_root" rev-parse FETCH_HEAD)"
  git -C "$original_root" worktree add --detach "$worktree_path" "$pr_head_sha" --quiet
else
  pr_head_sha="$(git -C "$worktree_path" rev-parse HEAD)"
fi

printf 'WORKTREE_PATH=%s\n' "$worktree_path"
printf 'SNAPSHOT_FILE=%s\n' "$snapshot_file"
printf 'PR_HEAD_SHA=%s\n' "$pr_head_sha"
printf 'ORIGINAL_ROOT=%s\n' "$original_root"
printf 'ORIGINAL_BRANCH=%s\n' "$original_branch"
printf 'ORIGINAL_HEAD=%s\n' "$original_head"
