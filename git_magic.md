

# Correct algorithm if contaminated and legitimate changes are in separate commits

If your corrected invariant is:

1. legitimate changes exist entirely in Jules-authored non-merge commits;
2. synthetic/noing changes exist in separate orchestrator/Test User commits;
3. no Jules commit mixes legitimate work with contamination;
4. sync merge commits need not be preserved;
5. all Jules commits on the first-parent feature line are legitimate;

then the deterministic solution is to **cherry-pick the selected commits**, not copy files from the feature tip.

## 1. Fetch exact remote references

```bash
BRANCH=feature-branch

git fetch --prune origin \
  "+refs/heads/master:refs/remotes/origin/master" \
  "+refs/heads/$BRANCH:refs/remotes/origin/$BRANCH"

MASTER_SHA=$(git rev-parse refs/remotes/origin/master)
FEATURE_SHA=$(git rev-parse "refs/remotes/origin/$BRANCH")
```

## 2. Preserve the original tip

```bash
git update-ref \
  "refs/sanitizer-backups/$BRANCH/$FEATURE_SHA" \
  "$FEATURE_SHA"
```

## 3. Use a recorded base when available

```bash
D=$RECORDED_ORIGINAL_BASE_SHA
```

If no recorded base exists, you can fall back to:

```bash
D=$(git merge-base "$MASTER_SHA" "$FEATURE_SHA")
```

but recognize that it may be a recently merged master commit, not the original branch point.

## 4. Extract candidate commits from the feature’s first-parent line

A robust loop should inspect each commit explicitly:

```bash
mapfile -t FEATURE_LINE < <(
  git rev-list \
    --reverse \
    --first-parent \
    "$D..$FEATURE_SHA"
)
```

Classify the candidates:

```bash
AGENT_COMMITS=()

for COMMIT in "${FEATURE_LINE[@]}"; do
    PARENTS=$(git show -s --format='%P' "$COMMIT")
    AUTHOR_EMAIL=$(git show -s --format='%ae' "$COMMIT")

    read -r -a PARENT_ARRAY <<< "$PARENTS"

    # Skip all merge commits under the master-sync-only invariant.
    if (( ${#PARENT_ARRAY[@]} != 1 )); then
        continue
    fi

    if [[ "$AUTHOR_EMAIL" == "EXPECTED_EXACT_BOT_EMAIL" ]]; then
        AGENT_COMMITS+=("$COMMIT")
    fi
done
```

Replace `EXPECTED_EXACT_BOT_EMAIL` with the actual committed author email. Alternatively, classify orchestrator commits using trusted trailers or recorded SHAs.

## 5. Create the clean worktree

```bash
WORKTREE_DIR="build/tmp/sanitize-worktree-$$"

git worktree add --detach "$WORKTREE_DIR" "$MASTER_SHA"
cd "$WORKTREE_DIR"

git switch -c "sanitize/$BRANCH"
```

## 6. Replay all selected agent commits in order

```bash
for COMMIT in "${AGENT_COMMITS[@]}"; do
    if ! git cherry-pick "$COMMIT"; then
        git cherry-pick --abort
        echo "Sanitization failed while replaying $COMMIT" >&2
        exit 1
    fi
done
```

This preserves each legitimate commit’s delta without restoring complete stale files from the old branch tip.

### Why cherry-pick is preferable here

Suppose a legitimate commit changed one line from:

```text
old API
```

to:

```text
new API
```

The master version may now contain surrounding improvements. Cherry-pick applies the commit’s patch to current master, preserving unrelated master changes where possible.

By contrast:

```bash
git checkout old-feature-tip -- file
```

replaces the entire file with its stale feature-tip version.

### Conflicts

If cherry-pick conflicts, there is no universally safe pure-Git resolution. The sanitizer should:

- abort;
- mark the PR as needing intervention or rerun;
- avoid force-pushing anything.

Do not automatically use `-X ours` or `-X theirs`, because either can silently lose legitimate work.

## 7. Validate

```bash
git merge-base --is-ancestor "$MASTER_SHA" HEAD
git diff --check "$MASTER_SHA"...HEAD
git diff --stat "$MASTER_SHA"...HEAD
git diff "$MASTER_SHA"...HEAD
```

Run the build:

```bash
./gradlew compileKotlin :tools:orchestrator:compileKotlin
```

Also verify that no unexpected merge commits were introduced:

```bash
if git rev-list --merges "$MASTER_SHA..HEAD" | grep -q .; then
    echo "Unexpected merge commits in sanitized branch" >&2
    exit 1
fi
```

## 8. Push with an exact lease

```bash
git push \
  --force-with-lease="refs/heads/$BRANCH:$FEATURE_SHA" \
  origin \
  "HEAD:refs/heads/$BRANCH"
```

## 9. Remove the worktree

After leaving its directory:

```bash
cd -
git worktree remove --force "$WORKTREE_DIR"
```

---

# Important limitation

Even this corrected algorithm is reliable only if your commit classification invariant is true.

For example:

```text
C1: synthetic Test User contamination
C2: Jules legitimate task delta
C3: Jules legitimate follow-up
```

Cherry-picking `C2` and `C3` onto current master is appropriate.

But if the feature tip has:

```text
C1: Jules contamination
C2: Jules legitimate task delta
```

and both have the same author identity with no other metadata, author filtering cannot distinguish them.

Likewise, if Jules’s legitimate `C2` depends on code introduced by excluded contaminated `C1`, cherry-picking `C2` may conflict or fail to build. That failure should stop the process rather than trigger a guess.

---

# Recommended document corrections

The proposed Step 4 should be removed entirely:

```bash
CHANGED_FILES=...
git checkout origin/feature-branch -- "$FILE"
```

Replace it with:

```bash
for COMMIT in "${AGENT_COMMITS[@]}"; do
    git cherry-pick "$COMMIT" || {
        git cherry-pick --abort
        exit 1
    }
done
```

And update the design claim from:

> identify genuine changes through differential file extraction

to:

> rebuild from current master by replaying commits classified as legitimate using first-parent topology and trusted commit provenance.

## Final verdict

- **Fresh-master reconstruction:** correct.
- **First-parent extraction:** correct under controlled merge topology.
- **Exact force-with-lease:** correct for protecting the final remote update.
- **File checkout from feature tip:** incorrect and contamination-preserving.
- **Claim that agent commits are replayed:** not implemented.
- **Correct replacement:** cherry-pick each separately identified legitimate commit onto fresh master, fail safely on conflicts, validate, then push with an exact lease.