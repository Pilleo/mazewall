# Agentless HotSpot Safepoint-Epoch Identity Design

**Status:** High-level architecture approved in conversation on 2026-10-01.
Implementation authorized on 2026-10-01 after the second review. The execution
corrections below supersede the rejected reader/transaction/error contracts.
Implementation and focused/native/live acceptance have passed for the scoped
profile. Repository merge verification and independent review are recorded in
the linked evidence/issue; broader identity coverage remains open.

**Issue:** [Captured interpreted method identity](../internals/backlog/issue-20260930-220901-114-validate-captured-interpreted-hotspot-method-ident.md).

**Evidence:** [Identity investigation](../../experiments/hotspot-agentless/results/identity-investigation.md).

## Second-review findings (2026-10-01)

### Authorized execution corrections

- R1: keep the instance's RemoteMemory/ReaderAt immutable. A trace-local decoder
  passes a copied RemoteMemory with a bounded error ledger to private method,
  Symbol and stub lookup helpers. Mapping synchronization continues to use the
  original reader. Existing process-manager locks serialize metadata-cache use.
- R2: purge all four existing metadata LRUs on any rejected strict decode,
  including unchanged-epoch failures; admit frame-cache entries only after finish.
- R3: a required session-owned fatal callback retains bounded causes and cancels
  capture independently of lookups. The reader ledger preserves the first error
  and the first EPERM/EACCES separately; it never grows an error queue.
- R4: copy the captured identity unchanged to the definition. Store validation
  outcome/cause separately. Compare captured epoch first, then report semantic
  rejection as unavailable; true captured-identity conflicts remain fatal.
- R5: the collector owns a sched_process_exec barrier attached before discovery.
  A one-entry four-byte kernel poison map is session state, not a metadata cache.
  It is monotonic for the selected PID. Strict lookup checks it before and after
  resolution, and the existing pidfd watcher checks it while idle. Exec closes
  the supported session; no post-exec reference is accepted as checked evidence.

The original paragraphs below describe the historical reviewed proposal where
superseded; the corrections above describe the executed implementation. The five
findings have implementation/regression evidence and remain open for independent
review. The original uninstrumented failure is retained as a red control.

The table records the second review's defects and their required corrections.
They are now implemented as summarized above; see the linked evidence.

| Finding | Required correction before implementation |
|---|---|
| [R1: shared-reader ownership](../internals/backlog/issue-20261001-134304-861-serialize-strict-hotspot-epoch-reads-with-mapping-.md) | `SynchronizeMappings` runs outside `pm.mu`; temporarily replacing the instance ReaderAt and mutating its ledger is not serialized by the existing lock. Use immutable trace-local reads or explicit instance ownership covering both paths. |
| [R2: rejected metadata cache admission](../internals/backlog/issue-20261001-134316-242-roll-back-hotspot-metadata-caches-after-a-rejected.md) | Delaying frame-cache admission does not undo method/Symbol/stub records written during a failed decode. Define abort invalidation for all affected metadata caches, including unchanged-epoch retries. |
| [R3: session-fatal delivery](../internals/backlog/issue-20261001-134344-624-deliver-strict-provider-fatal-errors-independently.md) | `HandleTrace` only logs reporter errors; cached errors with no lookup do not reach the collector. Define bounded session-owned fatal notification/retention and preserve later permission failures in the read ledger. |
| [R4: captured identity versus validation result](../internals/backlog/issue-20261001-134356-432-separate-captured-hotspot-epoch-identity-from-vali.md) | Preserve immutable captured epoch evidence separately from decode outcome. A stable capture rejected after an epoch transition must be unavailable, not a fatal event/definition identity mismatch. |
| [R5: no-exec scope enforcement](../internals/backlog/issue-20261001-134412-393-enforce-the-no-exec-boundary-for-strict-hotspot-ep.md) | PID/start ticks survive exec. Specify authoritative invalidation before unsupported captures can reuse definitions, or restrict the guarantee to a verifiably controlled fixture lifetime. Periodic mapping checks are not an incarnation barrier. |

