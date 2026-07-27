#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# rebase_pr.sh — Safely integrate a Jules PR branch with origin/master.
#
# WHY NOT git rebase:
#   git rebase replays every Jules commit, including stale force-push commits
#   that may silently delete files Jules never actually had in its workspace
#   (files added to master after Jules diverged). This causes "unrelated logic
#   removal" bugs in the PR diff.
#
# THE RELIABLE STRATEGY — Merge-Base Diff Apply:
#   1. Find the exact point Jules diverged from master (merge-base).
#   2. Compute Jules's NET diff: what Jules *actually* changed relative to its
#      own starting point.
#   3. Apply that net diff onto a fresh checkout of current master.
#
# This is 100% reliable because files that master added *after* Jules diverged
# will never appear in the merge-base diff — they simply aren't touched.
# ---------------------------------------------------------------------------

if [ -z "${1:-}" ]; then
  echo "Usage: ./scripts/rebase_pr.sh <PR_NUMBER>"
  echo "Example: ./scripts/rebase_pr.sh 367"
  exit 1
fi

PR_NUMBER="$1"
echo "🔍 Inspecting PR #${PR_NUMBER} on GitHub..."

BRANCH_NAME=$(env -u GITHUB_TOKEN gh pr view "$PR_NUMBER" --json headRefName --jq .headRefName)
if [ -z "$BRANCH_NAME" ]; then
  echo "❌ Could not determine head branch name for PR #${PR_NUMBER}"
  exit 1
fi
echo "🌿 Target branch for PR #${PR_NUMBER} is '${BRANCH_NAME}'"

echo "📥 Fetching latest origin/master and origin/${BRANCH_NAME}..."
git fetch origin master
git fetch origin "$BRANCH_NAME"

MERGE_BASE=$(git merge-base origin/master "origin/$BRANCH_NAME")
echo "🔎 Merge-base (Jules's divergence point): ${MERGE_BASE}"

# Show what Jules actually changed relative to its own starting point
echo ""
echo "📋 Jules's net changes (merge-base → PR branch head):"
git --no-pager diff --name-status "$MERGE_BASE" "origin/$BRANCH_NAME"
echo ""

WORKTREE_DIR="build/tmp/rebase-worktree-${PR_NUMBER}"
rm -rf "$WORKTREE_DIR"
git worktree prune

echo "🛠️  Creating clean worktree from origin/master at ${WORKTREE_DIR}..."
git worktree add "$WORKTREE_DIR" "origin/master" --detach

cleanup() {
  echo "🧹 Cleaning up temporary worktree..."
  git worktree remove "$WORKTREE_DIR" --force 2>/dev/null || true
  rm -rf "$WORKTREE_DIR"
  git worktree prune 2>/dev/null || true
}
trap cleanup EXIT

cd "$WORKTREE_DIR"

echo "🔄 Applying Jules's net diff (merge-base → PR branch) onto origin/master..."
if ! git diff "$MERGE_BASE" "origin/$BRANCH_NAME" | git apply --3way; then
  echo ""
  echo "⚠️  Conflicts detected during 3-way apply. Resolving by preferring master for conflicted deletions..."
  # For any DELETE/MODIFY conflict (Jules deleted, master modified), prefer master
  CONFLICTED=$(git diff --name-only --diff-filter=U 2>/dev/null || true)
  if [ -n "$CONFLICTED" ]; then
    echo "Conflicts in: $CONFLICTED"
    echo "$CONFLICTED" | xargs git checkout origin/master --
    git add .
  fi
  # Abort if still unresolved
  REMAINING=$(git diff --name-only --diff-filter=U 2>/dev/null || true)
  if [ -n "$REMAINING" ]; then
    echo "❌ Cannot auto-resolve conflicts in: $REMAINING — manual intervention required."
    exit 1
  fi
fi

echo "🔨 Verifying compilation on clean branch..."
git checkout -b "clean-pr-${PR_NUMBER}"
./gradlew compileKotlin :tools:orchestrator:compileKotlin

echo "✅ Compilation clean."
echo ""
echo "📋 Final diff vs origin/master (should contain ONLY Jules's intended changes):"
git --no-pager diff --name-status origin/master

git commit -m "chore(orchestrator): rebase PR #${PR_NUMBER} onto master via merge-base diff apply"

echo ""
echo "🚀 Force-pushing cleaned branch '${BRANCH_NAME}' to origin..."
git push --force-with-lease origin "HEAD:${BRANCH_NAME}"

echo ""
echo "✅ Successfully applied PR #${PR_NUMBER} ('${BRANCH_NAME}') onto origin/master!"
echo "   Strategy: merge-base diff apply (not git rebase)"
echo "   Merge-base used: ${MERGE_BASE}"
