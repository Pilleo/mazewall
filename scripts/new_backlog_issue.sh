#!/usr/bin/env bash
# Scaffold a backlog issue that already passes :tools:orchestrator:checkBacklog.
# Unique id is issue-YYYYMMDD-HHMMSS from UTC (seconds bumped on collision).
# --symbol walks Kotlin sources for the definition and matching *Test.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

ARGS_FILE="$(mktemp)"
cleanup() { rm -f "$ARGS_FILE"; }
trap cleanup EXIT

if [[ $# -eq 0 ]]; then
  printf '%s\n' --help > "$ARGS_FILE"
else
  for arg in "$@"; do
    printf '%s\n' "$arg" >> "$ARGS_FILE"
  done
  wants_interactive=0
  wants_non_interactive=0
  for arg in "$@"; do
    case "$arg" in
      --interactive) wants_interactive=1 ;;
      --non-interactive) wants_non_interactive=1 ;;
    esac
  done
  # Humans on a TTY get prompts; agents (no TTY, or explicit flag) do not.
  if [[ $wants_interactive -eq 0 && $wants_non_interactive -eq 0 && -t 0 && -t 1 ]]; then
    printf '%s\n' --interactive >> "$ARGS_FILE"
  fi
fi

exec ./gradlew -q :tools:orchestrator:newBacklogIssue -PissueArgsFile="$ARGS_FILE"
