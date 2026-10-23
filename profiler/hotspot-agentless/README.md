# MazeWall external HotSpot collector assets

This is the single canonical Go collector source tree. Kotlin evidence and local
asset admission/readiness decisions are internal integration components behind the
explicit public strategy. The approved [integration plan](../../docs/superpowers/plans/2026-10-03-mazewall-hotspot-profiler-integration.md)
is complete; whole-session coverage remains incomplete.

For versioned offline distribution, license/corresponding-source inventory,
library/protocol pairing and the compiled installed example, see
[operator guidance](../HOTSPOT_AGENTLESS.md#versioned-offline-distribution).
`:profiler:distHotSpotAssets` packages existing verified local assets; prepared
native source/cache and Go are required only for this optional packaging task.
The versioned output destination must not already exist.

The engine retains its historical `io.mazewall/experiments/hotspot-agentless`
module namespace for Go `internal` compatibility. The experiment now carries an
explicit physical source snapshot and local preparation/build tooling with recorded
provenance; canonical runtime changes require deliberate snapshot updates.
Ordinary Kotlin builds have no dependency on these Make
targets or the Go/Docker toolchains.

## Explicit build, verification and installation

```bash
# Go 1.26.10 and an explicitly configured rootful Docker builder are needed at build time.
DOCKER_HOST=unix:///var/run/docker.sock ./gradlew :profiler:buildHotSpotAssets
./gradlew :profiler:verifyHotSpotAssets
./gradlew :profiler:installHotSpotAssets -PhotSpotAssetsDirectory=/absolute/new/assets-directory
```

The equivalent Make commands are `make bundle`, `make verify-bundle`, and
`make install DESTDIR=/absolute/new/assets-directory` in this directory.
Installation verifies first, stages in an owned temporary sibling, then publishes
to a new destination using Linux atomic no-replace rename. Existing destinations,
including empty directories created during publication, are refused. Serialize installation
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
Release collector builds also use Go linker `-s -w`: ELF debug information and
the external symbol table are omitted to reduce bytes hashed by interpreter
admission. Go runtime/build metadata and embedded profiler BPF objects remain.
Debugger-oriented builds can omit those flags explicitly; they are separate from
the release artifact. Source/license archives remain complete. Verify real release
artifacts with `go run ./cmd/mw-collector-tools footprint --baseline <baseline> --candidate <candidate>`.
This verifies the actual prepared-source identity and generated native hashes,
stripped/static ELF layout and embedded Go provenance.
Fault/diagnostic preparer variants have separate manifests and are not bundle inputs.

The current accepted source overlay is
`b9a9d70db7e37bba07293a635cac94cc540688b85e1f3aa31c2e9ad25cfeecf3`.

The containerd overlay migrates Gadget's actual client and test utilities to
patched `github.com/containerd/containerd/v2 v2.3.7`. Discovery retains `k8s.io`
as its default namespace and preserves explicit overrides and permission failures.
Both modules' prepared graphs must pass their race suites and `govulncheck`;
there are no vulnerability suppressions. Older bundles are not admitted by this
fingerprint and must be rebuilt into a new destination, not overwritten in place.

The [owned-drain repair](../../docs/plans/2026-10-19-hotspot-observed-drain-evidence.md)
wires actual auxiliary ownership for both HotSpot modes without fabricating an
epoch lease. It preserves strict admission and native budgets; ordinary-JVM
candidate qualification is completed by the separate
[coupled JVM exec evidence](../../docs/plans/2026-10-19-hotspot-coupled-exec-evidence.md),
followed by operator-approved [scoped admission](../../docs/plans/2026-10-19-hotspot-observed-admission.md).
SDKv0.2.0 is published; host distribution qualification remains open. The historical allocator/helper evidence
below is not a release/admission receipt for the new source graph. Existing asset
bundles must be rebuilt and fully verified for the current fingerprint.

Task3 adds a separately negotiated schema3 observation profile, described in
the [profile contract](../../docs/plans/2026-10-18-hotspot-observed-profile-contract.md).
The rebuilt CLI and `RunObserved*` now use the qualified owned implementation after
explicit operator approval. Strict2 and false policy eligibility are unchanged.
`RunObservedCandidate` is only a tagged compatibility alias. Native modules pin released SDKv0.2.0.
Use `GOWORK=off` with the owner's prepared `build/experiment.go.mod`; the SDK must
resolve to that released module, not a local workspace replacement. Existing helper
bundles do not qualify the new graph; rebuild and verify their embedded module
provenance before distribution. Strict2 and the Kotlin facade retain their scope.
It adds the bounded shared native correlation allocator. Both architecture BPF
objects and layouts are generated; actual-C and isolated kernel checks pass.
Task2 built two byte-identical locked Go1.26.0 helpers/full local OCI bundles and
executed all eight public-native cases without skips. The qualified helper is
`d0cc2a1ddf91fecef49d80a5bb3fded0d416a7002664fbfcef6bf4c1d9f3f93c`;
the unchanged gadget is `sha256:5c5791bb47beba9568d98216abf7af4a03abe9093d1d024d511a26e645e051e9`.
Actual non-time task binding, selected accounting/drain and legacy rendered/pdata
checks passed. Both dense600 diagnostics still rejected; experimental carrier
controls do not qualify lifetime or active perf/NMI contexts. Task3's optional
complete-path native kprobe fixture closes finite cache publication/eviction/drain
acceptance:376calls/366exact packets/0reported loss, native LRU republish and checked
all-owner retirement. The scoped allocator issue is resolved; synthetic entry
seeds do not replace JVM metadata proof or exhaust possible schedules.
The R1 review correction makes reordered-definition regression fixtures distinct;
its hashed test input changes the graph from `962b…` without changing native source,
generated layouts or either embedded architecture. Fresh all-fourteen pin-ordering
receipts accompany the correction; older chronology limits remain documented.
Preserved `270a…`, `618c…` and earlier bundles retain their historical evidence;
current admission requires the new source identity and refuses those old pins.
See [allocator evidence](../../docs/plans/2026-10-18-hotspot-correlation-allocator-evidence.md).

For an explicitly offline rebuild with already acquired pinned images, invoke the
existing `mw-gadget-build` command with `--builder-image-pull never` and the locked
builder digest, and Docker with `--pull=never`. Set both `DOCKER_HOST` and
`MAZEWALL_DOCKER_HOST` to the authorized rootful endpoint. The normal Make gadget
target retains its explicit `always` pull policy; there is no network fallback in
asset admission or capture. Preserve old `build/assets` before installing the new
verified bundle. Task2's receipts retain exact offline commands and destinations.

`manifest.json` locks collector version 2, production wire schema 2, capture ABI 2, Linux-amd64,
upstream revisions and the verified overlay fingerprint. Every listed file has a
SHA-256 checksum. Verification bounds the manifest to 64 KiB, 256 files and 512 MiB,
rejects symbolic/traversing paths, missing/incomplete files, digest/size mismatches,
unsupported versions and unlisted transitive OCI data. Kotlin admission also
bounds JSON depth. The manifest is trusted distribution/operator input, **not a
signature or protection against an operator replacing the manifest and files**.
The operator owns the directory and must keep it stable during validation/use.

Asset maintenance uses the existing Go owner:

```bash
go run ./cmd/mw-collector-tools pack --collector build/mw-hotspot-collector \
  --store "$HOME/.ig/oci-store" --image "$(cat build/gadget-image.ref)" --output /absolute/new/bundle
go run ./cmd/mw-collector-tools verify --bundle /absolute/existing/bundle
go run ./cmd/mw-collector-tools install --bundle /absolute/existing/bundle --output /absolute/new/install
go run ./cmd/mw-collector-tools release --bundle /absolute/existing/bundle \
  --output /absolute/new/release.tar.gz --library-version 0.1.0-SNAPSHOT
```

The optional Gradle tasks declare compilation of this tool from its sources and
pins. Verify/install do not construct native assets; distribution uses existing
verified prepared source. Root and adjacent archive checksums cover every release
file, selected-module notices and reproducible corresponding-source archives.

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
Internal JVM process/pipe supervision is implemented in milestone 4. Milestone 5
exposes the [explicit public strategy/results/session](../HOTSPOT_AGENTLESS.md).

## Owned Kotlin adapter (internal)

`HotSpotBackend` verifies explicitly configured assets, reads the target birth
generation through a testable identity trait, and launches through the existing
`ProcessLauncher` with separate stdout/stderr. It selects schema 2 and parent stdin
control automatically for that supervised child. Operator argv prefixes are
immutable argument vectors; there is no shell interpolation or automatic sudo.
The helper, rather than the parent's UID/capability probe, admits actual privileges
and runtime. A configured wrapper must preserve stdin/EOF and wait for the actual
collector, including aborts; detached/untracked descendants are outside that launch
contract. Native success was verified with an explicit cached-image Docker wrapper.

The parent owns asynchronous launch, stdout/stderr readers, process wait, finite
timer, stop/abort and retirement tasks. Snapshots retain immutable paired evidence,
separate pending occurrence/retention accounting, bounded stderr (64 KiB) and
pending ownership. Retention limits continue accounting while dropping retained
payloads. Stdout is bounded schema-2 JSONL; stderr is always drained even after its
retention limit. Stops write exactly stop newline once and keep stdin open until
child exit. Every worker must actually join and owned pipes must close successfully
before resources-joined completion is admitted. A blocked launcher/reader/closer
or failed close stays owned, exposes partial failure evidence and can be retried
through the retained owner. Failed sessions do not become successful after cleanup.

The internal ownership session supplies snapshot/finish/close. The additive public
facade wraps it with immutable evidence and compatible result descriptors; AUTO,
USER_NOTIF and recorded-EBPF behavior remain unchanged.

## Verification

`make unit` runs canonical race tests and local graph packaging/install controls.
`make vuln` scans the prepared module graph with `govulncheck`; install the pinned
tool with `go install golang.org/x/vuln/cmd/govulncheck@v1.9.0`. Findings or scanner
errors fail the command. CI runs both commands when either Go module changes.
The [experiment harness](../../experiments/hotspot-agentless/README.md) runs the
upstream race/native finalization/live attribution controls against this engine.
Promotion does not broaden runtime, syscall, virtual-thread or loss guarantees.
Release licensing/distribution and public API acceptance remain integration gates.
