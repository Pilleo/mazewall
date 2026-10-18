# Upstream patch status

## Contribution review inventory (2026-10-03)

The [local Kubescape proposal](../../../docs/plans/2026-10-03-hotspot-kubescape-contribution-proposal.md)
records the actual tested graph, a genuine record projection and the inspected
node-agent fork/schema mismatch. No upstream acceptance or submission is claimed.
The experiment bundle contains several independent review families:

| Review unit | Authored source boundary | Verification / dependency |
|---|---|---|
| Typed structured result and immutable correlation identity | `gadget-structured.patch`, structured resolver additions | Lookup/pruning/identity/cancellation race tests; original rendered APIs retained |
| Missing-definition recovery | `gadget-additions/pkg/symbolizer/otel/recovery*`, optional symbolizer recovery | Exact-task LRU scan/race tests and isolated real drop-definition build |
| Observable walk/scope/BCI quality | `profiler-quality.patch`, libpf/HotSpot additions | Original decoder plus quality/mutation/live corpus checks; semantic BCI oracle remains open |
| Loss payload dispatch and health | `gadget-event-loss.patch`, `gadget-health.patch`, `profiler-health.patch` | Valid sample plus positive loss regression, native pressure and partial counter checks |
| Strict epoch identity/native ABI | `profiler-epoch-*`, `profiler-identity.patch`, `gadget-epoch.patch` | Pinned interpreter/Serial only, generated layouts/BPF and unloading acceptance; not a general JIT profile |
| Definition/event drain | `profiler-definition-drain.patch`, `gadget-definition-drain.patch`, `gadget-finalization.patch`, additions | Native queued tails8/5000, ordered callback completion and deadline retention |
| Checked metadata retirement | `profiler-metadata-retirement.patch`, processmanager additions | Native cause/failed-owner retention and checked reader-join regressions |
| Auxiliary/prune worker ownership | `profiler-auxiliary-ownership.patch`, `gadget-auxiliary-ownership.patch`, additions | Scoped cancellation/join/finalizers, native perf/netlink controls and fresh strict reports |

These units are local overlays, not standalone upstream PRs yet. Epoch and
lifecycle units depend on selected profiler/bridge boundaries and require separate
review; review-unit separation is not a claim that every patch applies in isolation.
Fault/diagnostic additions remain test-only with separate manifests.

## Bounded native correlation allocator (2026-10-18, scoped native qualification)

`profiler-epoch-capture.patch` includes the shared production `correlation_ids.h`:
one ARRAY/u32/u64/1, at most 32 CAS attempts, positive IDs through INT64_MAX.
The actual miss path returns zero without cache/definition publication on bounded
allocation failure; hits, full keys, trace ABI, deadlines and membership pruning
remain intact. The existing loader acquires the allocator with the LRU and rewrites
both unwinder variants to that same map; no new public accessor/reset is added.

`tests/correlation/dispatcher.c` includes the entire prepared dispatcher and real
layouts. `tests/correlation/allocator.bpf.c` compiles the same production header
into a test-only runnable BPF wrapper. See
[allocator evidence](../../../docs/plans/2026-10-18-hotspot-correlation-allocator-evidence.md)
for RED/GREEN/mutants, actual multi-CPU execution, loader ownership, source controls
and retained failures. These isolated checks do not prove HotSpot transport or
active perf/NMI safety. Task2 separately produced two byte-identical locked helpers
and complete local OCI bundles, independently verified every asset/descriptor, and
executed all eight public-native cases with real current assets. Observed IDs1–9
in completed public fixture captures bind to exact tasks; zero stays unavailable.
Rendered and serialized pdata compatibility pass at1,2^53+1,INT64_MAX. The one
experimental six-case control includes four carrier TIDs without observed reference
aliasing, not JIT/GC/Loom lifetime proof. Both dense600 diagnostics still reject.
Same-key publication/eviction interleavings and active perf/NMI context coverage
remain limited; the allocator issue is not archived. No runtime source changed in
Task2, and the original failure/historical source linkage remains unproved.
The R1 review correction uses distinct task/frame/epoch fixtures and exact binding
assertions, including preservation after rejected redelivery. A definition-swap
negative fails that oracle. Its new hashed graph preserves all native bytes and
reuses the original isolated native receipts; current all-fourteen advancement
chronology is recorded separately from the older incomplete chronology evidence.

