#!/usr/bin/env bash
# Run under the fleet verification.lock when used in a shared verification fleet.
# Removing the full build gate or Gradle isolation must fail this real-Git regression.
# Clearing Git-local context before audits must fail the alternate-index case.
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
HOOK="$SCRIPT_DIR/../hooks/pre-commit"
CONFINED_BUILD_LANE="$SCRIPT_DIR/../confined_build_lane.sh"
# The test itself may be invoked from a hook. Never let that context reach fixtures.
GIT_LOCAL_NAMES=$(git rev-parse --local-env-vars)
for name in $GIT_LOCAL_NAMES; do
  unset "$name"
done

FIXTURE=$(mktemp -d /tmp/mazewall-git-hook-environment.XXXXXX)
cleanup() {
  local status=$?
  if (( status == 0 )); then
    rm -rf -- "$FIXTURE"
  else
    echo "FAIL: disposable fixture retained at $FIXTURE" >&2
  fi
}
trap cleanup EXIT

# Per-command global identity/config; never write operator or real repo config.
cat > "$FIXTURE/global.gitconfig" <<'CONFIG'
[user]
    name = Hook Fixture
    email = hook-fixture@example.invalid
[commit]
    gpgsign = false
[core]
    hooksPath = /dev/null
CONFIG
export GIT_CONFIG_GLOBAL="$FIXTURE/global.gitconfig"
export GIT_CONFIG_NOSYSTEM=1
export HOOK_ENV_SENTINEL=preserve-nonlocal-environment

fail() { echo "FAIL: $*" >&2; exit 1; }

