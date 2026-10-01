#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
docker_cmd=("${DOCKER:-docker}")
if [[ -n "${COLLECTOR_DOCKER_HOST:-}" ]]; then
    docker_cmd+=(--host "$COLLECTOR_DOCKER_HOST")
fi
go_image=$(python3 -c 'import json; print(json.load(open("dependencies.lock.json"))["images"]["go"])')
jdk_image=$(python3 -c 'import json; print(json.load(open("dependencies.lock.json"))["images"]["jdk"])')
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
python3 - <<'PY'
import json
from pathlib import Path
valid = json.loads(Path('build/preflight-supported.json').read_text())
assert valid['ready_for_attach_test'] and not valid['failures'], valid
assert valid['environment']['target_uid'] == 1000, valid
root = json.loads(Path('build/preflight-root-refused.json').read_text())
assert not root['ready_for_attach_test'] and 'privileged_target' in root['failures'], root
unprivileged = json.loads(Path('build/preflight-unprivileged.json').read_text())
assert not unprivileged['ready_for_attach_test'] and 'bpf_permission_denied' in unprivileged['failures'], unprivileged
print('Preflight: ordinary-user HotSpot accepted; root target and unprivileged collector refused.')
PY
