# Canonical HotSpot collector and local assets — milestone 2

The approved integration milestone 2 promotes one source tree to
`profiler/hotspot-agentless/`, with `mw-hotspot-collector` as the collector-facing
command. Runtime packages, module/sum/lock, gadget and authored overlays move
together; experiment runtime paths are source links. Experiment preparation/build
delegates to the canonical tree, while benchmark/oracle/unloading tooling and
fixtures/results remain in the experiment. The historical internal Go module
namespace is retained; generated dependencies are not maintained runtime copies.

## Asset contract and build evidence

The optional Gradle tasks `buildHotSpotAssets`, `verifyHotSpotAssets` and
`installHotSpotAssets` build or verify/install explicitly. Verify/install never
trigger build or network access. Ordinary JVM compilation/build does not depend
on them. Explicit simultaneous task selection has `mustRunAfter` ordering.

The pinned Go image builds static Linux-amd64 with CGO disabled, trimpath and VCS
metadata disabled. The pinned Gadget builder uses fixed `SOURCE_DATE_EPOCH`.
The exporter copies the actual reachable OCI index/manifests/config/BPF graph,
checks each descriptor's digest/size and produces a local read-only loader store.
Installation stages verified files into a new destination and refuses existing
destinations. The operator owns/stabilizes trusted assets; checksums are not a
distribution signature. Public distribution/licensing remains milestone 7.

`HotSpotAssets` is internal Kotlin admission: schema/version/platform/ABI/pins,
executable presence, per-file checksums, transitive OCI closure and descriptor
sizes, bounded manifest/depth/file count/bytes and nontraversing nonsymbolic paths.
It performs no process launch, compiler invocation, registry fetch or escalation.

| Fingerprint | Actual value |
|---|---|
| Overlay bundle | `e908bb2689936481f8a696dd2347457cc44b6344c1fa78ca65b37772032703eb` |
| Static collector SHA-256 | `9f63210dde91cc404b832eca5727fe0524535f425e28510f3813c9f96cb784bf` |
| Fixed-epoch gadget OCI digest | `sha256:3ae6007bdf574e1c082b5f185f38d3f60bb678d9e964f05a658da4ac5670de68` |
| Bundle files/bytes | Executable + eight OCI files; 95,874,205 bytes |

The independent local Go1.26.0 static build equals the pinned Go-image executable
byte for byte. Repeated fixed-epoch Gadget builds reproduce the packaged OCI
digest. `go version -m` confirms Go1.26.0 and the selected patched Gadget/profiler
replacement graph. Existing generated native layout/BPF fingerprints are verified
by the unchanged canonical preparer; the overlay fingerprint did not change.

## Verification

- Observed initial red Kotlin compilation for absent `HotSpotAssets`, then the
  intended red assertion for an omitted transitive BPF file despite a valid root
  digest. Eight synthetic contracts pass; the ninth guarded test passes against
  the real installed native bundle with `MW_HOTSPOT_ASSET_TEST_DIR`.
- Five Python package/install tests pass, including transitive missing/corrupt
  BPF data, unsupported ABI, symlink refusal, tamper refusal without destination
  publication, and existing-install refusal. Initial red was missing packager.
- Experiment `make verify` passes through canonical/runtime and harness race
  tests, upstream ownership/identity/layout tests, build and selected module graph.
  An initial 180-second run timed out; the completed repeat passes.
- Native event finalization and definition finalization controls pass, including
  8/5000 queued definitions, held callback retention and idle netlink wake/join.
- Explicit Gradle build, verification and installation pass. Development exposed
  task-ordering and unset Exec executable defects; both were corrected before
  the successful installer gate.
- Full `:profiler:test`, production `:profiler:detektMain`, warning ratchet and
  `./gradlew build` pass. Direct `:profiler:detektTest` reports pre-existing
  `ImplicitDefaultLocale` errors in shared `TestSuiteHealthListener.kt`; no new
  asset-code Detekt findings remain. The merge gate is the supported full gate.
- Impact tooling reports `PARTIAL`/stale Codanna; direct package/import inventory
  and native/replay regression evidence supplement that incomplete caller graph.

## Real installed capture

Installed files reside under the ignored harness directory
`build/installed-assets-milestone2`; capture mounts only its isolated `oci/` store,
not the builder's personal cache. The collector uses its installed executable.
The target runs as the ordinary UID, exact pinned Temurin25 `-Xint`/SerialGC.

The live API contract exposed Gadget's name normalization: a bare
`mw-hotspot-openat@sha256:...` is rewritten to
`ghcr.io/inspektor-gadget/gadget/mw-hotspot-openat@sha256:...` before local resolve.
The bounded asset adapter/exporter now use that full reference. The prefix is
a local lookup identity, not permission to fetch remotely. Failed attempt
`capture.7757Gd` is retained. A subsequent `capture.UaIYsr` completes native capture
but the historical nondrain structured test rejects the newer, honest quiescent
loss label; the strict benchmark gate is used for strict finalization acceptance.

`build/benchmark.dba66545` measures three independent baseline/full pairs with
1200 marked operations, eight routes, 600 target warmup and 600 post-attachment
preparation operations per run. All three full runs have **1200 correctly ordered
application segments, zero wrong/order/mixing/skipped/missing marked operations**.
Each full session delivers 1803 event terminals; unavailable counts are 93,132,155
across all delivered events, outside the fully attributed marked measurement.
Event producer quiescence/drain/join/counter read/retirement, definition drain and
auxiliary worker completion are true. Native reserve-failure observations are zero;
collection health remains partial and loss completeness remains false.

No runtime, syscall or loss guarantee is broadened. This milestone is asset
promotion/installation, not public Kotlin process supervision. Readiness/control,
owned session supervision and public API dispatch are milestones 3–5.