run_case() {
  local label=$1 portal=$2 gradle_status=$3 audit=$4
  local case_dir="$FIXTURE/$label" status
  local default_index alternate_index=''
  local -a commit_env=()
  mkdir -p "$case_dir/hooks"
  git init -q --initial-branch=main "$case_dir/original"
  printf 'baseline\n' > "$case_dir/original/baseline.txt"
  git -C "$case_dir/original" add baseline.txt
  git -C "$case_dir/original" commit -q -m baseline
  git -C "$case_dir/original" worktree add -q -b hook-task "$case_dir/worktree"
  mkdir -p "$case_dir/worktree/scripts"
  cp "$CONFINED_BUILD_LANE" "$case_dir/worktree/scripts/"
  git init -q --initial-branch=main "$case_dir/foreign"
  printf 'foreign baseline\n' > "$case_dir/foreign/baseline.txt"
  git -C "$case_dir/foreign" add baseline.txt
  git -C "$case_dir/foreign" commit -q -m baseline

  cp "$HOOK" "$case_dir/hooks/actual-pre-commit"
  chmod +x "$case_dir/hooks/actual-pre-commit"
  cat > "$case_dir/hooks/pre-commit" <<'WRAPPER'
#!/usr/bin/env bash
set -euo pipefail
snapshot() {
  git rev-parse HEAD
  git for-each-ref --format='%(refname) %(objectname)'
  git ls-files --stage
  sha256sum "$(git rev-parse --git-path index)"
  git config --local --list
  if [[ -n $ALTERNATE_INDEX ]]; then
    test "$GIT_INDEX_FILE" = "$ALTERNATE_INDEX"
    GIT_INDEX_FILE="$DEFAULT_INDEX" git ls-files --stage
    sha256sum "$DEFAULT_INDEX"
  fi
}
# These are genuinely exported by Git for a linked-worktree commit, not simulated.
test -n "${GIT_DIR:-}" && test -n "${GIT_INDEX_FILE:-}"
git rev-parse --local-env-vars > "$CASE_DIR/local-names"
for name in $(cat "$CASE_DIR/local-names"); do
  if [[ -v $name ]]; then printf '%s\n' "$name"; fi
done > "$CASE_DIR/hook-exported-names"
snapshot > "$CASE_DIR/before"
set +e
"$CASE_DIR/hooks/actual-pre-commit" > "$CASE_DIR/hook.log" 2>&1
status=$?
set -e
printf '%s\n' "$status" > "$CASE_DIR/hook-status"
snapshot > "$CASE_DIR/after"
if ! diff -u "$CASE_DIR/before" "$CASE_DIR/after"; then
  echo 'FAIL: Gradle foreign Git operations mutated original HEAD/refs/index/config' >&2
  exit 92
fi
exit "$status"
WRAPPER
  chmod +x "$case_dir/hooks/pre-commit"

  cat > "$case_dir/worktree/gradlew" <<'GRADLE'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$@" > "$CASE_DIR/gradle-args"
test "${HOOK_ENV_SENTINEL:-}" = preserve-nonlocal-environment
# Capture names only, never operator environment values.
for name in $(cat "$CASE_DIR/local-names"); do
  if [[ -v $name ]]; then printf '%s\n' "$name"; fi
done > "$CASE_DIR/gradle-local-names"
# Deliberately use ordinary foreign-repo Git operations without sanitizing here.
# An unfixed actual hook redirects these to the disposable original repository.
git -C "$FOREIGN_REPO" config --local user.name 'Foreign Fixture'
git -C "$FOREIGN_REPO" config --local user.email foreign-fixture@example.invalid
printf 'foreign payload\n' > "$FOREIGN_REPO/foreign.txt"
git -C "$FOREIGN_REPO" add foreign.txt
git -C "$FOREIGN_REPO" -c core.hooksPath=/dev/null commit -q -m foreign-operation
git -C "$FOREIGN_REPO" update-ref refs/fixture/foreign HEAD
git -C "$FOREIGN_REPO" config --local core.bare true
printf 'foreign operations completed\n' > "$CASE_DIR/foreign-done"
if (( GRADLE_STATUS != 0 )); then echo 'fixture verification FAILED requested Gradle failure'; fi
exit "$GRADLE_STATUS"
GRADLE
  chmod +x "$case_dir/worktree/gradlew"
  if [[ $portal == included ]]; then
    printf 'include(":portals:runtime")\n' > "$case_dir/worktree/settings.gradle.kts"
  else
    printf '// runtime intentionally excluded in fixture\n' > "$case_dir/worktree/settings.gradle.kts"
  fi
  if [[ $audit == reject ]]; then
    printf 'val unsafe = sun.misc.Unsafe\n' > "$case_dir/worktree/Rejected.kt"
  elif [[ $audit == alternate-reject ]]; then
    printf 'val safe = 1\n' > "$case_dir/worktree/Rejected.kt"
  else
    printf 'val safe = 1\n' > "$case_dir/worktree/Safe.kt"
  fi
  git -C "$case_dir/worktree" add .
  default_index=$(git -C "$case_dir/worktree" rev-parse --path-format=absolute --git-path index)
  if [[ $audit == alternate-reject ]]; then
    alternate_index="$case_dir/alternate.index"
    cp "$default_index" "$alternate_index"
    printf 'val unsafe = sun.misc.Unsafe\n' > "$case_dir/worktree/Rejected.kt"
    GIT_INDEX_FILE="$alternate_index" git -C "$case_dir/worktree" add Rejected.kt
    # Keep the worktree/default index safe; only the explicit index is forbidden.
    printf 'val safe = 1\n' > "$case_dir/worktree/Rejected.kt"
    [[ $(git -C "$case_dir/worktree" show :Rejected.kt) == 'val safe = 1' ]] || fail 'default index is not safe'
    [[ $(GIT_INDEX_FILE="$alternate_index" git -C "$case_dir/worktree" show :Rejected.kt) == 'val unsafe = sun.misc.Unsafe' ]] || fail 'alternate index lacks forbidden staged content'
    # Prime Git's cache-tree extension before the byte-preservation snapshot;
    # otherwise commit itself writes this metadata before invoking pre-commit.
    GIT_INDEX_FILE="$alternate_index" git -C "$case_dir/worktree" write-tree > /dev/null
    sha256sum "$default_index" "$alternate_index" > "$case_dir/indexes-before-commit"
    commit_env+=("GIT_INDEX_FILE=$alternate_index")
  fi
  git -C "$case_dir/worktree" rev-parse HEAD > "$case_dir/original-head"
  export CASE_DIR="$case_dir" FOREIGN_REPO="$case_dir/foreign" GRADLE_STATUS="$gradle_status"
  export DEFAULT_INDEX="$default_index" ALTERNATE_INDEX="$alternate_index"
  set +e
  env "${commit_env[@]}" git -C "$case_dir/worktree" -c core.hooksPath="$case_dir/hooks" commit -m "$label" > "$case_dir/commit.log" 2>&1
  status=$?
  set -e
  if ! cmp -s "$case_dir/before" "$case_dir/after"; then
    cat "$case_dir/commit.log" >&2
    fail "$label: original repository changed during hook"
  fi
  grep -qx GIT_DIR "$case_dir/hook-exported-names" || fail 'missing real Git worktree context'
  grep -qx GIT_INDEX_FILE "$case_dir/hook-exported-names" || fail 'missing real Git index context'

  if [[ $audit == reject || $audit == alternate-reject ]]; then
    [[ $status == 1 && $(cat "$case_dir/hook-status") == 1 ]] || fail 'audit rejection did not abort commit'
    grep -q 'ERROR: Security violation in Rejected.kt' "$case_dir/hook.log" || fail 'audit lost staged Git context'
    [[ ! -e $case_dir/gradle-args ]] || fail 'Gradle ran despite audit rejection'
    [[ $(git -C "$case_dir/worktree" rev-parse HEAD) == "$(cat "$case_dir/original-head")" ]] || fail 'audit rejection advanced original HEAD'
    if [[ $audit == alternate-reject ]]; then
      sha256sum "$default_index" "$alternate_index" > "$case_dir/indexes-after-commit"
      diff -u "$case_dir/indexes-before-commit" "$case_dir/indexes-after-commit" || fail 'audit rejection changed default or alternate index'
      echo "PASS: $label — explicit alternate-index audit rejects before Gradle; both indexes and original state intact"
      return
    fi
    echo "PASS: $label — staged audit rejects before Gradle; original state intact"
    return
  fi

  [[ -f $case_dir/foreign-done ]] || fail 'foreign operations did not complete'
  [[ ! -s $case_dir/gradle-local-names ]] || fail 'Git-local variables reached Gradle'
  [[ $(git --git-dir="$case_dir/foreign/.git" show HEAD:foreign.txt) == 'foreign payload' ]] || fail 'foreign commit missing'
  [[ $(git --git-dir="$case_dir/foreign/.git" rev-parse refs/fixture/foreign) == "$(git --git-dir="$case_dir/foreign/.git" rev-parse HEAD)" ]] || fail 'foreign ref missing'
  [[ $(git --git-dir="$case_dir/foreign/.git" config --local user.name) == 'Foreign Fixture' ]] || fail 'foreign identity not updated'
  [[ $(git --git-dir="$case_dir/foreign/.git" config --local core.bare) == true ]] || fail 'foreign config not updated'
  printf 'build\n' > "$case_dir/expected-args"
  if [[ $portal == included ]]; then
    printf ':portals:runtime:integrationTest\n:portals:runtime:test\n' >> "$case_dir/expected-args"
  else
    grep -q 'GATE DEGRADED' "$case_dir/hook.log" || fail 'excluded portal gate was silent'
  fi
  printf '%s\n' --build-cache --configuration-cache --no-parallel >> "$case_dir/expected-args"
  diff -u "$case_dir/expected-args" "$case_dir/gradle-args" || fail 'gate argument selection changed'
  if (( gradle_status == 0 )); then
    [[ $status == 0 && $(cat "$case_dir/hook-status") == 0 ]] || fail 'successful gate aborted commit'
    [[ $(git -C "$case_dir/worktree" rev-parse HEAD^) == "$(cat "$case_dir/original-head")" ]] || fail 'original commit parent changed'
    [[ $(git -C "$case_dir/worktree" status --porcelain) == '' ]] || fail 'original commit index not clean'
  else
    [[ $status == 1 && $(cat "$case_dir/hook-status") == 1 ]] || fail 'Gradle failure did not abort commit with existing hook status 1'
    grep -q 'ERROR: Full Gradle build failed. Commit aborted.' "$case_dir/hook.log" || fail 'Gradle verification failure diagnostic missing'
    [[ $(git -C "$case_dir/worktree" rev-parse HEAD) == "$(cat "$case_dir/original-head")" ]] || fail 'failed gate advanced original HEAD'
  fi
  echo "PASS: $label — real worktree exports isolated; foreign Git only; gate arguments/status preserved"
}