## Current strict full and retirement patches

Optional structured `--drain-full` now requires acknowledged event readout,
definition perf/final consumption, enrichment completion and auxiliary/prune joins
under the original remaining stop budget. Native metadata failures preserve errno
and failed ownership. The ordinary partial capture path remains distinct.
See [strict definition evidence](../../../experiments/hotspot-agentless/results/full-definition-finalization.md),
[metadata remedy](../../../experiments/hotspot-agentless/results/metadata-retirement.md) and
[auxiliary retirement](../../../experiments/hotspot-agentless/results/auxiliary-worker-retirement.md). Explicit buffer
drains do not upgrade native loss to complete. Historical sections below describe
the earlier patch milestones and their initial claim boundaries.

## Optional event-only finalization patch (local, not submitted upstream)

`gadget-finalization.patch` activates only via explicit optional Gadget vars.
The source scope must be one openat kprobe/ring tracer, disabled stack helpers,
and the required per-CPU uint64 counter; additional producer types are refused.
Additions implement one bounded state/effect owner, callback/reader completion,
quiescent counter sampling without reset polling and checked resource retirement.
Default full capture retains its existing partial shutdown behavior.

`make event-finalization-kernel-test` exercises the actual Gadget Stop/reader/
datasource path with a real filtered openat link, queued tail, pressure, future
publication refusal after detach and timed-out callback retention. Native layout,
buffer and cache sizes are unchanged. See
[`event-only-finalization.md`](../../../experiments/hotspot-agentless/results/event-only-finalization.md) for source
retirement semantics, exact evidence, partial claim boundaries and review gates.

## Event-read loss patch (local, not submitted upstream)

`gadget-event-loss.patch` preserves a successful ring-buffer payload accompanying
an independently polled loss observation. Its stateless adapter reports loss,
then forwards a nonempty sample through the original datasource path; perf-style
loss-only/empty reads emit no fabricated event. The original reader's `continue`
discarded an additional valid event for each positive ring loss report.

The full event-operator race suite and an isolated real-ring pressure control
verify payload delivery, subscriber errors and loss-only behavior. Normal native
buffers/maps and loss polling are unchanged. See
[`event-read-loss.md`](../../../experiments/hotspot-agentless/results/event-read-loss.md) for the regression, actual
pressure denominator, retained failed runs and remaining finality gates.

## Definition shutdown patches (local, not submitted upstream)

`profiler-shutdown.patch` makes both trace and batch-barrier channel delivery
cancellation-aware. The reader owns a completion channel and closes its native
perf reader before publishing completion. Undelivered traces return to the
existing pool. `gadget-shutdown.patch` adds resolver-owned child cancellation and
consumer completion; optional `StopDefinitions(ctx)` joins both owners. Existing
resolver interfaces retain their signatures, buffers and caches.

The collector completes pending enrichment, attempts the two-second stop, then
samples final fatal/health state. Successful joins permit resolver close;
timeouts remain explicit errors and unjoined partial snapshots. This is an owned
worker join, **not** a native producer-buffer drain. Callback joining and canceled
delivery have channel-gated race tests; buffer finality remains an open gate.

`faults/drop-marked-event.patch` is applied only to an owned generated gadget
source copy by `build-loss-gadget.sh`. It drops benchmark token 42 after allocating
its occurrence sequence and has a separate image/manifest. Normal gadget source
has no runtime fault switch. See [evidence](../../../experiments/hotspot-agentless/results/loss-benchmark.md).

## Epoch identity patches (local, not submitted upstream)

`profiler-epoch-capture.patch` changes C trace/proc-info/cache layouts and capture
brackets; `profiler-epoch-memory.patch` adds immutable-memory helper paths;
`profiler-epoch-handler.patch` carries captured identity and separate validation,
guards the actual frame cache, enforces raw frame bounds and connects the required
fatal owner. `gadget-epoch.patch` forwards options/tasks/definitions through the
existing correlation cache. Additions contain the guard/ledger and their tests.

`scripts/rebuild-profiler-ebpf.sh` runs the existing pinned builder's Clang/LLVM
18 and cgo tools in an owned generated tree. Both embedded architectures and Go
layouts are regenerated together. The preparer hashes source inputs and verifies
artifact hashes on reuse; incomplete or mismatched trees are refused. Source
patches still apply without fuzz to checksum-verified original modules.

