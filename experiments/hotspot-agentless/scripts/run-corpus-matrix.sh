#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
matrix=$(mktemp -d "$root/build/matrix.XXXXXX")
for mode in c2 tiered interpreter; do
    for workers in 1 4 16; do
        run=$("${GO:-go}" run -modfile build/experiment.go.mod ./cmd/mw-experiment-tools token)
        MW_CORPUS_MODE="$mode" MW_CORPUS_WORKERS="$workers" MW_CORPUS_RUN="$run" \
            bash scripts/run-demo.sh > "$matrix/$mode-$workers.log" 2>&1
        capture=$(rg '^Capture artifacts: ' "$matrix/$mode-$workers.log")
        capture=${capture#Capture artifacts: }
        printf '%s,%s,%s\n' "$mode" "$workers" "$capture" >> "$matrix/runs.csv"
    done
done
"${GO:-go}" run -modfile build/experiment.go.mod ./cmd/mw-experiment-tools matrix-report "$matrix"
printf 'Corpus matrix artifacts: %s\n' "$matrix"