The isolated probe `build/epoch-review.r6i0tgok/probe.log` reproduced method-cache
pollution (`void .leaf()` retained after an EACCES class-symbol read), reuse on a
same-epoch retry, and loss of a later EACCES behind the proposed ledger's first
EOF. Its synthetic memory is not live JVM metadata; the wrapper is copied from
the plan, not a production epoch implementation. The source audit also corrected
the plan's read-address bias rule: `RemoteMemory.Bias` normalizes pointer values;
it does not relocate `ReadAt` offsets.

## Goal and supported contract

Prevent the reproduced wrong-generation Java attribution after class unloading.
Use the JVM's safepoint counter as a conservative metadata-lifetime boundary,
carried from capture through producer references, symbolization and structured
lookup. Preserve useful attribution within a validated boundary.

Initial supported configuration is Linux x86-64, digest-pinned Temurin
`25.0.4.1+1-LTS`, interpreter-only execution (`UseCompiler=false`), Serial GC
(`UseSerialGC=true`), and the existing ordinary-user, agentless fixtures. Runtime
flags must be read from the live JVM; command-line intent alone is insufficient.
The target must retain its process incarnation and verified JVM mapping.

The identity label is `safepoint_epoch_checked`, qualified by profile
`temurin25_xint_serial`. It means that interpreted metadata was decoded while
the same stable even epoch was observed at capture and around decoding. It is
not a unique class-loader ID, a class-allocation generation, semantic BCI proof,
or evidence of a complete stack walk or collection.

Concurrent collectors, compiled-method/nmethod reclamation, class redefinition,
other JDK builds, other architectures and target exec are outside this first
supported profile. Explicit epoch mode refuses unsupported startup conditions;
it never falls back to pointer-only attribution. Existing experimental capture
without epoch mode remains labelled as lacking this lifetime guarantee.

## Source and binary evidence

The failing normal control observed all 1,600 operation tokens and 80 unloaded
classes, with 20 correct segments, 20 unavailable and 1,560 wrong-generation
events. Four diagnostic references first published in generation one were reused
through generation seventy-nine. The producer returned from its raw-stack cache
hit without publishing a definition, bypassing method validation.

Exact pinned JVM inspection found:

- Image: `eclipse-temurin@sha256:119a3d18f160a3e7655a66034d0f43beee31cd7b3b9142d57a5de29772011de6`.
- Source: `https://github.com/adoptium/jdk25u.git`, commit `520406d87195`.
- `libjvm.so` SHA-256: `5e0fb6e83a5676090f28ff0b453e9199df06af1ff90d473c1b9837e41096114c`.
- ELF build ID: `e4dad22744e32e4d71755f4a58c5cf1f1d336f87`.
- Local ELF symbol `SafepointSynchronize::_safepoint_counter`, symbol value
  `0x17fa938`. This value is not an ASLR-adjusted process address.
- Local symbols `UseSerialGC` and `UseCompiler` are available in this binary.

At that source commit, `safepoint.hpp` declares a volatile 64-bit counter: even
outside a safepoint, incremented only at safepoint entry and exit.
`arm_safepoint` publishes the odd value before synchronizing Java threads;
`disarm_safepoint` publishes the next even value before restarting them.
`SerialHeap::do_full_collection` calls
`ClassLoaderDataGraph::purge(/*at_safepoint*/true)` before the safepoint ends.

The normal provider also has address-keyed method, Symbol and JIT-info caches.
Invalidating method records alone leaves `addrToSymbol` capable of returning a
stale class/method/signature string from a recycled Symbol address.

## Global constraints