ABI v2 producer keys are `24+3072*8` bytes, with unchanged uint64 values and
256-entry capacity. Recovery and the native eviction/pruning test use this ABI.
Only explicit strict mode enables the pinned interpreter/Serial lifetime claim;
other existing modes retain unknown identity. Captured identity, validation and
quality are separate. Permission/unexpected errors never depend on an event
lookup for fatal delivery. The genuine drop-definition fault variant handles
both legacy and strict storage and remains absent from normal source bundles.

See the [implemented evidence](../../../experiments/hotspot-agentless/results/identity-investigation.md#implemented-bounded-remedy-2026-10-01)
before considering upstream submission or broader runtime support.

Build inputs retain Gadget base `7097db144ccd8dab15373f27ec98d2c3d22e4588` and
the profiler replacement base `ef6cf29492c8a980f889052903fa2dd0c0765cc0`.

## Structured-result patch (local, not submitted upstream)

`gadget-structured.patch` changes two existing production files and adapts their
existing tests. `gadget-additions/` contains new upstream Go source/test
files, stored as `.go.txt` so the experiment does not treat them as its own Go
packages. The preparer installs them at the corresponding Gadget paths.

- Adds an optional structured-resolver capability without breaking existing
  resolver interfaces or rendered callers.
- Retains PID/TID alongside frames in the existing correlation cache.
- Returns immutable ordered frame sequences and rejects mismatched task identity.
- Reuses existing adaptive waits and pruning, with cancellation-safe shared
  waiter ownership and synchronized lazy prune-loop startup.
- Keeps BPF LRU capacity, prune policy, perf buffers and startup defaults intact.

The original module is checksum-verified before applying the patch without fuzz
to ignored build sources. `build/patch-manifest.json` records the exact base and
patch/addition content hash; Make embeds that hash into the collector. There is
no upstream branch/commit for this local proposal yet.

Verification: `make upstream-test` runs the original lookup/pruning tests plus
the new identity, immutability, cancellation and missing-definition cases with
the race detector. `make demo` verifies genuine typed Java-frame delivery.

Future independent review units:

1. Structured results (implemented locally; upstream review pending).
2. Lost-first-definition recovery through the existing cache (implemented locally).
3. Observable HotSpot scope-lookup and BCI quality (implemented locally; upstream review and semantic BCI fidelity pending).

Record exact branch/revision, purpose, verification and required root-module
replacements when each patch exists. Do not vendor another profiler tree here.

## Recovery additions and isolated fault build

The optional `RecoverDefinition` capability uses the existing tracer's LRU
accessor. It checks the exact task and reference, retains late definitions,
rejects partial/overlong scans, and rechecks the map value before deleting one
unknown entry. A fresh definition is published by the unchanged producer on a
subsequent operation. Native permission failures are propagated. There is no
new stack cache and no change to existing LRU/pruning/buffer configuration.

`patches/faults/drop-first-definition.patch` is applied only by the explicit
`make recovery-test-build` target. It drops one genuine symbolized application
definition before correlation-cache insertion. Its code is absent from normal
builds, and the test binary/modfile/manifest are distinct from the normal
collector. The fault variant has its own embedded content hash.

Recovery unit tests cover exact-task selection, aborted/limited iteration,
replacement races, cancellation, late definitions and native EPERM/EACCES.
`make recovery` exercises the real producer cache and verifies that subsequent
correctly attributed operations occur after the actual recovery clock, without
rewriting original unavailable outcomes.

## Collection-health patches (local, not submitted upstream)

`gadget-health.patch` implements cumulative forwarded-loss reporting on the
existing datasource, under its existing lock. An optional `ReportedLossReader`
accessor keeps the datasource interface unchanged. Gadget's ring-loss polling
uses lookup/reset and can race producers; forwarded reports are a lower bound.

`profiler-health.patch` exposes observations owned by the existing trace perf
reader. Its three counters retain their original interval `Swap(0)` metrics and
also retain cumulative totals. Non-consuming snapshots are per tracer, with no
global metrics exporter, replacement cache, polling loop or buffer changes.
The existing perf reader supplies the actual per-CPU data-buffer size.
New profiler additions retain OpenTelemetry copyright/SPDX notices.

Both original module checksums are verified before patching generated trees in
ignored `build/deps/`. Both bases and the combined content hash are recorded in
the manifest and temporary root module replacements. The module cache and base
`go.mod` stay pinned. The collector snapshots reader health after cancellation,
pending enrichment completion and owned worker stop, before closing its symbolizer. This does not
establish a producer/reader drain barrier. Counters are independently sampled;
native submit failures, unread loss records and symbolization failures are not
complete definition-loss accounting. Final event/definition loss stays unknown.

`make upstream-test` verifies cumulative/isolation semantics with the race
detector, including metrics resets. `make kernel-cache-test` requires a
privileged container and verifies real LRU eviction using the pinned provider's
key/value sizes and 256-entry capacity, then the existing two-cycle pruning.
It characterizes that native map configuration; it does not measure RSS, total
provider memory, burst capacity, or loss completeness.

## HotSpot quality patch (local, not submitted upstream)

`profiler-quality.patch` extends the public `libpf.Frame` value with comparable
immutable metadata, then annotates existing method/JIT/stub/interpreter paths.
Approval for this extension was explicit. Selected graph builds and interned
value tests pass; external unkeyed struct literals may need updates and frame
memory/overhead effects have not been measured.

The existing greatest-preceding/first-exact PC algorithm is preserved with checked
descriptor layout. Unsigned5 decoding and method cache remain upstream-owned.
Method indexes/null pointers are checked before access; nonzero rejected scope
links return errors and retained frames are reinterned with partial diagnostics.
Zero encoded scope BCI is unavailable; interpreter sentinel/out-of-range input
remains invalid even though the legacy source-line lookup uses zero. Returned
scope metadata is not a complete inline/walk or semantic BCI guarantee.

Gadget's existing handle-based cache preserves metadata without a side cache.
`make upstream-test` includes original and added libpf/HotSpot tests; `make
quality-fuzz` executes mutations with the selected graph (without coverage
guidance). A guided fuzz run also passed inside the generated upstream module,
using that module's own graph; it is separate evidence. The live quality gate
rejects pre-quality output and validates tiered and interpreter-only captures.

Error transport limitation: the process manager keeps already appended partial
frames, logs the decoder error and appends an unknown placeholder on failure.
Retained partial frames carry actual diagnostics; failures before emitting a
frame still have only the existing log/placeholder evidence. Interpreted method
lookup ignores the captured check argument; its identity gap is separately
tracked, while existing compiled-ID checking remains intact.

## Interpreted identity patch (local, not submitted upstream)

`profiler-identity.patch` stores the decoded full ConstMethod pointer alongside
the existing cached method and routes interpreted frames to `getInterpretedMethod`.
The checked path validates the captured low-32-bit shifted pointer fingerprint,
compares the full cached pointer, reloads changed cached methods, and rechecks
the live pointer after lookup. There are two bounded eight-byte identity reads
on this symbolization path. No identity side cache or BPF layout change is added.
Zero tokens are valid checks; checked reads reject null/overflow/short input and
preserve EPERM/EACCES errors with wrapping. Remote-memory bias is respected.

Existing compiled scope and process-manager frame-cache behavior remain in place.
The fingerprint is not a collision-free generation ID; full pointer recycling
and ABA cannot be excluded by these before/after reads. The regression proves
the formerly ignored check and stale method-cache behavior are corrected, not
the entire class-unloading/runtime identity matrix. Added reads/cache storage
have not been benchmarked. Upstream review and submission are pending.

## Isolated identity diagnostics

`--identity-observations` applies `diagnostics/identity-observations.patch` and
`diagnostic-additions/` only to a separate generated provider. Its binary, module
file and manifest are distinct. It logs raw PID/TID/reference/Method/cookie,
frame-cache hit state, returned names and sampled current full ConstMethod
pointers. These are validation observations after capture, not newly captured
generation evidence. The patch does not invalidate caches or change wire data.
It is mutually exclusive with the first-definition-drop fault build.

`make unloading-investigation` runs a pinned ordinary-user disposable-loader
fixture and currently fails its generation assertion. The uninstrumented control
fails the same assertion. The normal patch bundle and defaults remain the same;
the diagnostic variant must not be used as overhead evidence.
