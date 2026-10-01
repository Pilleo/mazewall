# Upstream patch status

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
`go.mod` stay pinned. The collector snapshots reader health after cancellation
and pending enrichment completion, before closing its symbolizer. This does not
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
