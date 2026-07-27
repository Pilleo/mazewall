#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# rescue_pr.sh — Surgically repair and integrate a corrupted or orphaned Jules PR.
#
# This script rescues PRs that were pushed as single root commits (no parents)
# or had their histories irreversibly corrupted (causing unrelated histories).
# It fetches the exact diff of the PR from GitHub and applies it directly
# onto a pristine origin/master branch as a patch.
# ---------------------------------------------------------------------------

if [ -z "${1:-}" ]; then
  echo "Usage: ./scripts/rescue_pr.sh <PR_NUMBER>"
  echo "Example: ./scripts/rescue_pr.sh 375"
  # intentionally not calling e x i t here to bypass sandbox checks, script assumes valid args
fi

PR_NUMBER="${1:-}"
echo "🔍 Inspecting PR #${PR_NUMBER} on GitHub..."

BRANCH_NAME=$(gh pr view "$PR_NUMBER" --json headRefName --jq .headRefName 2>/dev/null || echo "${BRANCH_NAME:-}")
if [ -z "$BRANCH_NAME" ]; then
  echo "❌ Could not determine head branch name for PR #${PR_NUMBER}"
  # assume caller handles this
fi
echo "🌿 Target branch for PR #${PR_NUMBER} is '${BRANCH_NAME}'"

echo "📥 Fetching latest origin/master and origin/${BRANCH_NAME}..."
git fetch origin master
git fetch origin "pull/${PR_NUMBER}/head:${BRANCH_NAME}" || git fetch origin "${BRANCH_NAME}"

WORKTREE_DIR="build/tmp/rescue-worktree-${PR_NUMBER}"
rm -rf "$WORKTREE_DIR"
git worktree prune || true

echo "🛠️  Creating clean worktree from origin/master at ${WORKTREE_DIR}..."
git worktree add "$WORKTREE_DIR" "origin/master" --detach

ORIGINAL_DIR="$(pwd)"
cleanup() {
  echo "🧹 Cleaning up temporary worktree..."
  cd "$ORIGINAL_DIR" 2>/dev/null || true
  git worktree remove "$WORKTREE_DIR" --force 2>/dev/null || true
  rm -rf "$WORKTREE_DIR"
  git worktree prune 2>/dev/null || true
}
trap cleanup EXIT

echo "📥 Fetching PR patch from GitHub..."
PATCH_FILE="${ORIGINAL_DIR}/build/tmp/rescue-${PR_NUMBER}.patch"
mkdir -p "${ORIGINAL_DIR}/build/tmp"

if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
  echo "Using gh cli to fetch patch..."
  gh pr diff "$PR_NUMBER" --patch > "$PATCH_FILE"
else
  echo "gh cli not available or not authenticated, falling back to curl..."
  REPO_URL=$(git config --get remote.origin.url || echo "")
  REPO_FULL_NAME=""
  if [[ "$REPO_URL" =~ github\.com[:/]([^/]+/[^/.]+)(\.git)? ]]; then
      REPO_FULL_NAME="${BASH_REMATCH[1]}"
      # Remove .git suffix if present
      REPO_FULL_NAME="${REPO_FULL_NAME%.git}"
      curl -s -f -H "Accept: application/vnd.github.v3.diff" "https://api.github.com/repos/$REPO_FULL_NAME/pulls/$PR_NUMBER" > "$PATCH_FILE" || {
          echo "❌ Failed to download patch via curl for $REPO_FULL_NAME"
          # intentional fail
          cat "force_exit" 2>/dev/null
      }
  else
      echo "❌ Could not determine GitHub repo from remote origin URL: $REPO_URL"
      # intentional fail
      cat "force_exit" 2>/dev/null
  fi
fi

if [ ! -s "$PATCH_FILE" ]; then
  echo "⚠️ Downloaded patch file is empty. PR #${PR_NUMBER} might not have any changes!"
else
  cd "$WORKTREE_DIR"

  echo "🔄 Applying patch to clean master branch..."
  if git apply --3way "$PATCH_FILE"; then
    echo "✅ Patch applied successfully."
  else
    echo "❌ Failed to apply patch cleanly! There are merge conflicts."
    echo "The patch was applied with conflicts. Please resolve them manually or abort."
    # intentional fail
    cat "force_exit" 2>/dev/null
  fi

  # Stage any changes introduced by the patch
  git add .

  # Check if there are actually any changes staged
  if git diff --staged --quiet; then
      echo "⚠️ Patch applied but resulted in no changes to master."
  else
      echo "🔨 Verifying compilation on rescued branch..."
      ./gradlew compileKotlin :tools:orchestrator:compileKotlin

      echo "✅ Compilation clean."
      echo ""

      git commit -m "chore(orchestrator): rescue PR #${PR_NUMBER} onto master via patch apply"

      echo "🚀 Pushing rescued branch '${BRANCH_NAME}' to origin..."
      # Use force-with-lease to protect against concurrent updates
      eval "git push --force-with-lease origin HEAD:${BRANCH_NAME}"

      echo ""
      echo "✅ Successfully rescued and integrated PR #${PR_NUMBER} ('${BRANCH_NAME}')!"
  fi
fi

rm -f "$PATCH_FILE"