- Work in `/home/leanid/Documents/code/java/jseccomp`, branch `fast-master`; no worktrees or delegation. Preserve unrelated user edits.
- Keep MazeWall Tier E, C++ agent, InvocationId protocol, task storage, session APIs and USER_NOTIF production implementation unchanged.
- Gadget base: `7097db144ccd8dab15373f27ec98d2c3d22e4588`; profiler base: `ef6cf29492c8a980f889052903fa2dd0c0765cc0`. Retain dependency and image pins; add no dependencies.
- Author scoped patch files and source additions; apply without fuzz to checksum-verified ignored `build/deps/` sources. Never edit the module cache or vendor a profiler tree.
- Reuse producer LRU, correlation cache, waiters, pruning, process discovery, unwinders and enrichment workers. No replacement cache, retry queue or historical metadata side store.
- Preserve producer LRU capacity 256, frame-cache capacity 16384, frame data bound 3072 words, perf data buffer 524288 bytes/CPU, four workers, 1024 pending events, 16 MiB pending payload budget and one-second BOOTTIME enrichment deadline.
- Preserve exact operation-token, branch, frame-order, wrong-generation, pruning and recovery assertions. Observed zero loss remains partial evidence.
- Strict epoch sessions have a maximum lifetime of 60 seconds. Refuse counter values in the upper half of uint64; detected live regression poisons the session instead of establishing a new baseline.
- No direct push to the default branch or upstream submission. Keep the identity issue open until evidence and review support closure.

## Architecture and data flow

### 1. Read-only profile discovery

Add one small experiment-owned identity package. From the target's actual maps
and mapped ELF, verify the locked SHA-256/build ID and locate the counter and
runtime flag symbols. Use ELF PT_LOAD segments and mapping file offsets to
calculate load bias, with checked address arithmetic. Verify the counter lies
in readable mapped memory, is eight-byte aligned and has an eight-byte symbol
size; flag symbols have one-byte bool storage in the verified build.

Read `UseSerialGC` and `UseCompiler` through checked remote reads. Read process
start ticks before and after discovery, retain the existing pidfd and revalidate
the mapping before enabling strict capture. A deleted/replaced/missing mapping,
ambiguous bias, changed birth time, bad flag value or unsupported build is a
startup refusal. EPERM/EACCES and short reads retain their original errors.

Pass the verified target configuration before starting the provider's PID event
processor or exposing the capture attachment. One immutable target descriptor
contains PID, process start ticks, counter address, JVM identity and initial
observed counter. It is configuration, not another metadata cache.

### 2. Versioned capture evidence

Define a value-only provider record:

```go
type HotSpotEpochState uint32
// Numeric C/Go contract: unknown=0, stable=1, read_error=2,
// in_progress=3, changed=4, unsupported=5, regressed=6.
type HotSpotIdentity struct {
    Epoch uint64
    State HotSpotEpochState
    ReadError int32
}
```

Zero epoch is valid when state is `stable`; zero state is unknown.
Never encode read failure as a stable zero epoch.

Add the corresponding 16-byte C record to the trace header before its trailing
`frame_data`, and to the producer `TraceCache` key after PID/TID. Append the
verified counter address and initial counter to `HotspotProcInfo`. Keep the 3072-word frame array and
existing frame formats unchanged. Regenerate Go layouts and embedded BPF objects
together. Record ABI version 2 in the preparation manifest.
Strict startup checks the actually loaded producer-key/proc-info layouts and
trace-header BTF against the generated Go contract before attachment, so old
embedded bytecode cannot be accepted merely because the Go source was updated.

Reset new per-CPU fields at every entry. In epoch-enabled HotSpot capture, read
the counter before the first Java metadata access and again before producer
cache lookup/publication. Require equal even values in the lower half of uint64
and no value below the configured initial counter. Failed/odd/changing evidence
must not reuse a valid cached reference. Leave the caller's already-reset
correlation return at zero and preserve an explicit event-side status.

The gadget independently brackets the entire stack-helper call with counter
reads using the verified address. Event fields carry epoch, state and read error
alongside PID/TID/reference. A transition outside the provider's narrower reads
is therefore still rejected by event/definition agreement. No extra map or
changes to `generic_params`/the Gadget user-stack ABI are needed.

