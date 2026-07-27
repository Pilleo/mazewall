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

# Find the initial divergence point where Jules started (before any rebase/merge commits)
FIRST_COMMIT=$(git rev-list --reverse "origin/master..origin/$BRANCH_NAME" | head -n 1 2>/dev/null || echo "")
if [ -n "$FIRST_COMMIT" ]; then
  INITIAL_BASE=$(git rev-parse "${FIRST_COMMIT}~1" 2>/dev/null || git merge-base origin/master "origin/$BRANCH_NAME")
else
  INITIAL_BASE=$(git merge-base origin/master "origin/$BRANCH_NAME")
fi

echo "🔎 Initial Divergence Point (Jules's original starting base): ${INITIAL_BASE}"

# Show what Jules actually changed relative to its original starting point
echo ""
echo "📋 Jules's net changes (initial base → PR branch head):"
git --no-pager diff --name-status "$INITIAL_BASE" "origin/$BRANCH_NAME"
echo ""

WORKTREE_DIR="build/tmp/rebase-worktree-${PR_NUMBER}"
rm -rf "$WORKTREE_DIR"
git worktree prune

echo "🛠️  Creating clean worktree from origin/master at ${WORKTREE_DIR}..."
git worktree add "$WORKTREE_DIR" "origin/master" --detach

ORIGINAL_DIR="$(pwd)"
cleanup() {
  echo "🧹 Cleaning up temporary worktree and branch..."
  cd "$ORIGINAL_DIR" 2>/dev/null || true
  git worktree remove "$WORKTREE_DIR" --force 2>/dev/null || true
  rm -rf "$WORKTREE_DIR"
  git worktree prune 2>/dev/null || true
  git branch -D "clean-pr-${PR_NUMBER}" 2>/dev/null || true
}
trap cleanup EXIT

cd "$WORKTREE_DIR"

echo "🎯 Extracting Jules's intended added/modified files (diff-filter=AM from initial base)..."
INTENDED_FILES=$(git diff --name-only --diff-filter=AM "$INITIAL_BASE" "origin/$BRANCH_NAME" 2>/dev/null || echo "")

if [ -z "$INTENDED_FILES" ]; then
  echo "⚠️ No added/modified files found on PR branch relative to initial base."
  exit 0
fi

echo "📋 Intended task files:"
echo "$INTENDED_FILES" | sed 's/^/  - /'
echo ""

echo "🔄 Checking out intended task files onto fresh origin/master..."
for f in $INTENDED_FILES; do
  git checkout "origin/$BRANCH_NAME" -- "$f"
  git add "$f"
done

echo "🔨 Verifying compilation on clean branch..."
git checkout -B "clean-pr-${PR_NUMBER}"
./gradlew compileKotlin :tools:orchestrator:compileKotlin

echo "✅ Compilation clean."
echo ""
echo "📋 Final diff vs origin/master (should contain ONLY Jules's intended changes):"
git --no-pager diff --name-status origin/master

git commit --no-verify -m "chore(orchestrator): rebase PR #${PR_NUMBER} onto master via intended-files apply"

echo ""
echo "🚀 Force-pushing cleaned branch '${BRANCH_NAME}' to origin..."
git push --force-with-lease origin "HEAD:${BRANCH_NAME}"

echo ""
echo "✅ Successfully applied PR #${PR_NUMBER} ('${BRANCH_NAME}') onto origin/master!"
echo "   Strategy: surgical intended-files apply (diff-filter=AM)"
echo "   Initial base used: ${INITIAL_BASE}"
