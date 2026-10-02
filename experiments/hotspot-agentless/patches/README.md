# Upstream patch status

## Event-read loss patch (local, not submitted upstream)

`gadget-event-loss.patch` preserves a successful ring-buffer payload accompanying
an independently polled loss observation. Its stateless adapter reports loss,
then forwards a nonempty sample through the original datasource path; perf-style
loss-only/empty reads emit no fabricated event. The original reader's `continue`
discarded an additional valid event for each positive ring loss report.

The full event-operator race suite and an isolated real-ring pressure control
verify payload delivery, subscriber errors and loss-only behavior. Normal native
buffers/maps and loss polling are unchanged. See
[`event-read-loss.md`](../results/event-read-loss.md) for the regression, actual
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
has no runtime fault switch. See [evidence](../results/loss-benchmark.md).

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

See the [implemented evidence](../results/identity-investigation.md#implemented-bounded-remedy-2026-10-01)
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
3. Observable HotSpot scope-lookup and BCI quality (pending).

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