### 3. Producer-reference isolation

The existing exact producer key becomes `(PID, TID, identity, raw frames)`.
Stable captures at the same epoch retain reference reuse. A new epoch yields a
different key and definition even if every Method pointer and cookie repeats.
The value remains the existing uint64 reference and capacity remains 256.

Update recovery's explicit key-size check and byte decoding to ABI version 2.
Require matching epoch as well as PID/TID/reference before invalidation in strict
mode. Retain complete-scan, entry-bound, cancellation and late-definition rules.
Pruning still operates on reference values and retains its two-cycle grace.

### 4. Userspace lifetime guard

Add an optional interpreter capability; do not change the existing `Instance`
interface or `Symbolize` signature. Configure the target HotSpot instance before
its process data is installed. The process manager invokes its begin guard before
any frame-cache lookup and its end guard after all HotSpot decoding.

The begin guard uses checked reads to require live counter == captured stable
counter. Before accepting a new epoch, purge the existing `addrToMethod`,
`addrToSymbol` and `addrToJITInfo` LRUs and retain their capacities. Existing
static stub discovery remains separate; epoch-specific returned frame entries
cannot cross the boundary. Existing full ConstMethod/cookie checks remain active.

Include epoch in HotSpot frame-cache identity and its hash. Native frame-cache
keys retain their prior identity. Every strict trace must pass the begin guard
even when all its frames would be cache hits.

Use the existing output trace as tentative storage. Delay admission of newly
decoded HotSpot frame-cache entries until the end guard succeeds; a trace-local
list of key/segment indexes is bounded by validated raw-frame count (at most
3072 entries). It is discarded after handling one definition and is not another
stack cache. Account for this transient memory; do not claim unchanged overhead.

If either guard rejects, emit no named HotSpot frames from that definition,
including frames appended before the rejection. Preserve native/error evidence
and attach the typed rejection to the definition. Do not log-only the failure
and then publish tentative Java labels. Native errno/short-read errors are
carried to structured lookup through a typed error with its original cause (not
just an errno string) so unexpected/permission failures remain fatal to
the experiment rather than turning into a warning.

The existing `getSymbol` helper can return a null string on a remote-read error,
and `convertFrame` currently logs decoder errors before appending an unknown
placeholder. Strict decoding must preserve those causes too. The proposed shared
ReaderAt replacement and first-failure-only ledger in the plan are rejected by R1/R3:
the existing locks do not cover mapping synchronization, and a later permission
failure must not disappear behind an earlier error. Reader ownership and bounded
error retention must be revised before implementing this part of the design.
The strict HotSpot conversion helper also returns decoder errors to publication
instead of discarding them in the legacy bool-only conversion path. Neither
helper changes the public `Instance.Symbolize` interface.

Track the highest live counter successfully read in the configured instance;
lower live values and upper-half values poison the supported session. A delayed
old *capture* is `changed`, not a counter regression. Old raw definitions cannot
be decoded against new metadata, even if an old frame-cache entry remains.

### 5. Immutable definition and event agreement

Carry identity on `libpf.EbpfTrace`, `libpf.Trace`, Gadget `correlatedFrames` and
`StructuredStack`. The reporter can read `libpf.Trace` directly; no independent
identity dictionary or rendered-name parsing is needed.

Strict structured tasks include event-side identity. Require PID, TID,
reference and stable epoch agreement. Store validated definitions as immutable
snapshots. A later JVM safepoint does not invalidate an already validated old
definition for an event captured at its original epoch; no late re-reading of
replacement metadata is necessary.

An incoming duplicate reference with different epoch, PID/TID or frames must
not overwrite an existing strict definition. Surface identity conflict as an
error. Checked evidence additionally requires a nil identity/decoder error;
a stable state alone cannot erase a failed read. The existing timestamp-based
reference allocator is not promoted to a
proof of collision freedom.

