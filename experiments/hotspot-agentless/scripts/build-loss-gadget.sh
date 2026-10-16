#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
builder=$("${GO:-go}" run ./cmd/mw-collector-tools image --key gadget_builder)
source_dir=$("${GO:-go}" run -modfile build/experiment.go.mod ./cmd/mw-experiment-tools loss-source)
mkdir -p build/loss-gadget
go run ./cmd/mw-gadget-build --builder-image "$builder" --builder-image-pull always \
    --output "$root/build/loss-gadget" --tag mw-hotspot-openat-loss-test:v1 "$source_dir"
image=$("${GO:-go}" run ./cmd/mw-collector-tools oci-ref --store "$HOME/.ig/oci-store" --name mw-hotspot-openat-loss-test:v1)
printf '%s\n' "$image" > build/loss-gadget-image.ref
