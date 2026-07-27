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
# THE RELIABLE STRATEGY — git merge origin/master:
#   Preserves Jules's original commits and authorship — they remain signed
#   by the bot/author. The resulting PR diff contains exactly Jules's session
#   changes relative to master. Standard git merge semantics handle conflicts
#   correctly and isolate the integration cleanly.
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

WORKTREE_DIR="build/tmp/merge-worktree-${PR_NUMBER}"
rm -rf "$WORKTREE_DIR"
git worktree prune || true

echo "🛠️  Creating clean worktree from origin/${BRANCH_NAME} at ${WORKTREE_DIR}..."
git worktree add "$WORKTREE_DIR" "origin/${BRANCH_NAME}" --detach

ORIGINAL_DIR="$(pwd)"
cleanup() {
  echo "🧹 Cleaning up temporary worktree..."
  cd "$ORIGINAL_DIR" 2>/dev/null || true
  git worktree remove "$WORKTREE_DIR" --force 2>/dev/null || true
  rm -rf "$WORKTREE_DIR"
  git worktree prune 2>/dev/null || true
}
trap cleanup EXIT

cd "$WORKTREE_DIR"

echo "🔄 Merging origin/master into branch '${BRANCH_NAME}'..."
if ! git merge origin/master --no-edit -m "chore: merge master into PR #$PR_NUMBER to keep up to date"; then
  echo "❌ Merge conflict on PR #${PR_NUMBER}! Aborting..."
  git merge --abort
  exit 1
fi

# Check if anything actually changed (branch might already be up to date)
AHEAD_OF_MASTER=$(git rev-list --count "origin/master..HEAD" || echo "0")
if [ "$AHEAD_OF_MASTER" -eq 0 ]; then
  echo "⚠️ Nothing to merge for PR #${PR_NUMBER} — already up to date"
  exit 0
fi

echo "🔨 Verifying compilation on merged branch..."
./gradlew compileKotlin :tools:orchestrator:compileKotlin

echo "✅ Compilation clean."
echo ""

echo "🚀 Force-pushing merged branch '${BRANCH_NAME}' to origin..."
git push --force-with-lease origin "HEAD:${BRANCH_NAME}"

echo ""
echo "✅ Successfully merged master into PR #${PR_NUMBER} ('${BRANCH_NAME}')!"