If a definition was delayed until after a safepoint, store an explicit rejected
definition without Java labels and wake existing waiters. Missing definitions
remain governed by existing deadlines/recovery; rejected definitions are not
retried or rewritten into successful old results.

### 6. Operator-facing mode and output

Expose `--hotspot-identity=safepoint-epoch` on the experiment CLI; the default
remains `unknown` for existing captures. Reject strict mode combined with legacy
debug rendering. Strict startup verifies the supported profile rather than
assuming it from this flag.

Add optional `hotspot_identity` to event and enrichment output, containing
`state`, optional `epoch` (including valid zero), `profile` and optional read
error. Accepted evidence uses state `safepoint_epoch_checked`; unresolved
definitions and capture/guard failures keep explicit reasons such as
`hotspot_epoch_in_progress`, `hotspot_epoch_changed` and
`hotspot_epoch_unavailable`. Regressions, identity conflicts and propagated
EPERM/EACCES are fatal. Existing JSONL event IDs and terminal accounting remain
unchanged. Old consumers can ignore additive fields; do not claim compatibility
with old binary BPF layouts.

## Alternatives and trade-offs

1. **Safepoint epochs, selected:** direct lifetime evidence for the reproduced
   interpreter/Serial case; preserves same-epoch cache reuse. More definitions
   after unrelated safepoints and more unavailable delayed definitions are
   expected. The new evidence is deliberately profile-specific.
2. **Full pointers/content fingerprints:** fewer layout/lifecycle changes and
   better mismatch detection, but no proof against same-address reuse or
   replacement before deferred reads. Insufficient as the selected remedy.
3. **Lifecycle uprobes:** potentially broader collector/reclamation support;
   requires complete hook coverage, exact-build verification and attachment-race
   handling. A separate milestone after this bounded acceptance gate.

## Verification and acceptance

- Unit-test zero, odd, changed, unavailable, malformed-state, short-read,
  permission, lower-counter and upper-half cases. Preserve underlying errors.
- Exercise the real process-manager frame-cache hit path: begin validation runs,
  differing epochs never share Java labels, and failed end validation admits no
  tentative frame-cache entries or named Java frames.
- Seed all existing address-keyed metadata LRUs and prove epoch transitions purge
  method, Symbol and JIT records; do not mock only the cache-reset decision.
- Prove event/definition epoch agreement, immutable duplicates, late definitions,
  historical validated reuse, waiter cancellation and strict recovery identity.
- Compare regenerated C layout, Go offsets and loaded BTF map/header layouts.
  Test actual producer same-epoch reuse and cross-epoch separation, not merely
  a Go reimplementation of the key.
- Keep the existing red normal unloading control. Run the strict normal collector
  without raw diagnostic instrumentation in three fresh JVMs. Each run must see
  all 1600 tokens, confirm all 80 unloaded generations and report zero wrong
  labels. In each run, at least one correct leaf/root segment for both branches
  is required in every generation 1–79; generation zero may remain cold-start
  unavailable. This minimum detects all-unavailable and late-generation failure
  without inventing a completeness claim.
- Require epoch-labelled producer-reference separation across confirmed unloading
  boundaries, and retain per-generation availability counts and rejection reasons.
  Safepoint logs are validation evidence, never production generation input.
- A separate controlled decoder-delay test must reject an old definition after a
  boundary. An already validated historical definition must remain resolvable.
- Run focused race/ABI/native-cache/recovery gates, `make verify`, backlog checks
  and `./gradlew build` once after focused checks pass. No Seccomp/Landlock behavior
  is changed, so repository kernel-containment suites are not required here.

## Rollout boundary

All changes remain in the standalone experiment and its reproducible dependency
patches. Publish run-specific evidence and actual hashes before claiming the
Serial/interpreter unloading gate passes. Keep broader unloading/JIT identity
acceptance open. Implementation must stop for design revision if exact-layout
regeneration, live profile verification or useful later-generation resolution
cannot meet this contract.
