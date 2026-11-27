#!/bin/sh
# Exit 0 when this gradlew invocation should use scripts/run_local_build.sh.
# Usage: should_split_host_build.sh <app-home> <cwd> [gradle-args...]
set -eu
app_home=$1
cwd=$2
shift 2
if [ "$cwd" != "$app_home" ] || [ ! -r /proc/self/status ] || [ -n "${ORG_GRADLE_PROJECT_mazewallKernelLane:-}" ]; then
  exit 1
fi
wants_build=false
for arg do
  case "$arg" in
    -PmazewallKernelLane*|-Dorg.gradle.project.mazewallKernelLane*|--project-prop*|-P|\
    --dry-run|-m|--help|-h|-\?|--plan|--project-dir*|-p*)
      exit 1
      ;;
    build)
      wants_build=true
      ;;
  esac
done
[ "$wants_build" = true ]
