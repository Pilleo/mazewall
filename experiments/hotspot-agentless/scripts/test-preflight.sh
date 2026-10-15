#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
docker_cmd=("${DOCKER:-docker}")
if [[ -n "${COLLECTOR_DOCKER_HOST:-}" ]]; then
    docker_cmd+=(--host "$COLLECTOR_DOCKER_HOST")
fi
go_image=$("${GO:-go}" run ./cmd/mw-collector-tools image --key go)
jdk_image=$("${GO:-go}" run ./cmd/mw-collector-tools image --key jdk)
target=''
cleanup() {
    if [[ -n "$target" ]]; then
        "${docker_cmd[@]}" rm -f "$target" >/dev/null
    fi
}
trap cleanup EXIT
mkdir -p build
target=$("${docker_cmd[@]}" run -d --user 1000:1000 --pid=host \
    --mount "type=bind,src=$root,dst=/work,readonly" \
    "$jdk_image" java /work/tests/fixtures/PreflightTarget.java)
pid=$("${docker_cmd[@]}" inspect --format '{{.State.Pid}}' "$target")
ready=false
for ((attempt=0; attempt<100; attempt++)); do
    if "${docker_cmd[@]}" logs "$target" 2>&1 | rg -q '^ready [0-9]+$'; then
        ready=true
        break
    fi
    sleep 0.1
done
if [[ "$ready" != true ]]; then
    "${docker_cmd[@]}" logs "$target" >&2
    printf '%s\n' 'Ordinary-user HotSpot fixture did not become ready' >&2
    exit 1
fi
"${docker_cmd[@]}" run --rm --privileged --pid=host \
    --mount type=bind,src=/sys,dst=/sys,readonly \
    --mount "type=bind,src=$root,dst=/work,readonly" \
    --entrypoint /work/build/mw-hotspot-demo "$go_image" \
    --preflight --pid "$pid" > build/preflight-supported.json
if "${docker_cmd[@]}" run --rm --privileged --pid=host \
    --mount type=bind,src=/sys,dst=/sys,readonly \
    --mount "type=bind,src=$root,dst=/work,readonly" \
    --entrypoint /work/build/mw-hotspot-demo "$go_image" \
    --preflight --pid 1 > build/preflight-root-refused.json; then
    printf '%s\n' 'Preflight incorrectly accepted a privileged target' >&2
    exit 1
fi
if bash scripts/preflight.sh --pid "$pid" > build/preflight-unprivileged.json; then
    printf '%s\n' 'Preflight unexpectedly loaded BPF without collector privileges' >&2
    exit 1
fi
"${GO:-go}" run -modfile build/experiment.go.mod ./cmd/mw-experiment-tools preflight-verdict build
printf '%s\n' 'Preflight: ordinary-user HotSpot accepted; root target and unprivileged collector refused.'
