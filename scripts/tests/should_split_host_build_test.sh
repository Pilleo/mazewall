#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
pred=$root/scripts/should_split_host_build.sh
app=/tmp/mazewall-checkout
fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }
expect() {
  name=$1
  want=$2
  shift 2
  if sh "$pred" "$@"; then got=0; else got=$?; fi
  [ "$got" = "$want" ] || fail "$name expected $want got $got"
}
expect 'bare build' 0 "$app" "$app" build
expect 'flag then build' 0 "$app" "$app" --offline build
expect 'build then flag' 0 "$app" "$app" build --offline
expect 'module build' 1 "$app" "$app" :enforcer:build
expect 'build prefix' 1 "$app" "$app" buildEnvironment
expect 'help' 1 "$app" "$app" --help build
expect 'explicit lane' 1 "$app" "$app" build -PmazewallKernelLane=ALL
expect 'other directory' 1 "$app" /tmp build
if ORG_GRADLE_PROJECT_mazewallKernelLane=ALL sh "$pred" "$app" "$app" build; then
  fail 'environment lane should not split'
fi
echo PASS
