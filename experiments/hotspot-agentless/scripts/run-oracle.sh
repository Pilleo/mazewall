#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
artifact=$(mktemp -d "$root/build/oracle.XXXXXX")
MW_CORPUS_MODE=c2 MW_CORPUS_WORKERS=1 \
MW_CORPUS_RUN=$(python3 -c 'import secrets;print(secrets.token_hex(8))') \
    bash scripts/run-demo.sh > "$artifact/candidate.log" 2>&1
candidate=$(rg '^Capture artifacts: ' "$artifact/candidate.log")
candidate=${candidate#Capture artifacts: }
bash scripts/run-reference.sh > "$artifact/reference.log" 2>&1
reference=$(rg '^Independent reference artifacts: ' "$artifact/reference.log")
reference=${reference#Independent reference artifacts: }
go run -modfile build/experiment.go.mod ./cmd/mw-reference-compare \
    "$candidate/inline-report.json" "$reference/reference-report.json" "$artifact/differential-report.json"
printf '%s\n%s\n' "$candidate" "$reference" > "$artifact/runs.txt"
printf 'Independent oracle comparison artifacts: %s\n' "$artifact"
