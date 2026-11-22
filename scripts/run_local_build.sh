#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$root"
source "$root/scripts/confined_build_lane.sh"

if has_cap_checkpoint_restore || ! grep -Eq '^[[:space:]]*include\(":enforcer"\)' settings.gradle.kts; then
  exec ./gradlew "$@" -PmazewallKernelLane=ALL
fi

if blocker=$(confined_lane_blocker "$root"); then
  printf 'Full build requires the CONFINED kernel lane, but it is unavailable: %s\n' "$blocker" >&2
  printf 'To run only the host portion explicitly: ./gradlew build -PmazewallKernelLane=BASELINE\n' >&2
  exit 1
fi

# Both lanes write the enforcer test results. Share the hook's lock and keep
# both runs under one acquisition so they cannot race with another build gate.
exec 9>/tmp/mazewall-precommit-test.lock
flock -w 900 9
echo '==> Running the BASELINE build on the host...'
./gradlew "$@" -PmazewallKernelLane=BASELINE --no-parallel
echo '==> Running the CONFINED kernel lane in rootful Docker...'
run_confined_build_lane "$root"
