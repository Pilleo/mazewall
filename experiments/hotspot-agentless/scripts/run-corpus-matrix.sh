#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
matrix=$(mktemp -d "$root/build/matrix.XXXXXX")
for mode in c2 tiered interpreter; do
    for workers in 1 4 16; do
        run=$(python3 -c 'import secrets; print(secrets.token_hex(8))')
        MW_CORPUS_MODE="$mode" MW_CORPUS_WORKERS="$workers" MW_CORPUS_RUN="$run" \
            bash scripts/run-demo.sh > "$matrix/$mode-$workers.log" 2>&1
        capture=$(rg '^Capture artifacts: ' "$matrix/$mode-$workers.log")
        capture=${capture#Capture artifacts: }
        printf '%s,%s,%s\n' "$mode" "$workers" "$capture" >> "$matrix/runs.csv"
    done
done
python3 - "$matrix" <<'PY'
import csv, json, pathlib, sys
directory=pathlib.Path(sys.argv[1])
rows=[]
for mode,workers,artifact in csv.reader((directory/"runs.csv").open()):
    report=json.loads((pathlib.Path(artifact)/"inline-report.json").read_text())
    rows.append({"mode":mode,"workers":int(workers),"artifact":artifact,"report":report})
(directory/"matrix-report.json").write_text(json.dumps({"schema_version":1,"runs":rows},indent=2)+"\n")
print("Corpus matrix artifacts: "+str(directory))
PY
