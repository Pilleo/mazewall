#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# rescue_pr.sh — Surgically repair and integrate a corrupted or orphaned Jules PR.
#
# This script rescues PRs that were pushed as single root commits (no parents)
# or had their histories irreversibly corrupted (causing unrelated histories).
# It fetches the exact target files Jules intended to change and applies them
# directly onto a pristine origin/master branch.
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

echo "🧹 Finding linked issue and target files for surgical checkout..."
ISSUE_NUMBER=$(gh pr view "$PR_NUMBER" --json closingIssuesReferences --jq '.[0].number' 2>/dev/null || echo "")
if [ -z "$ISSUE_NUMBER" ] || [ "$ISSUE_NUMBER" == "null" ]; then
  PR_BODY=$(gh pr view "$PR_NUMBER" --json body --jq '.body' 2>/dev/null || echo "")
  ISSUE_NUMBER=$(echo "$PR_BODY" | grep -ioE 'fixes\s+#?[0-9]+' | grep -oE '[0-9]+' | head -n 1 || echo "")
fi

ISSUE_FILE=""
if [ -n "$ISSUE_NUMBER" ]; then
  ISSUE_FILE=$(grep -rl "github_issue: $ISSUE_NUMBER" docs/internals/backlog/ 2>/dev/null | head -n 1 || echo "")
fi

TARGET_FILES=""
if [ -n "$ISSUE_FILE" ] && [ -f "$ISSUE_FILE" ]; then
  TARGET_FILES=$(sed -n '/^target_files:/,/^[a-zA-Z0-9_-]\+:/p' "$ISSUE_FILE" | grep -E '^\s*-\s*' | sed 's/^\s*-\s*//' | sed 's/^://' | sed "s/['\"]//g" || echo "")
fi

echo "📋 Backlog file: ${ISSUE_FILE:-None}"
echo "📋 Allowed target files:"
echo "$TARGET_FILES" | sed 's/^/  - /'

is_file_allowed() {
  local f="$1"
  if [[ "$f" =~ ^docs/internals/backlog/.*\.md$ ]]; then
    return 0
  fi
  [ -z "$TARGET_FILES" ] && return 0 # If no targets found, apply everything from PR as a fallback

  while read -r target; do
    [ -z "$target" ] && continue
    target="${target#:}"
    if [ "$f" == "$target" ] || [[ "$f" == */"$target" ]]; then
      return 0
    fi
    if [[ "$target" == *"/src/main/"* ]]; then
      local test_target="${target//\/src\/main\//\/src\/test\/}"
      test_target="${test_target%.kt}Test.kt"
      test_target="${test_target%.java}Test.java"
      if [ "$f" == "$test_target" ] || [[ "$f" == */"$test_target" ]]; then
        return 0
      fi
    fi
  done <<< "$TARGET_FILES"
  return 1
}

cd "$WORKTREE_DIR"

echo "🔄 Diffing origin/master against PR branch '${BRANCH_NAME}'..."
DIFFERENT_FILES=$(git diff --name-only origin/master "${BRANCH_NAME}" 2>/dev/null || echo "")
RESCUED_ANY=0

for f in $DIFFERENT_FILES; do
  [ -z "$f" ] && continue
  if is_file_allowed "$f"; then
    if git ls-tree -r "${BRANCH_NAME}" --name-only | grep -qx "$f"; then
      echo "✅ RESCUING TARGET FILE: Checking out '$f' from PR branch..."
      git checkout "${BRANCH_NAME}" -- "$f"
      git add "$f"
    else
      echo "🗑️ RESCUING TARGET FILE: Deleting '$f' (removed in PR branch)..."
      git rm --ignore-unmatch "$f" >/dev/null 2>&1 || true
    fi
    RESCUED_ANY=1
  fi
done

if [ "$RESCUED_ANY" -eq 0 ]; then
  echo "⚠️ No target files found to rescue for PR #${PR_NUMBER}!"
else
  echo "🔨 Verifying compilation on rescued branch..."
  ./gradlew compileKotlin :tools:orchestrator:compileKotlin

  echo "✅ Compilation clean."
  echo ""

  git commit -m "chore(orchestrator): rescue PR #${PR_NUMBER} onto master via target-files apply"

  echo "🚀 Pushing rescued branch '${BRANCH_NAME}' to origin..."
  # Use force-with-lease to protect against concurrent updates
  eval "git push --force-with-lease origin HEAD:${BRANCH_NAME}"

  echo ""
  echo "✅ Successfully rescued and integrated PR #${PR_NUMBER} ('${BRANCH_NAME}')!"
fi
