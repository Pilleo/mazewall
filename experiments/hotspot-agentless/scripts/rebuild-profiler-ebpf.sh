#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 2 ]; then
  printf 'Usage: %s <provider_dir> <absolute_deadline_unix_sec>\n' "$0" >&2
  exit 1
fi

provider_dir="$1"
deadline_raw="$2"

if [ -z "${provider_dir}" ] || [ ! -d "${provider_dir}" ]; then
  printf 'INCOMPLETE(invalid provider directory: %q); no eligible preparation\n' "${provider_dir}" >&2
  exit 1
fi

if ! [[ "${deadline_raw}" =~ ^[1-9][0-9]*$ ]]; then
  printf 'INCOMPLETE(invalid or missing absolute deadline: %q); no eligible preparation\n' "${deadline_raw}" >&2
  exit 1
fi

if [ "${#deadline_raw}" -gt 19 ] || { [ "${#deadline_raw}" -eq 19 ] && [[ "${deadline_raw}" > "9223372036854775807" ]]; }; then
  printf 'INCOMPLETE(invalid or overflowing absolute deadline: %q); no eligible preparation\n' "${deadline_raw}" >&2
  exit 1
fi

now_sec="$(date +%s)"
if [ "${deadline_raw}" -le "${now_sec}" ]; then
  printf 'INCOMPLETE(stage deadline %s already expired); no eligible preparation\n' "${deadline_raw}" >&2
  exit 1
fi

provider_abs="$(cd -- "${provider_dir}" && pwd -P)"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
module_root="$(cd -- "${script_dir}/.." && pwd -P)"

exec env GOWORK=off go -C "${module_root}" run -mod=readonly ./scripts/rebuild-profiler-ebpf --deadline "${deadline_raw}" -- "${provider_abs}"