run_confined_case() {
  local label=$1 mode=$2 runner_status=$3
  local case_dir="$FIXTURE/$label" status
  mkdir -p "$case_dir/hooks" "$case_dir/repo/scripts" "$case_dir/repo/infra/dev" "$case_dir/repo/gradle/wrapper" "$case_dir/bin"
  cp "$CONFINED_BUILD_LANE" "$case_dir/repo/scripts/"
  git init -q --initial-branch=main "$case_dir/repo"
  printf 'baseline\n' > "$case_dir/repo/baseline.txt"
  printf 'include(":enforcer")\ninclude(":portals:runtime")\n' > "$case_dir/repo/settings.gradle.kts"
  printf 'distributionUrl=https://example.invalid/gradle.zip\n' > "$case_dir/repo/gradle/wrapper/gradle-wrapper.properties"
  printf 'FROM scratch\n' > "$case_dir/repo/infra/dev/Containerfile"
  cat > "$case_dir/repo/gradlew" <<'GRADLE'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$@" > "$CASE_DIR/gradle-args"
exit 0
GRADLE
  chmod +x "$case_dir/repo/gradlew"

  if [[ $mode == docker ]]; then
    local csha
    csha=$(sha256sum "$case_dir/repo/infra/dev/Containerfile" | cut -d' ' -f1)
    cat > "$case_dir/bin/docker" <<DOCKER
#!/usr/bin/env bash
set -euo pipefail
case "\$*" in
  *" info "*) printf '["name=seccomp,profile=builtin"]\n' ;;
  *"io.mazewall.containerfile.sha256"*) printf '%s\n' "$csha" ;;
  *"{{.Id}}"*) printf 'sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n' ;;
  *) echo "unexpected docker invocation: \$*" >&2; exit 99 ;;
