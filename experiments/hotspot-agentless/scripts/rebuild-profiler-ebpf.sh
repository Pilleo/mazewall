#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)
exec bash "$root/profiler/hotspot-agentless/scripts/rebuild-profiler-ebpf.sh" "$@"
