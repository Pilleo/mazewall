#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
make deps
artifact=$(mktemp -d "$root/build/identity-boundary.XXXXXX")
provider=$(go list -modfile build/experiment.go.mod -m -f '{{.Dir}}' go.opentelemetry.io/ebpf-profiler)
cp -R "$provider" "$artifact/provider"
cp tests/fixtures/InterpretedIdentityCollisionProbe.go.txt "$artifact/provider/interpreter/hotspot/identity_collision_probe_test.go"
cp build/experiment.go.mod "$artifact/probe.go.mod"
cp build/experiment.go.sum "$artifact/probe.go.sum"
go mod edit -modfile "$artifact/probe.go.mod" \
    -replace "go.opentelemetry.io/ebpf-profiler=$artifact/provider"
set +e
go test -modfile "$artifact/probe.go.mod" go.opentelemetry.io/ebpf-profiler/interpreter/hotspot \
    -run TestFullInterpreterGenerationCannotBeProvedByTruncatedCookie -count=1 \
    > "$artifact/probe.log" 2>&1
status=$?
set -e
cat "$artifact/probe.log"
printf 'Identity-boundary diagnostic artifacts: %s\n' "$artifact"
exit "$status"
