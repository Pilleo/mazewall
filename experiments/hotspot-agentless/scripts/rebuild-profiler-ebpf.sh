#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
provider=$(realpath -- "$1")
case "$provider" in "$root"/build/deps/profiler-*) ;; *) printf 'Refusing unowned provider directory\n' >&2; exit 1 ;; esac
builder=$(cd "$root" && "${GO:-go}" run ./cmd/mw-collector-tools image --key gadget_builder)
docker_cmd=("${DOCKER:-docker}")
if [[ -n "${DOCKER_HOST:-}" ]]; then docker_cmd+=(--host "$DOCKER_HOST"); fi
"${docker_cmd[@]}" run --rm --network none --user "$(id -u):$(id -g)" \
    --mount "type=bind,src=$provider,dst=/provider" --workdir /provider \
    --entrypoint /bin/bash "$builder" -c '
set -euo pipefail
cd support
printf "// Copyright The OpenTelemetry Authors\n// SPDX-License-Identifier: Apache-2.0\n\n" > types_epoch.go.tmp
CC=clang go tool cgo -godefs types_def.go >> types_epoch.go.tmp
sed -i '\''s/^package support$/package support \/\/ import "go.opentelemetry.io\/ebpf-profiler\/support"/'\'' types_epoch.go.tmp
/usr/local/go/bin/gofmt < types_epoch.go.tmp > types.go
rm types_epoch.go.tmp
cd ebpf
chmod +x print_instruction_count.sh
export PATH=/usr/lib/llvm-18/bin:$PATH
find . -maxdepth 1 \( -name "*.o" -o -name "*.d" \) -delete
make -s -o errors.h TARGET_ARCH=amd64 BPF_CLANG=clang BPF_LINK=llvm-link-18 LLC=llc-18 STRIP=llvm-strip
make -s -o errors.h TARGET_ARCH=arm64 BPF_CLANG=clang BPF_LINK=llvm-link-18 LLC=llc-18 STRIP=llvm-strip
'