esac
DOCKER
    chmod +x "$case_dir/bin/docker"
    cat > "$case_dir/repo/scripts/run_confined_helper_tests.sh" <<'RUNNER'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$@" > "$CASE_DIR/confined-args"
for name in $(git rev-parse --local-env-vars); do
  if [[ -v $name ]]; then printf '%s\n' "$name"; fi
done > "$CASE_DIR/confined-local-names"
if (( RUNNER_STATUS != 0 )); then echo 'mock confined runner failure'; fi
exit "$RUNNER_STATUS"
RUNNER
    chmod +x "$case_dir/repo/scripts/run_confined_helper_tests.sh"
  fi

  git -C "$case_dir/repo" add .
  git -C "$case_dir/repo" commit -q -m baseline
  cp "$HOOK" "$case_dir/hooks/pre-commit"
  chmod +x "$case_dir/hooks/pre-commit"
  printf 'val safe = 2\n' > "$case_dir/repo/Safe.kt"
  git -C "$case_dir/repo" add Safe.kt
  git -C "$case_dir/repo" rev-parse HEAD > "$case_dir/original-head"

  export CASE_DIR="$case_dir" RUNNER_STATUS="$runner_status"
  local test_path="$PATH"
  if [[ $mode == docker ]]; then
    test_path="$case_dir/bin:$PATH"
  fi
  set +e
  PATH="$test_path" XDG_CACHE_HOME="$case_dir/cache" \
    git -C "$case_dir/repo" -c core.hooksPath="$case_dir/hooks" commit -m "$label" > "$case_dir/commit.log" 2>&1
  status=$?
  set -e

  printf '%s\n' build :portals:runtime:integrationTest :portals:runtime:test -PmazewallKernelLane=BASELINE --build-cache --configuration-cache --no-parallel > "$case_dir/expected-args"
  diff -u "$case_dir/expected-args" "$case_dir/gradle-args" || fail "$label: expected BASELINE gradle args"

  if [[ $mode == unavailable ]]; then
    [[ $status == 0 ]] || fail "$label: commit should succeed when confined lane is unavailable"
    grep -q 'GATE DEGRADED: Skipping CONFINED kernel lane' "$case_dir/commit.log" || fail "$label: missing explicit GATE DEGRADED notice"
    echo "PASS: $label — BASELINE host build + explicit GATE DEGRADED skip when confined lane unavailable"
    return
  fi

  [[ -f $case_dir/confined-args ]] || fail "$label: confined runner was not invoked"
  [[ ! -s $case_dir/confined-local-names ]] || fail "$label: Git-local variables reached confined runner"
  grep -qx -- '--image' "$case_dir/confined-args" || fail "$label: missing --image flag"
  grep -qx -- '--gradle-home' "$case_dir/confined-args" || fail "$label: missing --gradle-home flag"
  if (( runner_status == 0 )); then
    [[ $status == 0 ]] || fail "$label: successful confined lane aborted commit"
    [[ $(git -C "$case_dir/repo" rev-parse HEAD^) == "$(cat "$case_dir/original-head")" ]] || fail "$label: commit did not advance HEAD"
    echo "PASS: $label — BASELINE host build + CONFINED container lane succeeded with sanitized Git env"
  else
    [[ $status == 1 ]] || fail "$label: failed confined lane did not abort commit"
    grep -q 'ERROR: CONFINED kernel lane failed. Commit aborted.' "$case_dir/commit.log" || fail "$label: missing confined lane failure diagnostic"
    [[ $(git -C "$case_dir/repo" rev-parse HEAD) == "$(cat "$case_dir/original-head")" ]] || fail "$label: failed confined lane advanced HEAD"
    echo "PASS: $label — failed CONFINED container lane aborted commit cleanly"
  fi
}

run_case portal-success included 0 allow
run_case build-verification-failure included 23 allow
run_case excluded-portal excluded 0 allow
run_case staged-audit included 0 reject
run_case alternate-index-audit included 0 alternate-reject
cap_eff=$(awk '/^CapEff:/ {print $2}' /proc/self/status 2>/dev/null || true)
if [[ -n "$cap_eff" ]] && (( (16#$cap_eff & (1 << 40)) == 0 )); then
  run_confined_case enforcer-confined-unavailable unavailable 0
  if [[ -S /var/run/docker.sock && "$(id -u)" -ne 0 ]] && [[ "$(cat /proc/self/uid_map 2>/dev/null || true)" =~ ^[[:space:]]*0[[:space:]]+0[[:space:]]+4294967295[[:space:]]*$ ]]; then
    run_confined_case enforcer-confined-docker-pass docker 0
    run_confined_case enforcer-confined-docker-fail docker 1
  fi
fi
echo 'PASS: Git hook environment regression'
