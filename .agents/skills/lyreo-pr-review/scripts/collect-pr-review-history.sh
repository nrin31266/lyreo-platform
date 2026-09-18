#!/usr/bin/env bash
# Collect PR review history (reviews, inline comment threads, replies, and Lyreo markers) via gh.
# Usage: collect-pr-review-history.sh <owner>/<repo>/pull/<number>|PR_URL
set -euo pipefail

if [ "$#" -ne 1 ]; then
  printf '%s\n' "usage: $0 <owner>/<repo>/pull/<number>|PR_URL" >&2
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
else
  printf '%s\n' "unrecognized PR reference: $raw" >&2
  exit 65
fi

if ! command -v gh >/dev/null 2>&1; then
  printf '%s\n' "gh CLI required" >&2
  exit 2
fi

if ! command -v jq >/dev/null 2>&1; then
  printf '%s\n' "jq required" >&2
  exit 2
fi

reviews_json="$(gh api --paginate "repos/${owner}/${repo}/pulls/${number}/reviews" 2>/dev/null || printf '[]')"
comments_json="$(gh api --paginate "repos/${owner}/${repo}/pulls/${number}/comments" 2>/dev/null || printf '[]')"
issue_comments_json="$(gh api --paginate "repos/${owner}/${repo}/issues/${number}/comments" 2>/dev/null || printf '[]')"

# Process and group into a structured history digest using jq
jq -n \
  --arg pr "${owner}/${repo}#${number}" \
  --argjson reviews "$reviews_json" \
  --argjson comments "$comments_json" \
  --argjson issue_comments "$issue_comments_json" '
  # Helper to parse lyreo marker
  def parse_marker(text):
    if text | test("<!--[\\s\\S]*?lyreo-review[\\s\\S]*?fingerprint:\\s*([^\\s]+)[\\s\\S]*?reviewed-head:\\s*([^\\s]+)[\\s\\S]*?-->") then
      {
        fingerprint: (text | capture("fingerprint:\\s*(?<fp>[^\\s]+)") | .fp),
        reviewed_head: (text | capture("reviewed-head:\\s*(?<head>[^\\s]+)") | .head)
      }
    else
      { fingerprint: null, reviewed_head: null }
    end;

  # Process reviews
  ($reviews | map({
    id: .id,
    user: .user.login,
    state: .state,
    body: .body,
    commit_id: .commit_id,
    submitted_at: .submitted_at
  })) as $parsed_reviews |

  # Process inline comments and group by thread root
  ($comments | map(. + { marker: parse_marker(.body) })) as $comments_with_markers |
  ($comments_with_markers | map(select(.in_reply_to_id == null))) as $root_comments |

  ($root_comments | map(
    . as $root |
    ($comments_with_markers | map(select(.in_reply_to_id == $root.id))) as $replies |
    {
      root_comment_id: $root.id,
      path: $root.path,
      line: ($root.line // $root.original_line),
      side: ($root.side // "RIGHT"),
      fingerprint: $root.marker.fingerprint,
      reviewed_head: $root.marker.reviewed_head,
      root_body: $root.body,
      root_user: $root.user.login,
      created_at: $root.created_at,
      replies: ($replies | map({
        id: .id,
        user: .user.login,
        body: .body,
        created_at: .created_at,
        marker: .marker
      }))
    }
  )) as $threads |

  # Find latest reviewed head from markers or reviews
  (
    [
      ($parsed_reviews[] | select(.commit_id != null) | .commit_id),
      ($threads[] | .reviewed_head // empty),
      ($threads[].replies[] | .marker.reviewed_head // empty)
    ] | last // null
  ) as $latest_reviewed_head |

  {
    pr: $pr,
    latest_reviewed_head: $latest_reviewed_head,
    reviews: $parsed_reviews,
    threads: $threads,
    conversation_comments: ($issue_comments | map({
      id: .id,
      user: .user.login,
      body: .body,
      created_at: .created_at
    }))
  }
'
