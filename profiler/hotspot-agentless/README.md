# MazeWall external HotSpot collector assets

This is the single canonical Go collector source tree. Kotlin evidence and local
asset admission are internal integration components; public sessions/readiness
are subsequent milestones of the [integration plan](../../docs/superpowers/plans/2026-10-03-mazewall-hotspot-profiler-integration.md).

The engine retains its historical `io.mazewall/experiments/hotspot-agentless`
module namespace so the experiment's verification tools can share Go `internal`
packages without maintaining a second runtime. The experiment delegates its
preparer/build and links runtime source; only verification tools, fixtures and
results remain there. Ordinary Kotlin builds have no dependency on these Make
targets or the Go/Docker toolchains.

## Explicit build, verification and installation

```bash
# Go 1.26, Python 3 and an explicitly configured rootful Docker builder are needed at build time.
DOCKER_HOST=unix:///var/run/docker.sock ./gradlew :profiler:buildHotSpotAssets
./gradlew :profiler:verifyHotSpotAssets
./gradlew :profiler:installHotSpotAssets -PhotSpotAssetsDirectory=/absolute/new/assets-directory
```

The equivalent Make commands are `make bundle`, `make verify-bundle`, and
`make install DESTDIR=/absolute/new/assets-directory` in this directory.
Installation verifies first, stages in an owned temporary sibling, then publishes
to a new destination. Existing destinations are refused. Serialize installation
with any other operator modifying that directory. To rebuild changed source into
the fixed `build/assets` output, first move aside that owned generated bundle.

The bundle contains `mw-hotspot-collector`, `manifest.json` and an isolated `oci/`
layout containing exactly the pinned gadget's reachable descriptor/config/BPF
blobs. It does not depend on the builder's personal `~/.ig` cache after export.
The collector's native profiler BPF objects are embedded in its executable.
The pinned Go image produces a static Linux-amd64 executable; the pinned Gadget
builder builds the gadget. `SOURCE_DATE_EPOCH=1790985600` fixes OCI creation metadata,
and `-trimpath -buildvcs=false` removes worktree paths/status from the executable.
All dependency module sums, root replacements and zero-fuzz overlays are preserved.
Fault/diagnostic preparer variants have separate manifests and are not bundle inputs.

`manifest.json` locks asset version 1, wire schema 1, capture ABI 2, Linux-amd64,
upstream revisions and the verified overlay fingerprint. Every listed file has a
SHA-256 checksum. Verification bounds the manifest to 64 KiB, 256 files and 512 MiB,
rejects symbolic/traversing paths, missing/incomplete files, digest/size mismatches,
unsupported versions and unlisted transitive OCI data. Kotlin admission also
bounds JSON depth. The manifest is trusted distribution/operator input, **not a
signature or protection against an operator replacing the manifest and files**.
The operator owns the directory and must keep it stable during validation/use.

## Offline local loader

The actual Gadget API is `WithOrasReadonlyTarget`, backed by an ORAS local OCI
store. Use the full `ghcr.io/inspektor-gadget/gadget/mw-hotspot-openat@sha256:...`
reference: Gadget normalizes bare names before resolving. The image name is a
local lookup key and does not cause network access. `internal/capture/store.go` adapts the full image reference to a digest-only
local resolution and rejects a different reference. Fetching stays within that
store. Missing data fails; there is no registry fallback, compilation or automatic
privilege escalation in the capture path.

An explicitly privileged external launch against an ordinary target uses:

```bash
/absolute/assets/mw-hotspot-collector --capture --pid HOST_PID \
  --store /absolute/assets/oci --image ghcr.io/inspektor-gadget/gadget/mw-hotspot-openat@sha256:DIGEST_FROM_MANIFEST \
  --hotspot-identity=safepoint-epoch --drain-full --duration 60s
```

Select the launch authority yourself. This command cannot change an existing
JVM's startup settings. The initial strict profile admits the pinned Temurin25
interpreter/SerialGC runtime only. Keep the target alive through final enrichment
and drain. Stdout is JSONL; stderr is separate. Physical finalization does not
prove whole-session loss completeness or produce an enforcement policy.

## Verification

`make unit` runs canonical race tests and local graph packaging/install controls.
The [experiment harness](../../experiments/hotspot-agentless/README.md) runs the
upstream race/native finalization/live attribution controls against this engine.
Promotion does not broaden runtime, syscall, virtual-thread or loss guarantees.
Release licensing/distribution and public API acceptance remain integration gates.
