# MazeWall external HotSpot collector assets

This is the single canonical Go collector source tree. Kotlin evidence and local
asset admission/readiness decisions are internal integration components; public sessions
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

`manifest.json` locks collector version 2, production wire schema 2, capture ABI 2, Linux-amd64,
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
  --protocol-version=2 --hotspot-identity=safepoint-epoch --drain-full --duration 60s
```

Select the launch authority yourself. This command cannot change an existing
JVM's startup settings. The initial strict profile admits the pinned Temurin25
interpreter/SerialGC runtime only. Keep the target alive through final enrichment
and drain. Stdout is JSONL; stderr is separate. Physical finalization does not
prove whole-session loss completeness or produce an enforcement policy.

## Production readiness and control (schema 2)

Select `--protocol-version=2` explicitly. It requires the strict runtime/owned
full-drain scope and verified enrichment bounds. Schema 1 remains the separately
selected experimental/replay contract; it is not production readiness.

`session_started` describes the requested PID/profile. `collector_ready` follows
Gadget's successful OCI/ebpf Start (native readers/maps/link established), strict
runtime discovery and a fresh target generation/pidfd/exec/fatal check. It carries
host PID, start ticks, BOOTTIME clock/sample, exact JVM build/hash and provider pins.
It does not wait for a useful Java stack; cold events may still be unavailable.
Events from already-running target threads may precede ready. A failed startup
does not emit ready. `collector_error` preserves diagnostics and native errno when
available; an unjoined/blocked event writer cannot be safely used to append an error,
so failed EOF/exit must also be handled. Summary/EOF alone never prove completion.

SIGTERM and SIGINT both request the existing checked graceful drain. To bind a
supervised child to its parent's pipe lifetime, also pass `--control-stdin`: keep
stdin open through finalization, write exactly `stop\n` once to request drain,
and close it on parent loss. EOF or malformed input aborts the independent owned
native/enrichment lifetime. The bounded poll worker joins without closing borrowed
stdin. Pipe lifetime guarantees apply when that option is configured; signal-only
launches do not acquire a parent-death guarantee.

Internal Kotlin `HotSpotSessionMachine` makes pure exhaustive state/effect decisions.
Startup is bounded to 10 seconds, strict session lifetime to 60 seconds, and stop
to at most two remaining seconds. One finish owner is retained through duplicate
finish/close. Completion requires a verified healthy native summary, stdout EOF,
stderr join and zero process exit; failures preserve causes and pending ownership.
Actual JVM process/pipe supervision is milestone 4.

## Verification

`make unit` runs canonical race tests and local graph packaging/install controls.
The [experiment harness](../../experiments/hotspot-agentless/README.md) runs the
upstream race/native finalization/live attribution controls against this engine.
Promotion does not broaden runtime, syscall, virtual-thread or loss guarantees.
Release licensing/distribution and public API acceptance remain integration gates.
