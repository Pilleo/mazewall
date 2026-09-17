# Tier E — eBPF Semantic Enrichment: Technical Design Document

**Status:** EXPERIMENTAL IMPLEMENTATION (InvocationId stack backend and correctness hardening, 2026-09-13)
**Component:** `:platform`, `:profiler`, `profiler-native/`
**Related:** [profiler-design.md](profiler-design.md) (Tiers P/S/A), [security-considerations.md](../core/security-considerations.md) (Shared-Memory ACE threat model)
**Work packages:** [docs/internals/backlog/implementation/issue-20260825-*tier-e*.md](../../backlog/implementation/)

---

## 1. Summary

> **Status: Implemented contract.**

Tier E adds a **semantic attribution plane** to mazewall profiling. Application code declares
*why* a thread is executing (`PDF_PARSE`, `STRIPE_CLIENT`, …); an eBPF uprobe records that
declaration into BPF task-local storage; a `sys_enter` tracepoint program reads it back at
syscall entry and emits attributed events. The result is a per-syscall answer to
*"which application-level operation caused this?"* — with **no JVM suspension, no
USER_NOTIF round-trips, and no per-syscall Java work**.

### 1.1 InvocationId stack backend (supersedes ContextId)

> **Status: Implemented contract.**

The implementation no longer treats an application label as the canonical attribution. A JVMTI
native-method proxy synchronously captures the current logical Java stack, interns it, allocates a
session-qualified `InvocationId`, and calls:

```c
mazewall_stack_marker(uint64_t invocation_id, uint32_t flags)
```

The marker uprobe writes `{invocation_id, sequence, flags}` to BTF-typed task storage. The raw
`sys_enter` program reads that exact task's value and emits an 88-byte record containing kernel
time, TGID/TID, syscall number, six arguments, invocation ID, sequence, and flags. Userspace joins
the event to the agent dictionary by ID only. Timestamp-nearest correlation and reuse of a previous
stack are forbidden.

Both system-wide BPF entry points compare the current TGID with the immutable session target
before reading or writing task storage. The dynamic uprobe PMU uses libbpf's single global event
form (CPU 0, PID -1); experimentally, opening the local trace-uprobe event on every CPU duplicated
every hit. TGID filtering in the BPF program rejects other processes even if they map the same
agent library and use the same session tag. The kernel gate pins the target away from CPU 0 to
verify that this dynamic-probe form still observes migrated execution.

The raw tracepoint evaluates every selected `sys_enter` event made by the target after attachment,
but only reserves a ring record for a validated active invocation. Syscalls outside a native
boundary are counted in a bounded kernel `ambient_inactive` diagnostic rather than being emitted
as fabricated `InvocationId=0` records. This prevents ordinary JVM background activity from
consuming ring capacity or being misclassified as an attribution failure. A missing task pointer,
ring loss, invalid marker update, capture failure, or lifecycle inconsistency remains fail-closed
and makes the evidence incomplete. A declared workload must supply an independent denominator to
distinguish a missing expected attributed syscall from ordinary ambient activity; zero ambient
count is not claimed as complete syscall-attempt coverage.

The JVM API is doing the stack walk, not eBPF. Consequently inlined Java calls are represented by
the logical JVMTI stack rather than a native PC-only unwind. A bounded 129-frame request detects
depth truncation explicitly; capture failure, truncation, argument-read failure, ring loss, marker
failure, scope overflow, and unresolved dictionary IDs all make session integrity incomplete.

The current native interception manifest is HotSpot/OpenJDK-specific because it proxies exact
internal JDK native method names and ABIs. The stack/dictionary protocol and Kotlin session model
are JVM-neutral JVMTI concepts. Supporting OpenJ9 requires a separately tested binding manifest;
silently applying HotSpot signatures to another JVM would be memory-unsafe. The current eBPF
register layouts and syscall numbers are x86_64-only. AArch64 requires a separately verified
program builder before it can be claimed supported.

Virtual threads are first-class Tier E executions on supported JVMs (validated on JDK 25): the
agent obtains the current `jthread`, reads `Thread.isVirtual()` and `Thread.threadId()`, and assigns an opaque,
session-local `ExecutionId`. Every resolved syscall retains both the carrier `TaskIdentity` from
eBPF and the logical `ExecutionContext { ExecutionId, VIRTUAL }` from the exact native boundary;
thread names and raw Java thread IDs are never exported. The JVMTI virtual-thread-end callback
retires the private ID lookup, so a later JVM reuse of a Java thread ID cannot reuse an old logical
execution identity.

The proxy is intentionally exact and therefore pins a virtual thread during the native interval.
Tier E reports the count and total duration of those intervals in `SessionIntegrity`; they are
overhead telemetry, not an attribution shortcut. There is no portable JVMTI mount/unmount event
stream on which to base a non-pinning carrier-to-virtual-thread join. A different current logical
execution observed in a carrier-local nested scope clears attribution and makes the session
incomplete rather than restoring a stale context.

The agent-definition protocol is version 2. Its reader accepts version 1 artifacts for offline
compatibility, but a version 1 event marked virtual has no execution identity and is terminally
`EXECUTION_IDENTITY_UNRESOLVED`, never a resolved virtual attribution.

### 1.1.1 Stack-walk API status and adoption rule

> **Status: Implemented contract.**

`JVMTI GetStackTrace` is the current exact-stack backend. It is a supported JVM TI operation that
returns logical `{jmethodID, BCI}` frames, including the JVM's view of inlined calls; it is safe to
call synchronously from the native binding proxy, but it is not a low-latency API. Method/class
names and descriptors are resolved lazily outside the exact key where possible.

The following are **not** replacements in the current implementation:

* Java `StackWalker` is a current-thread Java API. Calling it from a JNI callback adds a native to
  Java transition and Java frame/object materialization; it does not provide a lower-level,
  syscall-correlated fast path.
* `AsyncGetCallTrace` is an exported HotSpot-private symbol, not a supported JVM TI API. Its
  historical failure modes rule it out for an attribution backend that must report unknown rather
  than attach a wrong stack.
* JDK 25's experimental JFR CPU-time profiler can collect asynchronous sample stacks, but sampling
  is not a one-for-one syscall attribution protocol and cannot replace the explicit
  `InvocationId` handoff.

JEP 435's proposed asynchronous stack-trace API is the intended future direction, but it has not
become a released API. As of 2026-01 the OpenJDK work is explicitly a proof of concept: one design
delivers the result as an asynchronous JFR event with caller `user_data`, and another uses JVMTI
callbacks. Tier E must not depend on either design until it is released and has passed the Tier E
differential suite. At that point, add it as an optional HotSpot backend behind the existing
dictionary model; require zero wrong pairings across compilation, deoptimization, class unloading,
and virtual-thread tests before changing the default.

References: [JVM TI stack-frame specification](https://docs.oracle.com/en/java/javase/25/docs/specs/jvmti.html),
[StackWalker API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/StackWalker.html),
and [OpenJDK asynchronous stack-walker RFC](https://www.mail-archive.com/serviceability-dev@openjdk.org/msg26652.html).

Definitions are persisted when the target JVM unloads the agent by default. This keeps dictionary
I/O off attributed target threads and permits delayed resolution, but a crash leaves IDs unresolved
and therefore makes the run incomplete. `TierEProfilerSession` also supports an opt-in
`DictionaryBatchSink` for bounded asynchronous publication while preserving the same binary records
and fail-incomplete semantics; the file artifact remains the compatibility and recovery boundary.

The optional `DictionaryBatchSink` is that bounded asynchronous-channel boundary. The encoder keeps
definitions in its bounded queue until a sink returns `Committed`; `RetryableFailure` rolls back the
same batch and preserves byte order and watermark, while `TerminalFailure` and any uncommitted tail
make transport incomplete. Only one batch may be prepared at a time, and `drainEncoded()` remains
the immediate-commit compatibility adapter. The existing unload-time file remains the default source
of definitions, so introducing the sink does not change the native agent wire format.

The privileged differential acceptance gate runs the same timed file-workload body through Tier E
and the existing USER_NOTIF stack oracle. It requires `wrong=0`, `lost=0`, at least five resolved
workload syscalls per iteration, and fails unless Tier E is faster. On the 2026-09-06 Docker gate
(100 iterations), Tier E took 110,216,326 ns and USER_NOTIF took 308,617,796 ns (2.80x speedup).
These numbers are evidence for that environment, not a portable performance guarantee.

### 1.2 No-profiler control measurement

> **Status: Historical evidence.**

The Tier E-vs-USER_NOTIF comparison alone is not a measure of application overhead.  On
2026-09-06, the same file workload (1,000 write/read iterations, JDK 25 Graal, privileged
Docker with host PID namespace, target pinned to CPU 11) was measured from immediately before
the workload body to immediately after it.  JVM launch and Tier E attach time were excluded.

| Mode | Samples | Median workload time | Change from no profiler |
|---|---:|---:|---:|
| No profiler | 7 | 405.7 ms | baseline |
| Tier E (JVMTI native binding + uprobe + eBPF) | 7 | 839.6 ms | +107% (2.07x) |
| Tier S (`USER_NOTIF` oracle) | 3 | 3,158.5 ms | +679% (7.79x) |

All Tier E samples had `wrong=0` and `lost=0`; they produced 5,014 resolved observations per
run.  Tier E was 3.76x faster than the median USER_NOTIF sample, but it is **not low-overhead
yet** for syscall-dense work.  The Tier E samples ranged from 670.4 ms to 1,847.1 ms, so these
are diagnostic host measurements rather than a performance commitment.  The remaining work in
WP-12 is still required before making service-level throughput or latency claims.

### 1.3 Performance roadmap

The following order preserves Tier E's core guarantee: no observation may be assigned a stack
unless that exact logical stack has been proven at the corresponding native boundary.

1. **[Implemented] Reuse exact raw-stack keys in full-stream mode.** After every synchronous `GetStackTrace`, a
   stable `(jmethodID, BCI)[]` key can reuse an existing `StackTraceId` while the session still
   allocates a fresh `InvocationId` and emits every syscall. This removes repeated method-name,
   descriptor, and class-signature resolution without changing full-stream cardinality. Cache
   entries must retain weak class guards and be invalidated after class unloading.
2. **[Implemented baseline] Make the unavoidable capture path cheaper.** Reuse native frame and
   raw-key buffers per thread where the current proxy path permits. This reduces agent-side
   allocation, hashing, and locking, but cannot remove the HotSpot logical stack walk that proves
   the key. Allocation-free warmed hits and broader scratch-buffer reuse remain measured targets.
3. **[Required hardening] Offer an explicit reduced-payload coverage mode.** A coverage-only report may request only a
   syscall number and canonical stack edge, avoiding six-register argument capture where callers
   do not need representative arguments. It must not claim a full stream. Unknown/unmarked
   activity must remain visible as an integrity failure, either as events or as a clearly reported
   aggregate counter.
4. **[Required hardening] Evaluate a lower-cost marker transport.** A USDT marker or another verified lower-overhead
   transport can reduce the four current marker transitions around each native scope. It must
   retain the same per-thread state transitions and binary/attachment verification as the uprobe
   path.
5. **[Required hardening] Evaluate scoped tracepoint attachment for node scale.** Per-platform-TID perf tracepoints
   could avoid running the system-wide raw-tracepoint filter for unrelated host processes. This is
   an operational-cost optimization only; it must correctly track JVM thread lifecycle and carrier
   threads before it can replace the current global attachment.

Rejected shortcuts: Java `StackWalker`, `AsyncGetCallTrace`, a timestamp join, a per-method shadow
stack, and skipping `GetStackTrace` based only on prior history. Each either adds cost, lacks a
supported correctness contract, or cannot prove that a repeated raw key represents the current
logical stack.

The end state is deliberately shaped for later reimplementation inside Kubescape's
node-agent:

```text
Java code                         Linux kernel / eBPF
─────────────────────────────     ────────────────────────────────────
MazewallContext.run(PDF_PARSE)
    │ FFM downcall
    ▼
mazewall_context_marker(42)       uprobe fires synchronously
                                  bpf_task_storage_set(current, 42)
    │
    ├─ Files.readString(...)
    ▼
openat("/tmp/in.pdf")             sys_enter raw tracepoint
                                  ctx = task_storage[current]   → 42
                                  ringbuf: { tid, openat, ctx=42 }
```

## 2. Position in the Tier Taxonomy

> **Status: Implemented contract.**

| Tier | Mechanism | Privilege | Output | Completeness |
|---|---|---|---|---|
| P | eBPF tracepoints / strace descendant | host root / rootful container | raw syscall stream | complete, unattributed |
| S | Out-of-process `USER_NOTIF` supervisor | unprivileged target | exact syscalls + JVM stacks | complete, slow, suspends tracee |
| A | Iterative deny-and-retry | zero privilege | Landlock paths + syscalls | converges over N runs |
| **E** | **JVMTI logical stack → InvocationId uprobe → task storage → `sys_enter`** | **privileged collector; target agent stays unprivileged** | **syscall + canonical Java stack ID** | **complete only when every explicit integrity counter is zero** |

Tier E does not replace policy-grade Tier S. It is a profiling/attribution backend: exact logical
stacks for covered native bindings, with explicit UNKNOWN/incomplete results outside that coverage.

## 2.3 Current implementation contracts (2026-09-13)

> **Status: Implemented contract.**

The current Tier E implementation is in `:profiler` under
`io.mazewall.profiler.tierE`; the historical `tier-e-proto` and `ebpf-prototype`
paths are not active modules. The following contracts are executable in the current
source tree:

- `TierEClientSession` applies a 30-second receive deadline after `accept()`. A
  `SocketTimeoutException` is an idle event that rechecks shutdown state; hard I/O
  errors still end the client session.
- `ProbeMain` owns `--probe`, `--probe-stdin`, and `--probe-cmdfile`. The production
  `TierEKotlinDaemon` entry point has no probe-only command modes.
- `TierEBpfProgramBuilder` reserves map slot 5 for a bounded LRU context metric map.
  Context counts are diagnostic snapshots exposed by `TierEbpfEngine` and formatted
  in deterministic context-id order; they never authorize or suppress attribution.
- `BpfRingBufferReader` maps only the metadata page as `PROT_READ|PROT_WRITE` and
  maps the data alias as `PROT_READ`. Its regression tests exercise BUSY, DISCARD,
  wraparound, acquire/release cursor movement, and concurrent reservations.
- `TierEProfilerSession` routes an opt-in `DictionaryBatchSink` through
  `prepareBatch()`, sink delivery, and commit/rollback; `drainEncoded()` remains
  the immediate-commit compatibility adapter. `DictionaryBatchPublisherTest`
  covers retry, reconnect, terminal failure, daemon crash, abandoned batches,
  and opaque batch identity.
- `TierEEndToEndIntegrationTest` runs the native agent and real session together
  and verifies a failed dictionary write is retried with identical bytes before
  terminal finish. `TierEDaemonNativeWorkflowIntegrationTest` covers rootful
  daemon attach, status, detach, and shutdown around an agent-loaded workload.
- `lifecycle_identity_stress_fixture` repeats fork/exec identity transitions;
  Kotlin `InvocationReplayMachineTest` covers detach, exec, PID reuse, sequence
  gaps, callback suppression, and validated re-entry. These fixtures reject stale
  identities before replay restoration.

## 2.4 Performance and lifetime evidence

The native agent's exact stack path is measured in three phases: `GetStackTrace`,
raw-key validation/cache lookup, and logical-frame metadata resolution. The optional
`benchmark-phases` agent option emits phase totals and cache hit/miss counts without
changing definitions. Logical metadata is immutable after insertion and keyed by
method identity plus symbolization epoch; every value carries a weak declaring-class
guard and is rejected when the class is unloaded. The cache is bounded at 4096 entries.
Class load, retransformation, and redefinition advance the epoch, preventing cross-version
method collapse. A live Java retransformation fixture and full-stream/unique-edge
warmed benchmark fixtures run in `profiler-native` CTest.

## 2.5 Protocol fixture contract

The committed JSON transcripts under `profiler/src/test/resources/protocol/` are the
wire compatibility boundary for the current framed control daemon. They replay through
`ControlProtocol` and `ControlSessionMachine` and compare the exact rendered reply bytes.
The fixtures cover successful attach/status/detach/shutdown, `BUSY`, `ALREADY_BOUND`,
`MARKER_BUILD_ID_UNREADABLE`, `NOT_MAPPED_IN_TARGET`, and terminal `STATE` failures.
Any future wire-visible change must update these fixtures in the same change.

## 2.6 Privileged fixture boundary

> **Status: Implemented contract.**

The native CTest suite includes the seccomp denied-attempt boundary fixture, which
reports `attempt_missed=1 observed_sys_enter=0 verdict=INCOMPLETE`. This is deliberately
not an exact Tier E observation: attempts denied before the selected `sys_enter` path
are outside the capture domain. Rootful Docker is authoritative for local privileged
BPF, seccomp, and runtime fixtures; CI retention is a separate delivery concern.
Rootless Podman remains an environment limitation and must be recorded as `INCOMPLETE`,
never converted into a passing kernel claim.

### 2.1 Optional unique-edge emission

> **Status: Implemented contract.**

`TierEEmissionMode.FULL_STREAM` is the default.  The opt-in
`UNIQUE_STACK_SYSCALL` mode still proves the complete logical stack at every proxied native
boundary using the JVMTI `(jmethodID, BCI)` sequence, but it reuses a canonical stack context
after an exact match. The kernel coverage map is diagnostic only: it reserves and submits every
ring observation. Collector-owned acknowledgement is the only suppression boundary, so a failed
downstream write leaves a later identical observation eligible for retry.

This mode is a unique-edge coverage report: arguments are representative of the first retained
observation, event counts are unavailable, and it must not claim `FULL_STREAM` completeness.
Unmarked syscalls, failed stack capture, argument-read failures, and ring loss remain explicit
incomplete evidence.  It is never an enforcement or policy-generation input.

The userspace collector treats a resolved unique edge as an **emission candidate**, not as an
already emitted record. The candidate has three legal states: `CANDIDATE` (eligible for output),
`RETRYABLE` (the downstream sink rejected or did not retain it), and `ACKNOWLEDGED` (the sink
confirmed retention). Only `ACKNOWLEDGED` edges enter the deduplication set; a failed first emit
therefore leaves a later identical observation eligible for retry. The Tier E session acknowledges
records only after retaining them in its immutable result, while direct collector integrations must
call the acknowledgement boundary after their sink succeeds.

The collector is organized as three independent responsibilities: an invocation/dictionary joiner
holds delayed observations by explicit invocation ID, an integrity accumulator owns loss and
completeness dimensions, and a unique-edge gate owns acknowledgement-based suppression. None of
these components performs timestamp matching or restores state from an older invocation.
Unique-edge candidates also carry an opaque, session-local `EmissionToken`; acknowledgement consumes
only the exact outstanding token (or the exact object returned by the compatibility API), so a
reconstructed, duplicated, or foreign record cannot commit dedupe state.

Dictionary transport has an independent transactional lifecycle: `PREPARED` batches remain owned
by the pending queue, `COMMITTED` batches are removed and advance the watermark, and
`ROLLED_BACK` batches remain byte-identical and retryable. Only one batch may be in flight; an
unresolved in-flight batch at session termination is incomplete evidence, never an implicit commit.
The legacy `drainEncoded()` adapter commits immediately for compatibility, while new sinks must
use prepare/commit/rollback to make write failure observable.

### 2.1.1 Stack-definition transport flavours

> **Status: Implemented contract.**

`TierEStackTransport.DICTIONARY` is the default. It writes raw `MZSD v2`: each immutable
canonical stack definition appears once and successful invocations reference its
`StackTraceId`. `INLINE_PER_INVOCATION` is an opt-in diagnostic transport. It writes `MZSD v3`
and repeats the full immutable stack payload beside each successful invocation. Failed captures
continue to use the ordinary invocation failure record, so they remain explicit and are never
fabricated as an inline exact stack.

The collector normalizes both transports into the same `StackDefinition` and
`InvocationDefinition` model before joining ring observations. `InvocationId` stays an execution
identity, never a canonical-stack or unique-edge identity. MZSD v1 and v2 remain readable; a v3
reader rejects an inline record whose embedded stack ID conflicts with the invocation reference.

The native `benchmark-phases` option reports capture phases plus `serialization_ns` and
`publication_bytes` for dictionary/full-stream, dictionary/unique-edge, inline/full-stream, and
inline/unique-edge runs. These are host-specific comparison measurements, not universal latency
guarantees or a replacement for the existing regression gate. USDT is not a transport flavour;
it remains a separately validated marker-backend experiment.

### 2.1.2 Trustworthy flavour benchmark contract

> **Status: Implemented contract.**

The rootful benchmark records a controlled-workload verdict separately from whole-session evidence.
`workload_correctness=PASS` requires the shared operation/checksum contract, an exact workload stack
and controlled unlink relationship for Tier E, no wrong exact workload stack, no ring loss and no
unexplained resolution status. Background `NO_ACTIVE_INVOCATION` records are retained as an explicit
count and make whole-session evidence `INCOMPLETE` when applicable; they do not silently convert
into either a pass or a false workload failure. Every result records `policy_eligibility=false`.

In unique-edge mode, missing resolved records are not treated as suppression. The collector emits
benchmark-only audit decisions for `CANDIDATE`, `ACKNOWLEDGED`, and `SUPPRESSED`; every declared
operation must have exactly one explicit terminal `ACKNOWLEDGED` or `SUPPRESSED` decision. A
candidate without acknowledgement is incomplete, and a missing decision is a missing operation.

Each run emits metadata, fork-start, fork-output, batch samples and an explicit terminal record.
Phase totals belong to the fork record, while samples contain only batch workload duration. The
benchmark rotates fork order, pins workload and collector to distinct physical cores, and supports
the full baseline/Tier E/USER_NOTIF comparison, paired cache comparison, and terminal-versus-live
publication comparison. Artifacts retain source revision, dirty state, JDK, OS, affinity, timeout,
raw child output, publication and retained-state counters.
The rootful runner additionally retains a JSON manifest with the source dirty-tree fingerprint,
native-agent checksum, container image ID, kernel, CPU topology, arguments, deadlines, and raw-log
paths. It compiles as the local user, then performs privileged measurement from a read-only source
mount with writable scratch space only.

### 2.1.3 JDK-qualified capture-profile admission

> **Status: Implemented contract and historical rootful evidence.**

`full` remains the default exact-capture profile and is represented by omitting a
`capture-profile` native-agent option. The only narrowed profile is
`jdk25-unix-openat0`; it is benchmark-only and admits exact capture solely for the JDK 25
`sun.nio.fs.UnixNativeDispatcher.openat0` binding. The agent installs proxy bindings during startup
but keeps narrowed capture admission closed until `VM_INIT` confirms
`java.specification.version == "25"` through a profiler-owned JNI lookup. A failed validation leaves
the narrowed binding excluded rather than fabricating an exact stack or restoring a prior context.

Paired profile statistics include a fork only when both the full and narrowed arms report
`workload_correctness=PASS`, complete whole-session evidence, and valid admitted-capture joins.
Excluded pairs are retained with machine-readable reasons; they never contribute to a median or
confidence interval. Expected exclusions of bindings outside the narrowed profile do not count as
missing dictionary definitions: validity requires every **admitted** capture to join exactly, with no
capture/admission failure, outcome mismatch, or unrecorded definition.

The retained rootful artifact
[`tier-e-capture-profile-20260917.jsonl`](../../evidence/tier-e-capture-profile-20260917.jsonl)
and adjacent [manifest](../../evidence/tier-e-capture-profile-20260917.manifest.json) were produced
at clean commit `051be6ab61530864e702c4e4bd3c8fc9968137b1` on kernel `7.2.4-x64v3-xanmod1`, JDK
`25.0.4`, and pinned CPUs `0,2`. Five rotated forks with three warm-ups and ten measured batches
each had `PASS`, complete Tier E evidence, valid admitted captures, zero loss, and zero attribution
failures for both arms. The baseline was independently workload-verified and correctly marked
`BASELINE_VERIFIED` / `NOT_APPLICABLE` for Tier E session evidence. The paired full-minus-narrowed
median was `221,849 ns` per 100-operation batch (bootstrap 95% interval `15,981..767,531 ns`), so
this workload shows a positive narrowed-profile saving. It is host- and binding-domain-specific
evidence, not a general Tier E performance guarantee or a policy decision.

The existing snapshot transport remains the default. Incremental dictionary transport is deferred
until stack-diverse, class-churn and long-session live-publication measurements prove that repeated
snapshot serialization is a material cost. The decision record
`issue-20260917-000001-defer-incremental-tier-e-dictionary-publication.md` defines the evidence
required before that protocol work starts.

The retained rootful paired-cache artifact `tier-e-cache-paired-20260917.jsonl` validates the
corrected benchmark contract across 450 samples. Cache-enabled medians were lower for all four
Tier E flavours and all five-pair bootstrap intervals excluded zero. Dictionary/full-stream with
canonical cache reuse remains the performance default; the report keeps the host/workload limits
of that conclusion explicit.

`tier-e-publication-benchmark-20260917.jsonl` validates terminal and 100 ms live snapshot
publication for all dictionary and inline flavours. Its first rotated run has mixed median deltas;
it demonstrates publication behavior and retained phase metrics, but it does not yet justify a
publication-mode change or incremental dictionary transport.

The corrected full rootful comparison is retained as
`docs/internals/evidence/tier-e-full-matrix-20260917.jsonl`, with its provenance in the adjacent
manifest and its interpretation in
`docs/internals/evidence/tier-e-flavour-and-cache-report-20260917.md`. It contains five rotated
forks and 350 measured batches. On this native-call-heavy oracle workload, dictionary/full-stream
had the lowest Tier E median at 1.854 ms per 100-operation batch; inline and unique-edge showed no
demonstrated speed advantage. Paired cache A/B measurements showed a positive saving for every
Tier E flavour, so canonical cache reuse remains enabled. USER_NOTIF remained roughly three orders
of magnitude slower. These are host- and workload-specific observations, not universal overhead
guarantees or a policy decision.

The bounded rootful USER_NOTIF latency diagnostic retained in
`docs/internals/evidence/tier-e-user-notif-latency-20260917.md` found that the earlier 21 ms
per-operation result came from delayed admission of completed off-reactor preparation, not JVM
stack retrieval. A temporary 1 ms poll bound reduced the observed median from 21.51 ms to
3.56 ms in the matched no-stack diagnostic. The stack-enabled counterpart was 4.07 ms and
measured 40.5 microseconds in listener-side stack capture.

The implemented reactor contract now uses one session-owned nonblocking `eventfd` as a third poll
descriptor. A preparation future signals it after completing; the reactor drains the counter and
alone advances the notification to its single reply lease. Completing preparation also immediately
drives the newly armed control exchange through its first nonblocking send before waiting for the
controller acknowledgement; otherwise the consumed wakeup would leave that send dormant until the
deadline. Pending work therefore waits for an actual descriptor readiness event or its deadline,
never a fixed completion-poll interval. Worker threads cannot send kernel replies. The rootful
five-fork measurement retained in
`docs/internals/evidence/tier-e-eventfd-full-matrix-20260917.md` validates the corrected path:
every Tier E variant had a complete, oracle-matched run; USER_NOTIF matched the same controlled
operations but retained bounded-observation loss as incomplete whole-session evidence. Its measured
cost is dominated by profiling lifecycle and broad notification work, not the narrowly measured
stack-capture phase. The earlier one-fork figures remain historical diagnosis only.

Capture-domain limitations are recorded separately from observed events. A denied or otherwise
out-of-domain syscall attempt increments `outOfDomainAttempts` and makes the session incomplete;
it is never converted into a fabricated exact `sys_enter` observation.
The machine-readable classifications are `observed_sys_enter` and `attempt_missed`. Final evidence
exposes separate verdict dimensions for capture-domain validity, transport completeness, attribution
coverage, dictionary integrity, and lifecycle validity.

### 2.2 Capture-domain and attribution state machine

Tier E is a **capture-domain contract**, not an all-syscall attempt oracle.

- Capture input: records that reach the selected kernel path `raw_tp/sys_enter` while the session is
  RUNNING and TGID-aliased to the target process.
- Explicit blind spots: any syscall-attempt that never reaches this path (including blocked seccomp
  attempts in `RAW_ADVISING`/seccomp-denied windows) is not recorded as an exact attributed event.
  These attempts must emit boundary-limitation metadata (`attempt_missed`) rather than pretend coverage.
- Session boundary states are explicit and terminally safe:

```text
RUNNING_SESSION
  ├─ marker_enter (supported path)            ──► EXACT_ACTIVE
  ├─ unsupported wrapper→java callback start     ──► INACTIVE_FOR_CALLBACK
  ├─ callback→java→supported re-entry validation  ──► EXACT_REENTRY
  ├─ marker_exit / detach / callback end          ──► RUNNING_SESSION
  ├─ supported capture failure                    ──► INACTIVE_FOR_CALLBACK
  ├─ detach while callback active                  ──► INACTIVE_FOR_CALLBACK (clear state)
  └─ session TERMINATED                            ──► DEAD
```

`INACTIVE_FOR_CALLBACK` suppresses exact attribution and marks evidence as `UNKNOWN`
(`causal-uncertain` when classification is inherited from in-flight unsupported transition).
`INACTIVE_FOR_CALLBACK` is entered before any callback-driven JVM work and must not restore a prior exact
invocation until a validated re-entry boundary is observed.

## 2.7 Retained implementation evidence

> **Status: Historical evidence.**

The current hardening evidence is indexed by
[`tier-e-architecture-hardening-20260913.json`](../../evidence/tier-e-architecture-hardening-20260913.json).
The final local privileged run is retained in
[`tier-e-rootful-final-gate-20260913.json`](../../evidence/tier-e-rootful-final-gate-20260913.json)
and records commit `50e148591179f94e58012c8a714e05057342068f`, the supported kernel and
container image, both native-backed integration reports, benchmark gating, and all six
verdict dimensions. The lifecycle stress fixture and transport fault-injection details are
retained in [`tier-e-lifecycle-identity-stress-20260913.json`](../../evidence/tier-e-lifecycle-identity-stress-20260913.json)
and [`tier-e-transport-fault-injection-20260913.json`](../../evidence/tier-e-transport-fault-injection-20260913.json).
Rootless Podman remains explicitly `INCOMPLETE` and is not used to support a privileged claim.

## 3. Invariants (non-negotiable, enforced by review and where possible by types)

> **Status: Implemented contract.**

1. **Profiling/enrichment/detection hints only.** Never an enforcement input.
   Enforcement remains seccomp/Landlock + `USER_NOTIF`/`ADDFD`.
2. **Stack metadata is tracee-controlled and therefore forgeable.** Invocation and dictionary
   records are `UNTRUSTED ATTRIBUTION METADATA` and are never policy inputs.
3. **Fail UNKNOWN, never guess.** Missing registration, storage-create failure, ring-buffer
   drop, pre-attach window, or detach/invalid-context transition — every uncertain case
   yields `UNKNOWN`, never "nearest context" or timestamp inference.
4. **Virtual-thread ownership is explicit.** A resolved Tier E event carries an opaque logical
   execution identity separate from its carrier task. A mismatch fails unknown; no seccomp filter
   is installed on a carrier.
5. **One BPF map set per session epoch.** Maps are never recycled across sessions. Task
   local storage is scoped per-map instance, so a fresh session observes no stale values.
6. **No bpffs pinning in v1.** The daemon owns all map/link FDs. Daemon death detaches
   everything.
7. **Session death is terminal.** `RUNNING → DEAD` only; no reconnect-and-splice. A new
   connection is a new epoch with a fresh map set.
8. **No policy generation from Tier E.** `toPolicy()` must not exist. Ring-buffer drops make
   the stream incomplete by construction; BoB compilation remains USER_NOTIF-oracle-only.
   The existing `ProfileCollector` KDoc ("Implementations must not compile policies
   themselves") already encodes this.
9. **Ring-buffer loss marks observations incomplete.** A per-syscall drop counter is
   mandatory; any drop ⇒ `drainComplete = false` in the collector drain.
10. **Capture-domain separation.** Syscall-attempt coverage is explicit to the `sys_enter` observed
    path; denied attempts can only be surfaced as boundary limitation metadata, not exact events.
11. **Attribution lifecycle safety.** Once boundary state leaves exact attribution (`INACTIVE_FOR_CALLBACK`
    or `DEAD`), exact attribution must not resume via cache reuse, stale context memory, or process-identity
    overlap.

## 4. Historical architecture (superseded)

> **Status: Implemented contract.**

> The ContextId/USDT material below records earlier design iterations. For the implemented stack
> backend, substitute `InvocationId` for `ContextId`, `mazewall_stack_marker` for the context probe,
> and the native JVMTI dictionary described in §1.1. The current implementation uses a plain symbol
> uprobe by default because its dependency-free Kotlin loader resolves the ELF dynamic symbol directly.

### 4.1 The handoff primitive

> **Status: Implemented contract.**

The context transition itself is made observable to eBPF via a synchronous native call:

```text
libmazewall_context.so
    void mazewall_context_marker(uint64_t invocation_id) { /* intentionally empty */ }
```

Native raw-stack cache identity includes a monotonic symbolization epoch advanced by JVMTI class
load/retransform/redefinition notifications. Weak class guards remove entries after unload, and the
epoch is included in both canonical lookup and logical-stack interning. This prevents a redefined
method from collapsing into an older stack while keeping the v2 definition record unchanged; all
symbol names and frames are copied into immutable dictionary records before the weak guard can expire.

* Java calls it through an FFM downcall from inside `MazewallContext.withContext { }`
  (enter) and its `finally` block (restore).
* An eBPF uprobe attached at the exported symbol's file offset fires **synchronously on the
  calling Linux task** before the call returns (breakpoint trap runs the BPF program first).
* The BPF program writes the value into `BPF_MAP_TYPE_TASK_STORAGE` keyed on
  `bpf_get_current_task_btf()`.
* At `sys_enter`, a second program reads the same task's storage and emits an attributed
  ring-buffer record.

Because both programs run **on the same task**, and a task executes on exactly one CPU at
any instant, the write and all subsequent reads are serialized by construction. There is no
userspace/kernel memory-ordering proof to make — this is the property that eliminated the
previous mmap-slot design (§6).

### 4.1.1 Marker ABI: Plain uprobe (implemented) vs Mazewall USDT probe (planned)

> **Status: Required hardening.**

> **Decision (2026-08-25):** Tier E context transitions are designed to move to a Mazewall
> **USDT probe** long-term, with the underlying attribution mechanism remaining uprobe → BPF task
> storage. The current shipped implementation is still **uprobe-first**.

Planned production-facing interface:

```text
Provider: mazewall          Probe: context_switch(uint64_t invocation_id)

void mazewall_context_marker(uint64_t invocation_id) {
    DTRACE_PROBE1(mazewall, context_switch, invocation_id);   /* sys/sdt.h */
}
```

Rationale (full review in Appendix A):

* **The marker becomes an ABI described by the binary itself.** Probe address, provider,
  name, and argument locations live in `.note.stapsdt`; the daemon verifies
  `mazewall:context_switch` exists *in the exact library mapped by the target* before
  declaring a session RUNNING. Missing probe ⇒ loud `ATTACH_FAILED`, never silently
  everything-UNKNOWN (sharpens §11 risk 4).
* **Argument portability:** `bpf_usdt_readarg()` resolves argument location per arch/ABI,
  eliminating hand-written register access (§11 risk 7) in the USDT variant.
* **Single-probe semantics:** one `context_switch` probe carries full state
  (`task_storage[current] = invocation_id`). Java nesting logic already knows restore values;
  BPF needs no stack. Enter/exit probe pairs are explicitly rejected.
* **Extension vocabulary:** later probes (`async_submit(operationId, contextId, …)`) give
  the future async/io_uring extension a request-keyed storage shape without inventing a new
  bridge (Appendix A.3).
* **Not adopted for v1:** USDT semaphores ("is-enabled"). Our v1 arguments cost nothing to
  prepare. Revisit when probes carry expensive-to-build metadata.
* **FFM downcall is still required** — USDT does not remove the Java→native hop; G1
  benchmarking applies identically to both variants.

Build-order contract (WP-03): **G0a** proves the plain exported-symbol uprobe end-to-end;
**G0b** swaps attachment to `usdt/<path>:mazewall:context_switch` (libbpf ≥ 0.8) and must
reproduce G0a byte-for-byte; **G1** benchmarks both variants.

> **Sign-off (2026-08-25, operator):** the Kotlin control plane ships **uprobe-first**
> under this clause's escape hatch — G0a/G0b parity is already recorded in Appendix B —
> with the `.note.stapsdt` parser as a focused follow-up before USDT becomes the default
> attach mode there. Dependency impact
(ask-first rule): `systemtap-sdt-devel` (`sys/sdt.h`) enters the *prototype build toolchain*
only; libbpf remains prototype-only. WP-14 must either parse `.note.stapsdt` itself or
justify a plain-uprobe fallback with recorded parity evidence before shipping without libbpf.

### 4.2 Data model

> **Status: Implemented contract.**

A context is a positive 64-bit session-local identity (`io.mazewall.profiler.attribution.InvocationId`):

```text
0x00000000        UNKNOWN (fail-unknown sentinel)
0x00000001..      operator-assigned semantic scopes
```

Attribution provenance is recorded separately as `AttributionKind`:
`NONE | EXPLICIT_CONTEXT | AGENT_CONTEXT | USER_NOTIF_ORACLE`.

Wire form everywhere (BTF struct fields, ring buffer records, future control protocol):
encoded as 8-byte little-endian values, plus explicit zero handling for inactive invocation state,
no varint, no nullable representation.

### 4.3 Kernel-side sketch

> **Status: Implemented contract.**

```c
struct context_event {
    __u64 ktime_ns;
    __u32 tgid;
    __u32 tid;
    __s32 syscall_nr;
    __u64 invocation_id;      /* InvocationId wire form */
};

struct {
    __uint(type, BPF_MAP_TYPE_TASK_STORAGE);
    __uint(map_flags, BPF_F_NO_PREALLOC);
    __type(key, int);
__type(value, __u64);
} context_storage SEC(".maps");

SEC("uprobe")                     /* attach: marker symbol offset */
int BPF_UPROBE(on_marker, __u64 invocation_id)
{
    __u64 v = invocation_id;
    bpf_task_storage_get(&context_storage,
                         (struct task_struct *)bpf_get_current_task_btf(),
                         &v, BPF_LOCAL_STORAGE_GET_F_CREATE);
    return 0;
}

SEC("raw_tp/sys_enter")
int BPF_PROG(on_sys_enter, struct pt_regs *regs, long id)
{
    /* TGID/cgroup filter first; then: */
    __u64 *ctx = bpf_task_storage_get(&context_storage,
                             (struct task_struct *)bpf_get_current_task_btf(),
                             NULL, 0);
    if (!ctx || *ctx == 0) { unknown_counter(id); return 0; }   /* invariant 3 */
    reserve ringbuf … commit;
}
```

Notes verified against kernel sources/docs (see §7): `sys_enter`'s second raw argument **is**
the syscall number, so no CO-RE dereference of `pt_regs->orig_ax` is required for v1;
arch-specific register access (`di` on x86_64, `x0` on arm64) is needed only inside the
uprobe program to read the marker argument when using classic (non-BTF) uprobe context —
compile-time per-arch selection.

### 4.4 Component layout

> **Status: Implemented contract.**

```text
ebpf-prototype/                 unwired until Gate G2 passes
├── bpf/mazewall_context.bpf.c  uprobe + sys_enter programs, maps, ringbuf
├── daemon/                     privileged C sidecar: load, attach, session control
└── client/                     throwaway test client (pre-FFM)

:platform                       io.mazewall.profiler.attribution.InvocationId, AttributionKind
                                ffi additions (later PRs): marker downcall bind,
                                ringbuf mmap reader, bpf()/perf_event_open downcalls

:profiler                       LiveEbpfCollector : ProfileCollector (post-G2)
                                ProfileObservation gains optional context fields
```

### 4.5 Attach mechanics (FFM-feasible path, used by the PR-15 migration)

> **Status: Implemented contract.**

Uprobes are a dynamic PMU since Linux 4.17. Without libbpf:

1. Read PMU type from `/sys/bus/event_source/devices/uprobe/type`.
2. `perf_event_open(attr)` with `attr.config1 = uprobe_path`, `attr.config2 =
   probe_offset`, pid filter scoped to the target JVM.
3. `bpf(BPF_LINK_CREATE, …)` with `link_type = BPF_PERF_EVENT_LINK_TYPE`,
   `target_fd = perf_fd`.

`probe_offset` is resolved from the ELF symbol table of the exact file mapped by the JVM,
verified via `/proc/<pid>/maps` inode match plus `NT_GNU_BUILD_ID` comparison. A mismatch
is a loud attach failure (benign direction: without attachment every event is UNKNOWN).

## 5. Trust Model

> **Status: Implemented contract.**

> **Tier E context metadata is supplied by the observed JVM and is not trustworthy after
> compromise of that JVM.**

* Any ACE inside the target can call the marker with an arbitrary id and forge its own
  labels. This is acceptable because Tier E feeds detection/observability only (invariant 1).
* **Self-labeling only:** a uprobe fires on the process that executed the marker; task
  storage updates *that task*. No mechanism exists by which process A labels process B's
  syscalls. Unlike the rejected shared-array design, there is no writable cross-process
  surface at all.
* **Control socket** (`/run/mazewall/context.sock`, WP-04) is metadata-plane only:
  filesystem ownership + restrictive perms, `SO_PEERCRED` validation at accept time,
  duplicate-session rejection. It can never alter another process's attribution.
* **Daemon death:** links and maps die with the daemon's FDs (no pins). Breakpoints are
  restored; markers become inert plain calls. Storage entries persist until task exit but
  belong to a dead map object and are unreachable. Post-death events do not exist; the
  consumer sees session DEAD (invariant 7).
* **Detach while live:** callback-induced detach clears attribution state immediately; the collector
  emits `UNKNOWN` until a validated re-entry boundary occurs, preventing stale resume within the
  same callback window.

## 6. Rejected Alternative: mmap'd Context Slots ("Tier E Fast")

> **Status: Historical evidence.**

The original design wrote `(generation << 32) | contextId` into a `BPF_F_MMAPABLE` array
slot from userspace, keyed by host TID resolved across PID namespaces. It was rejected for
the correctness path because:

1. **Cross-model memory ordering.** The JMM gives no guarantees to an eBPF reader; LKMM and
   hardware semantics govern the actual race. A release fence is not a proof; the kernel
   documentation itself warns that direct pointers into array-map values require explicit
   synchronization under concurrent access. On ARM64 a late-visible store could attribute a
   syscall to the *previous* scope — a confident lie, violating invariant 3's spirit.
2. **Identity joining machinery.** Container↔host TID translation (NSpid scans), slot
   allocation/exhaustion, generation counters, SCM_RIGHTS map-FD export, thread-exit cleanup
   — all existed solely to compensate for the indirect keying.
3. **Forgeability surface.** The whole array FD lived in the client; a malicious client
   could scribble other clients' slots.

It is recorded as **Tier E Fast — deferred optimization, not an implementation target**.
Reviving it requires: a formally reviewed cross-userspace/kernel release/acquire protocol,
mandatory ARM64 CI, and a written proof that wrong attribution cannot occur under
concurrent userspace/kernel access.

## 7. Verified Kernel Facts (floor: Linux 5.15 LTS)

> **Status: Historical evidence.**

| Fact | Since | Source |
|---|---|---|
| `bpf_task_storage_get/delete` helpers | 5.11 (LSM-only), tracing/kprobe/perf-event/raw_tp/tracepoint/tracing progs ~5.12 | Song Liu series, netdev 2021-02; eBPF docs helper page lists `BPF_PROG_TYPE_KPROBE` |
| Task storage auto-freed at task destruction | 5.11+ (`bpf_task_storage_free` in `__put_task_struct`) | same series |
| Uprobe dynamic PMU via `perf_event_open` (`config1`=path, `config2`=offset, pid filter meaningful) | 4.17 | `perf_event_open(2)`; LWN 740832 |
| Raw tracepoint `sys_enter` args: `(pt_regs*, long nr)` — nr directly available | 4.16 | kernel `bpf_trace.c`; tracepoint format |
| `bpf_get_current_task_btf()` trusted current pointer | 5.11 | kernel sources |
| Storage allocation refused for dying tasks (`usage == 0`) → treat as UNKNOWN | 5.12+ | same series (refcount guard) |
| BPF ring buffer (used for event delivery) | 5.8 | kernel docs |
| `BPF_F_MMAPABLE` (deferred Tier E Fast only) | 5.5 | kernel docs |
| Recursive task-storage deadlock guard (helper fails over instead of hanging) | 5.12 | same series |

CI/test environments must run rootful containers (Podman/Docker with `--privileged` or
equivalent caps) — identical ceiling to Tier P, documented in profiler-design.md §Tier P.

## 8. Gates

> **Status: Implemented contract.**

| Gate | Definition | Blocking |
|---|---|---|
| **G0** | Deterministic pairing: **G0a** single platform thread, no agent/containers/concurrency — `marker(42)`→`context==42`, `marker(7)`→`context==7` via plain uprobe; **G0b** identical results with USDT attach. 100% repeatable. | PR-03 |
| **G1** | Marker latency measured at realistic transition rates for **both** variants (µs-class uprobe cost quantified; skip-if-unchanged optimization evaluated). Numbers recorded as design doc addendum. | PR-03 |
| **G2** | Zero wrong pairings under stress: thread churn (≥100k create/destroy), nesting, executor reuse, deliberate pre-attach/post-detach windows. UNKNOWN and dropped reported separately; incorrect == 0. | PR-05 |
| **G3** | Oracle parity matrix (WP-10): Tier E vs `USER_NOTIF` oracle across 7 workload classes; incorrect attribution == 0. | PR-10 |

## 9. Work Packages

> **Status: Required hardening.**

See backlog registry: `docs/internals/backlog/implementation/issue-20260825-*-tier-e-wp-*.md`.

| WP | Roadmap step | Issue |
|---|---|---|
| — | Master tracker | issue-…-tier-e-initiative.md |
| WP-01 | In-memory `MazewallContext` API + guards | issue-…-wp-01-mazewall-context-api.md |
| WP-02 | Standalone C eBPF syscall collector | issue-…-wp-02-collector-prototype.md |
| WP-03 | Marker `.so` + uprobe + task-storage PoC (G0/G1) | issue-…-wp-03-marker-uprobe-poc.md |
| WP-04 | Session lifecycle & trust protocol | issue-…-wp-04-lifecycle-trust.md |
| WP-05 | Concurrency stress suite (G2) | issue-…-wp-05-concurrency-stress.md |
| WP-06 | Noise budget & UNKNOWN counters | issue-…-wp-06-noise-budget.md |
| WP-07 | Container metadata association | issue-…-wp-07-container-metadata.md |
| WP-08 | FFM bridge client | issue-…-wp-08-ffm-bridge-client.md |
| WP-09 | `LiveEbpfCollector` integration | issue-…-wp-09-live-collector.md |
| WP-10 | Oracle comparison suite (G3) | issue-…-wp-10-oracle-comparison.md |
| WP-11 | Limited Java agent | issue-…-wp-11-java-agent.md |
| WP-12 | Performance harness | issue-…-wp-12-perf-harness.md |
| WP-13 | Sampling enrichment | issue-…-wp-13-sampling-enrichment.md |
| WP-14 | FFM loader/control-plane migration | issue-…-wp-14-ffm-migration.md |
| WP-15 | Kubescape integration PoC | issue-…-wp-15-kubescape-poc.md |

Dependency chain: WP-01 → WP-02 → WP-03 → WP-04 → WP-05 → {WP-06, WP-07} → WP-08 → WP-09 →
WP-10 → {WP-11 → WP-12}, WP-13; WP-14 after WP-10; WP-15 last. WP-02..05 live in
`ebpf-prototype/` and must not touch Gradle wiring.

## 10. Reuse Map (existing components, do not re-implement)

> **Status: Implemented contract.**

| Need | Existing component |
|---|---|
| Strategy-neutral collection + drop accounting | `ProfileCollector`, `CollectorDrain`, `ObservationMerger` (`:profiler .../collector/`) |
| Reserved integration seam | `EbpfCollector` stub ("live attach not implemented; would need a privileged sidecar") — Tier E *is* that sidecar |
| Privilege ceiling probe | `EbpfCapability.probe()` (`:profiler EbpfLoad.kt`) |
| SCM_RIGHTS over AF_UNIX (both directions) | `SupervisorSocketUtils`, `SocketManager.receiveDescriptor`, `FileDescriptor.adopt` (`:platform`) |
| Raw syscalls incl. `gettid`, `mmap`, socket set, `pidfd_*` | `SyscallInvoker` (`:platform ffi/internal`) |
| Daemon reactor state-machine pattern | `UnixListenDaemonMachine`, `SeccompDaemonEngine/Machine/Handler` (`:platform platform/daemon`) |
| Event identity model | `ProfileObservation.correlation(tgid, tid, ktimeNs)` |
| FFM memory discipline | `ManagedSegment`, `NativeArena`, `SegmentPool`, `LayoutValidator` (`:platform ffi/memory`) |

Non-refactor rule: leave the `TraceEvent` / `SyscallEvent` / `ProfileObservation` trio as-is
(wire shape vs neutral model separation is intentional).

## 11. Risks & Honest Limitations

> **Status: Implemented contract.**

| # | Risk | Direction | Mitigation |
|---|---|---|---|
| 1 | Marker latency: classic x86 uprobe ≈ trap + out-of-line step (µs-class), fires once per transition | measurable | G1 microbenchmark; optional skip-if-unchanged guard (ThreadLocal compare before downcall — semantics-preserving). If unacceptable ⇒ evidence for Tier E Fast, not for keeping two backends alive |
| 2 | Pre-attach window: scopes entered before attach emit UNKNOWN | fail-unknown | documented accepted behavior |
| 3 | Mid-scope detach can leave stale invocation state (see §5) | bounded/invalid-state control | session-epoch boundary transitions clear attribution state on callback path |
| 4 | Wrong-binary misfire (probing stale `.so` copy) | benign (silent UNKNOWN) but confusing | `/proc/<pid>/maps` inode + build-id verification, loud failure |
| 5 | Virtual threads: carrier-local nesting is observed with a different logical execution | unacceptable | clear the marker and registry; count a scope failure; never restore the prior invocation |
| 6 | Task-storage creation failure under pressure | fail-unknown | count and emit nothing |
| 7 | Arch-specific argument registers (x86_64 `di` / arm64 `x0`) | minor | eliminated in the USDT variant (`bpf_usdt_readarg`); compile-time per-arch BPF source remains the plain-uprobe bring-up fallback |
| 8 | Ring-buffer drops | data loss, never corruption | drop counter per nr; `drainComplete=false`; never compile BoB |

## 12. Open Questions

> **Status: Required hardening.**

1. **Public surface placement for `MazewallContext`** (WP-01): new tiny published module vs
   public API nested in an existing artifact. Decide during WP-01 review; default is no new
   module.
2. **Kubescape node-agent kernel baseline** (WP-15): confirm 5.15 floor matches the
   supported matrix of the deployment target before promising compatibility.
3. **UNKNOWN sampling policy** (WP-13): whether sampled UNKNOWN events (1-in-N) justify
   their ring-buffer cost; decide with WP-06 numbers in hand.

---

## Appendix A. External Reference Review: Oracle, *"From kernel to user-space tracing"* (A. Maguire, 2022-11-15)

> **Status: Historical evidence.**

Reviewed 2026-08-25. Source: blogs.oracle.com/linux/from-kernel-to-user-space-tracing;
sample code: github.com/oracle-samples/linux-blog-sample-code (branch
`kernel-to-userspace-tracing`). License of any borrowed snippet must be verified before use
(reference-only by default).

### A.1 What it confirms about our design

> **Status: Historical evidence.**

* Uprobe mechanics: registration records the instruction at the target offset and plants a
  breakpoint (x86_64 INT3) into **every VMA mapped from that inode**; the handler runs
  synchronously on the faulting task before the instruction executes (trap → handler →
  single-step/XOL). Independent confirmation of our §4.1 synchronous-ordering claim.
* Attach ergonomics: libbpf ≥ 0.8 supports attach-by-function-name and binary-name lookup
  (no hand-computed offsets) — matches our prototype-phase plan (WP-03).
* uretprobes patch return addresses (dispatcher trampoline). We deliberately avoid them:
  our exit path re-fires the same state-carrying probe instead. Their added fragility buys
  us nothing.

### A.2 USDT adoption decision

> **Status: Historical evidence.**

See §4.1.1. Summary: USDT is the userspace analogue of tracepoints — named probes with
declared arguments recorded in ELF `.note.stapsdt`, implemented atop uprobes. Adopted as the
production marker ABI because it converts our marker from an implementation detail into a
discoverable, verifiable, evolvable interface. Trade-offs accepted: `sys/sdt.h` build-time
dependency (prototype toolchain only), libbpf ≥ 0.8 for `attach_usdt` (prototype only),
custom `.note.stapsdt` parsing deferred to WP-14 with a documented plain-uprobe fallback
path. Semaphores rejected for v1 (no argument-prep cost today).

### A.3 Future async extension vocabulary (not v1 scope)

> **Status: Historical evidence.**

The synchronous tier keys context by task; asynchronous work (io_uring et al.) must key by
request identity. USDT gives us the emission side without new machinery:

```text
mazewall:async_submit(u64 operation_id, u64 invocation_id)
    → BPF: async_context[operation_id] = context_id
io_uring submit/complete tracepoints
    → recover request → context mapping at completion time
```

This preserves the invariant split: *synchronous execution → task storage; asynchronous
execution → request-keyed map*. Any such probe set is a separate future initiative gated on
the same fail-unknown rules; nothing in this document commits Tier E v1 to it.

## Appendix B. Gate Results (kernel 7.1.4-xanmod1 x86_64, rootful container, 2026-08-25)

> **Status: Historical evidence.**

| Gate | Result | Evidence |
|---|---|---|
| G0a | PASSED (3 runs) | Deterministic pairing via plain symbol uprobe: phases 42×5 → 7×3, silence after reset |
| G0b | PASSED | Identical event patterns through `usdt/…:mazewall:context_switch`; wrong-binary guard demonstrated (`stapsdt` notes present only in USDT artifact) |
| G1 | RECORDED | 10 M transitions per configuration, single-line results |

### B.1 G1 latency numbers

> **Status: Historical evidence.**

| Configuration | ns / transition |
|---|---|
| Plain uprobe, detached | 3.0 |
| Plain uprobe, attached | **1 324** |
| USDT, detached | 3.3 |
| USDT, attached | **596** |

* Attached USDT is ≈2.2× cheaper than attached plain uprobe on this kernel — empirical
  support for the §4.1.1 decision to make USDT the production ABI.
* Both variants confirm the µs-class prediction (§11 risk 1). Enter+exit costs
  ≈1.2–2.6 µs per scope: negligible for request-scale code with tens of scopes; material
  for tight inner loops ⇒ the skip-if-unchanged guard (WP-08) and "scope at boundaries,
  not inside loops" guidance are mandatory, not optional polish.
* Ring-buffer saturation under benchmark load was not exercised (the timed loop performs
  no syscalls; only 3 gate-wait events were observed, `dropped=0`). Drop-rate behavior
  under concurrent syscall pressure is measured separately in WP-06.
* Environment caveat: numbers are from one host/kernel; CI reruns on target kernels before
  any external claim (no public commitments per WP-12 policy).

### B.2 Harness notes

> **Status: Historical evidence.**

* Bench process holds on a gate file after dlopen+warmup so attachment precedes timing;
  first-run defect (attached rounds measuring detached cost due to loader flag rejection)
  was caught by the sanity expectation "attached ≫ detached" and fixed.
* The G0b silent-park failure (attach succeeding against a library the target had never
  mapped) motivated the harness rule: hard-fail unless `/proc/<pid>/maps` shows the marker
  library before attaching.

Reviewed 2026-08-25. Source: blogs.oracle.com/linux/from-kernel-to-user-space-tracing;
sample code: github.com/oracle-samples/linux-blog-sample-code (branch
`kernel-to-userspace-tracing`). License of any borrowed snippet must be verified before use
(reference-only by default).

---

## Appendix C. JVMTI native-method event backend (investigated, not enabled)

> **Status: Historical evidence.**

JVM TI `MethodEntry` and `MethodExit` are generated for Java methods **including native
methods**. This makes a generic native-boundary backend technically possible without an
OpenJDK-specific `NativeMethodBind` proxy manifest:

```text
MethodEntry(native Java method)
  → synchronously GetStackTrace + intern InvocationId + marker(id)
native code runs; sys_enter records id
MethodExit(native Java method)
  → marker(parent-or-zero)
```

This is exact at the same Java-to-native boundary as the binding-proxy backend. In particular,
the captured stack is a JVM logical stack, so inlined Java frames are represented by JVM TI
rather than guessed from a native PC. Native method exits are delivered on both normal and
exceptional returns, allowing the scope to be cleared without timestamp correlation.

It is intentionally not a supported Tier E backend today. JVM TI only exposes the event switch
globally: the callback must receive every Java method entry/exit before it can reject
non-native methods. The JVM TI specification explicitly warns that enabling these events can
significantly degrade performance and recommends bytecode instrumentation for performance
critical use ([JVM TI 25 — Method Entry and Method Exit](https://docs.oracle.com/en/java/javase/25/docs/specs/jvmti.html)).

The local controlled workload measured that cost directly. For ten file write/read iterations,
the generic event implementation captured 20,287 native entries and produced 180 resolved
syscalls with zero wrong attribution and zero kernel loss, but took **742,987,879 ns**. The
equivalent USER_NOTIF oracle took **264,645,705 ns**. It is therefore about 2.8× slower than
the oracle here, while the native-binding proxy remains faster than the oracle. These are
environment-specific measurements, but enough to reject MethodEntry/MethodExit as the default.

This remains useful future work in three situations:

1. a diagnostic-only, explicitly slow fallback where JVM-neutral native-boundary coverage is
   more valuable than throughput;
2. a correctness oracle for a generated-wrapper backend; and
3. a development aid for discovering missing binding-manifest entries.

The viable generic fast direction is **selective instrumentation of native call boundaries**:
rewrite or generate wrappers only around native invocations, invoke the same marker around the
call in a `try/finally`, and retain the current `InvocationId → dictionary` protocol. Such a
backend must be separately designed and benchmarked; it cannot be substituted by enabling
global MethodEntry/MethodExit events.

### C.1 PID namespace requirement for kernel gates

> **Status: Implemented contract.**

`bpf_get_current_pid_tgid()` uses the kernel's initial PID namespace identity. Consequently the
TGID installed in the BPF target map must be the target's host PID. A collector running in a
separate PID namespace cannot pass its namespace-local `Process.pid()` and expect target
matches. The privileged Docker gate must run with `--pid=host` (or receive the host PID from the
container runtime); otherwise both marker and syscall programs correctly reject the target and
the result is zero observations. This is a fail-unknown condition, never a reason to remove the
TGID guard.

## Appendix D. Updated Tier E hardening contracts

> **Status: Required hardening.**

### D.0 Benchmark correctness vocabulary

> **Status: Implemented contract.**

Benchmark artifacts report `workload_correctness` as `PASS`, `FAILED`, or `UNVERIFIED`.
`PASS` requires a benchmark-only, independent operation oracle that maps every declared
operation to exactly one observed syscall and validates its invocation, execution identity,
and version-qualified logical stack. Aggregate resolved-event counts and matching stack names
are useful diagnostics but are not an operation oracle.

The current shared file workload supplies only a batch-scoped expected `unlink` count. Ring
records intentionally retain syscall arguments, not dereferenced pathname bytes, so it cannot
map an `unlink` to a particular declared pathname. Its benchmark results are therefore
`UNVERIFIED`, never `PASS`; a missing, extra, wrong, duplicate, or unclassified operation is
`FAILED`. Whole-session evidence is reported independently and does not become policy input.
The runner also retains `attach_ns`, `workload_ns`, and `terminal_ns` for Tier E, plus
`profile_session_ns` and `profile_overhead_ns` for USER_NOTIF. These are phase measurements,
not a causal explanation; deeper USER_NOTIF readiness/receive/ack instrumentation remains
required before changing its reactor or acknowledgement schedule.

The privileged `write-oracle-workload` is the correctness fixture. It writes to `/dev/null`
through the supported `UnixFileDispatcherImpl.write0` proxy and encodes a run-local operation
identifier in the `write(2)` byte-count argument. The collector matches this value before any
unique-edge retention decision. In full-stream mode it requires one resolved record with the
workload stack, invocation identity, and execution identity for every declared operation. In
unique-edge mode it records the same identity checks for every collector delivery decision and
requires an explicit acknowledged representative or post-acknowledgement suppression for every
declared operation. Nonmatching, unresolved, duplicate, or missing decisions are classified
independently and do not satisfy the fixture denominator.

The current USER_NOTIF filter selects `openat` for this workload but does not select its
subsequent `write`. Its artifact therefore records `operation_oracle_available=false` and
`operation_oracle_reason=write_not_selected_by_user_notif`; it remains a timing and
stack-capture comparison, not a corresponding operation-identity proof. A USER_NOTIF oracle
requires a separately selected syscall with an argument-visible operation identifier.

### D.1 Live dictionary publication

> **Status: Implemented contract.** The current implementation provides opt-in live
> publication, the MZSE envelope, and transactional dictionary batches. The dependency
> completeness and durability rules below define the conditions for treating a live snapshot
> as terminal evidence; any property not covered by retained evidence remains required hardening.

Live publication is opt-in. It has four distinct guarantees:

1. **Atomic visibility:** the producer writes a temporary sibling and atomically replaces
   the configured path only after the complete snapshot bytes are written.
2. **Coherent contents:** the bytes represent one immutable, dependency-complete committed
   dictionary prefix through watermark `W`. Every invocation in that prefix has its required
   stack and execution definitions in the same prefix.
3. **Producer closure:** a terminal publication explicitly acknowledges that no later
   definitions will be produced for the session.
4. **Durability:** the default guarantee covers process failure and atomic pathname visibility.
   Host-power durability requires an opt-in file synchronization and containing-directory
   synchronization step; it is not paid for on every live publication by default.

The live path uses a versioned outer `MZSE` envelope around unchanged raw MZSD bytes:

```text
magic            4 bytes: ASCII MZSE
version          u16 LE: 1
flags            u16 LE: bit 0 means producer closed
publication_id   u64 LE
watermark        u64 LE
payload_length   u64 LE
payload          payload_length bytes containing unchanged MZSD v2 or v3 data
```

Raw MZSD v1/v2 files remain readable as legacy snapshots; v3 is used only by the opt-in
inline-per-invocation transport. A raw or open MZSE snapshot is a
recoverable prefix only; it is not proof of terminal dictionary completeness. The reader
opens one descriptor and parses both envelope metadata and payload from that descriptor. It
does not combine metadata from one pathname lookup with bytes from a later lookup.

One publication owner serializes snapshots and rejects publication-id regression. The owner
captures immutable references to the committed prefix under a short definitions lock, then
encodes and writes outside that lock. Dirty-generation tracking skips unchanged publications;
bounded coalescing reduces repeated full rewrites while retaining an explicit final
publication. Dictionary batches retain their opaque identity across retries, and failed or
ambiguous delivery never advances the watermark.

### D.2 Symbol lifetime and epochs

> **Status: Implemented contract.** `SymbolizationGeneration` brackets supported replacement,
> rejects unproven external replacement, and revalidates capture leases. Additional JVM-specific
> lifecycle coverage remains required hardening.

`SymbolizationGeneration` denotes a stable installed-definition interval. A capture is admitted
only while its generation is stable, and the lease is revalidated after stack capture, raw-key
lookup, and logical metadata copying. Equality of two epoch samples is necessary but
insufficient while a class replacement may still be in progress.

The required state machine is:

```text
STABLE
  -> REPLACEMENT_IN_PROGRESS
  -> STABLE(next generation)

STABLE
  -> EXTERNAL_REPLACEMENT_UNPROVEN
  -> validated resynchronization or new session
```

Initial class loading becomes stable only after its post-install lifecycle signal. A
transformation initiated through the supported cooperative path brackets the complete
operation, including the transformation call's return. A replacement initiated elsewhere
causes conservative unresolved or `CAUSAL_UNCERTAIN` captures until a validated
resynchronization boundary; capture threads never wait for replacement completion.

Cache identity includes method identity, installed symbolization generation, and raw location.
Hash lookup selects candidates, but complete raw-key equality decides identity. Metadata copying
holds a strong local declaring-class reference until all required strings and frame data are
copied into immutable records. Weak guards are retained only for later cache validation and
must never be used to dereference an unloaded class.

No transition may restore an older exact invocation implicitly. Re-entry requires a validated
new generation and an explicit re-entry event.

### D.3 Control protocol and receive boundaries

> **Status: Implemented contract.** Version-1 replies are frozen by fixtures and this section
> defines the framing and timeout contract precisely.

The control protocol is not an unframed line stream. Version 1 uses a two-byte unsigned
big-endian length prefix followed by Java modified-UTF bytes, as emitted by
`DataOutputStream.writeUTF` and consumed by `DataInputStream.readUTF`. One decoded frame
contains one command line; one response frame contains one rendered reply. The maximum
encoded frame length is 65,535 bytes.

The receiver distinguishes these conditions:

- **Idle timeout:** no frame has started; recheck shutdown and keep the client session alive.
- **Partial-header timeout:** retire the client without restarting framing.
- **Partial-payload timeout:** retire the client without restarting framing.
- **Whole-frame deadline:** bound total time from the first header byte to the final payload
  byte, so periodic bytes cannot hold the session indefinitely.
- **Hard socket error:** preserve existing fail-closed session retirement.

The default idle timeout and whole-frame deadline are each 30 seconds. The decoder tracks
header bytes, declared payload length, payload bytes, frame start time, and terminal reason.
The current decoder does not preserve resumable partial-frame state, so a partial-frame timeout
retires the client rather than interpreting the next byte as a new frame. Any wire-visible
change requires a new protocol version and updated golden transcripts.

## Tier E contract status and boundaries

> **Status: Implemented contract.** This section is the normative summary for the current
> architecture. Requirements explicitly marked `Required hardening` elsewhere remain planned
> until their implementation evidence is retained.

Tier E is an exact attribution system for a bounded capture domain, not an oracle for every
syscall attempt. Exact attribution is valid only when the native boundary has admitted a
stable symbolization generation, the logical stack has been copied into immutable metadata,
and the corresponding invocation identity is joined without inference.

The following outcomes are distinct and must not be collapsed:

- `UNKNOWN`: no valid active invocation or no exact attribution available.
- `CAUSAL_UNCERTAIN`: re-entrancy, callback suppression, or an unproven lifecycle boundary
  prevents causal attribution.
- Capture failure: the exact native stack or required arguments could not be captured.
- Transport loss: a definition, event, acknowledgement, or snapshot could not be delivered.
- Out-of-domain attempt: an attempt did not reach the selected `sys_enter` path.

Tier E observes records reaching the selected `raw_tp/sys_enter` path for the target process.
Seccomp-denied or otherwise out-of-domain attempts are represented as boundary diagnostics,
never fabricated exact events. Attribution, event retention, dictionary resolution, and
downstream acknowledgement are separate state transitions. Any incomplete dimension keeps
the final evidence incomplete and prevents policy use.

The complete flow is:

```text
native boundary
  -> symbolization generation admission
  -> exact stack capture
  -> immutable definition publication
  -> marker/ring event
  -> owned userspace record
  -> invocation/dictionary join
  -> downstream acknowledgement
  -> immutable final evidence
```

## Resource ownership and bounded admission

> **Status: Required hardening.** Ring records, transactional batches, and pending observations
> now have explicit ownership and bounded local budgets. A single cross-component budget covering
> every cache and snapshot buffer remains the next hardening step.

Before the consumer position advances, every record retained asynchronously is copied into
collector-owned storage. Delayed dictionary joins never retain a view into reusable mapped
ring memory. Ring draining remains independent of dictionary parsing, snapshot serialization,
sink I/O, and downstream acknowledgement.

Capture threads never wait for dictionary queue space, snapshot publication, or acknowledgement.
Admission failure is recorded explicitly and cannot leave an older invocation active; the
original native operation continues when the instrumentation failure is recoverable.

The retained-state budget accounts for all of the following together:

```text
dictionary staging
+ pending observations
+ prepared batches
+ outstanding emission tokens
+ snapshot buffers
+ metadata caches
```

The default bounded staging budget is 64 MiB. Exhaustion records explicit loss and incomplete
evidence rather than silently evicting state needed for an exact positive attribution. An
abandoned in-flight dictionary batch remains uncommitted and cannot advance the watermark.

Ownership lifecycle:

```text
mapped record
  -> copied owned record
  -> delayed join
  -> resolved observation
  -> immutable retained result
```

## Native-call preservation contract

> **Status: Implemented contract for current proxy cleanup paths.** This contract applies to
> every native-method proxy; future binding additions require the same failure-injection tests.

Profiler instrumentation is observational around the original operation. It must preserve:

- the original native return value;
- any pending application exception;
- applicable `errno` values;
- execution of the original operation when profiler admission failure is recoverable; and
- explicit re-entrant attribution boundaries.

Profiler cleanup must not use blanket `ExceptionClear()` or erase an application exception.
JNI work that is invalid while an exception is pending is avoided or completed before the
original operation. Profiler-worker syscalls do not inherit application attribution; in
`FULL_STREAM` they remain retained as unmarked activity rather than being silently removed.

## Performance targets and evidence contract

> **Status: Implemented measurement contract; optimization targets remain required hardening.**
> These are measurable acceptance targets, not claims about every host or JDK.

Warmed exact-cache hits should perform no profiler-owned heap allocation, string formatting,
or file I/O. Scratch storage is depth-aware for nested native calls, and virtual-thread
temporary storage is tied to the protected native execution. Snapshot serialization and sink
I/O run away from capture threads.

Benchmark evidence reports phase totals and:

- cache hits and misses;
- capture-side lock wait;
- snapshot bytes and publication latency;
- pending-observation high-water marks; and
- dictionary-resolution latency.

Measurements compare publication disabled/enabled, instrumentation disabled/enabled,
dictionary/inline-per-invocation transport, full-stream/unique-edge modes, warmed execution, and
class-loading/retransformation churn.
The existing host measurements remain historical evidence. A 25% regression threshold applies
to each measured phase and mode total, and logical stack bytes must remain identical before and
after an optimization.

USDT remains an experimental marker alternative. It can replace the plain-uprobe path only
after end-to-end evidence proves identical ordering, identity, failure behavior, and wire
output with a measured improvement. Manual ring notification suppression and removal of
failure-path clearing transitions are not accepted optimizations.

## Requirements and evidence matrix

> **Status: Implemented contract.** The matrix is the audit index; a `planned` row is not
> represented as an implemented guarantee.

| Contract | Status | Design section | Current implementation or fixture | Required evidence | Evidence artifact | Verdict dimension |
|---|---|---|---|---|---|---|
| Capture-domain boundary | implemented | 2.2, Contract status | `CaptureDomain`, seccomp boundary fixture | observed `sys_enter` versus `attempt_missed` artifact | `tier-e-rootful-final-gate-20260913.json` | `capture_domain_validity` |
| Stable installed symbolization generation | implemented | D.2 | native `SymbolizationGeneration`, redefinition fixture, lifecycle stress fixture | replacement, unload/redefinition, and stale-identity rejection | `tier-e-lifecycle-identity-stress-20260913.json` | `lifecycle_validity`, `attribution_coverage` |
| Strong metadata lifetime | implemented baseline | D.2 | native metadata resolver and weak cache guards | unload race with no invalid method dereference | `tier-e-rootful-final-gate-20260913.json` | `dictionary_integrity` |
| Coherent live snapshot and terminal closure | implemented baseline | D.1 | `AgentDefinitionFile`, live definition publication, `TierEEndToEndIntegrationTest` | MZSE bytes, watermark, publication ID, closure acknowledgement | `tier-e-rootful-final-gate-20260913.json` | `transport_completeness`, `dictionary_integrity` |
| Non-blocking bounded admission | implemented baseline | Resource ownership | encoder, `DictionaryBatchPublisher`, retained-result limits | stopped-consumer overload, retry, terminal, and abandoned-batch evidence | `tier-e-transport-fault-injection-20260913.json` | `transport_completeness` |
| Owned ring records | implemented | Resource ownership | `BpfRingBufferReader`, event decoder | delayed join across ring wraparound | `tier-e-rootful-final-gate-20260913.json` | `attribution_coverage`, `dictionary_integrity` |
| Native outcome preservation | planned for full binding audit | Native-call contract | invocation proxy and cleanup path | return, exception, `errno`, and re-entry fixture for every binding | not yet retained | `lifecycle_validity` |
| Version-1 framing | implemented | D.3 | `ControlProtocol`, `TierEClientSession`, golden transcripts | partial-frame, deadline, and hard-error transcript | `tier-e-rootful-final-gate-20260913.json` | `transport_completeness`, `lifecycle_validity` |
| Exact-stack performance | implemented measurement gate | Performance targets | native phase benchmark, baseline, 25% threshold checker | raw samples, phase medians, stack-byte comparison | `tier-e-rootful-final-gate-20260913.json` | `runtime_overhead` |

Each evidence artifact records the source commit, command, environment, raw output location,
fixture result, and the six machine-readable dimensions:
`capture_domain_validity`, `transport_completeness`, `attribution_coverage`,
`dictionary_integrity`, `lifecycle_validity`, and `runtime_overhead`.

---

## Current architecture-review contract (normative override)

This section is the current contract for Tier E. Earlier descriptions that mention
`ContextId`, `MazewallContext`, or `ebpf-prototype/` are historical material and do
not describe the active implementation.

### Contract status labels

Each requirement in this section is one of:

- **Implemented contract**: present in the repository and covered by focused tests.
- **Required hardening**: an approved requirement that still needs an implementation or durable fixture.
- **Historical evidence**: a retained measurement or decision, not a current guarantee.

### Boundaries and guarantees

**Implemented contract.** Tier E observes selected `raw_tp/sys_enter` events, not every
syscall attempt. Exact attribution is valid only for a proven native-boundary stack.
`UNKNOWN`, `CAUSAL_UNCERTAIN`, capture failure, transport loss, and out-of-domain
attempts are distinct evidence outcomes. Tier E evidence is never policy input;
`policy_eligibility` is always `false`.

Attribution, event retention, dictionary resolution, and transport acknowledgement
are separate concerns:

```text
native boundary
  -> symbolization generation admission
  -> exact stack capture
  -> immutable definition publication
  -> marker/ring event
  -> owned userspace record
  -> invocation/dictionary join
  -> downstream acknowledgement
  -> immutable final evidence
```

Fail-closed behavior means an incomplete run remains incomplete. No empty ring, valid
file, or zero out-of-domain counter is by itself a producer barrier or proof of
complete syscall-attempt coverage.

### Active native and eBPF path

**Implemented contract.** The active path is the native-method proxy, exact JVMTI
stack capture, immutable dictionary records, per-invocation `InvocationId`, marker
uprobe, BPF task storage, selected `raw_tp/sys_enter`, and collector-owned
resolution. The kernel coverage map in unique-edge mode is diagnostic only. It never
suppresses, reserves, or submits ring observations. Unique-edge suppression changes
only after the collector receives successful downstream acknowledgement.

The task-storage value is exactly 24 bytes:

```text
invocation_id  u64 @ 0
sequence       u64 @ 8
flags          u32 @ 16
reserved       u32 @ 20
```

The ring record is exactly 88 bytes:

```text
ktime_ns       u64 @ 0
 tgid          u32 @ 8
tid            u32 @ 12
syscall_nr     s32 @ 16
flags          u32 @ 20
invocation_id  u64 @ 24
sequence       u64 @ 32
args[6]        u64 @ 40
```

The event flags are `MARKER_ACTIVE`, `CAUSAL_UNCERTAIN`, `CAPTURE_FAILED`, and
`OUT_OF_DOMAIN`. Unknown flag bits are evidence-invalid rather than silently ignored.
The selected raw tracepoint only observes events reaching the selected `sys_enter`
path; seccomp-denied attempts outside that path are represented as an explicit
capture-domain limitation, never as fabricated exact events.

A marker update looks up the session and task-storage value, verifies the target
process, and updates the value returned by `bpf_task_storage_get`. The initialization
argument applies only when storage is first created. A clear transition writes zero
invocation and flags while advancing the marker-state version only after a successful
update. Failed lookup or update leaves attribution conservative. Removing marker
attachment prevents later `sys_enter` events from restoring an old exact invocation.

### Symbolization generation and cache safety

**Implemented contract.** `SymbolizationGeneration` represents a stable installed-
definition interval. Equality of before/after samples is necessary but insufficient
during replacement. Initial class loading closes only after a post-install lifecycle
signal. Agent-cooperative retransformation brackets the complete operation, including
return. Unbracketed external replacement yields conservative unresolved attribution;
capture threads never wait for transformation completion.

The state machine is:

```text
STABLE
  -> REPLACEMENT_IN_PROGRESS
  -> STABLE(next generation)

STABLE
  -> EXTERNAL_REPLACEMENT_UNPROVEN
  -> validated resynchronization or new session
```

Cache keys include method identity, installed generation, and raw location. Strong
local JVM references are used only while copying metadata. Weak guards validate
retained entries and are never used to dereference unloaded classes. No transition
may restore an older exact invocation implicitly.

### MZSE publication and replay

**Implemented contract.** Raw `MZSD v2` dictionary bytes remain backward-compatible; raw
`MZSD v3` carries the opt-in inline-per-invocation records. MZSE version 1 remains readable.
A session-bound MZSE version 2 has this exact envelope:

```text
magic            4 bytes: MZSE
version          u16 LE: 2
flags            u16 LE
session_epoch    u64 LE
publication_id   u64 LE
watermark        u64 LE
payload_length   u64 LE
payload          unchanged MZSD v2 dictionary bytes or MZSD v3 inline bytes
```

The watermark is the highest committed `StackTraceId` represented by the committed
dependency-complete definition set. It is not a byte offset, event sequence, or
`InvocationId`. A reader admits a live snapshot only when its session identity is
unbound for legacy compatibility or matches the expected session. A same-ID
same-content replay is `DUPLICATE_IDENTICAL`; a new identity is `NEW`; a same-ID
content mismatch is `CONFLICTING` and is terminal dictionary inconsistency.
Declared lengths, record counts, supported flags, integer overflow, payload bounds,
and trailing bytes are validated before allocation.

A publication owner performs atomic pathname replacement from a complete temporary
file. Readers parse metadata and payload from one opened descriptor. One owner
prevents generation regression; serialization occurs outside capture-side locks.
Dirty-generation tracking and coalescing avoid repeated unchanged snapshots. The
optional outer live envelope does not change MZSD bytes. Process-failure durability
is the default; file and directory synchronization is opt-in host durability.
Ambiguous remote delivery is terminally incomplete unless the sink explicitly
provides no-visible-effect or idempotent-receiver semantics.

### Ownership and finite retention

**Implemented contract.** Ring records are copied into collector-owned storage before
consumer advancement. Delayed dictionary joins never retain mapped ring views. Ring
drain is independent from dictionary parsing, serialization, sink I/O, and
acknowledgement. Capture threads never wait for queue space, publication, or
acknowledgement.

**Required hardening.** One bounded budget covers dictionary staging, pending
observations, prepared batches, emission tokens, snapshot buffers, and metadata
caches. The default budget is 64 MiB. Exhaustion produces explicit incomplete
evidence, not silent eviction. Abandoned in-flight dictionary batches remain
uncommitted and cannot advance the watermark; their exact bytes remain available for
terminal accounting until cleanup.

The ownership lifecycle is:

```text
mapped record
  -> copied owned record
  -> delayed join
  -> resolved observation
  -> immutable retained result
```

Conditional capacity calculations are calculations, not measurements: 64 MiB divided
by an 88-byte payload is approximately 762,600 payloads, which fills in about 12.7
minutes at 1,000/s, 76 seconds at 10,000/s, or 7.6 seconds at 100,000/s before
metadata and container overhead.

### Native-call preservation

**Implemented contract.** A profiler proxy preserves the native return value, pending
application exceptions, and applicable `errno`. If profiler admission or exact
capture fails, the original operation continues when recoverable. Cleanup must not
erase application failures. Blanket `ExceptionClear()` is prohibited; only
profiler-owned exceptions may be cleared after ownership is proven. Re-entrant
callbacks enter an explicit inactive or uncertain boundary and cannot inherit stale
exact attribution.

### Control protocol

**Implemented contract.** Protocol version 1 is a two-byte length-prefixed Java
modified-UTF frame, containing one command line per request and one rendered reply
per response. The maximum encoded frame length is 65,535 bytes. The idle timeout
between frames is 30 seconds and the whole-frame deadline is 30 seconds.

Idle timeout before a frame is different from partial-header timeout, partial-payload
timeout, hard socket failure, and shutdown polling. A partial-frame timeout retires
the client because the current decoder cannot resume from an unknown byte offset.
`EAGAIN` and `EWOULDBLOCK` are idle-session events that recheck shutdown state;
hard socket errors retain hard-error behavior.

### Performance targets and evidence

**Implemented contract.** A warmed metadata-cache hit is available to both full-stream
and unique-edge modes. It validates the complete raw key, symbolization generation, and
weak class guard before returning a canonical immutable stack definition; every boundary
still receives a fresh invocation and execution identity. The canonical cache is bounded
to 4,096 entries and eviction removes acceleration state only.

**Implemented contract.** Logical-stack interning uses a hash-to-candidate index followed
by full frame equality, preserving monotonic IDs and collision safety. Publication snapshots
retain shared immutable stack records under the definitions lock, then encode and atomically
replace the file outside that lock. Opt-in live snapshots coalesce for 100 ms on one publisher
worker and terminal finish joins the worker before publishing closure.

The native agent enforces a default 64 MiB retained-state ceiling for immutable-definition,
canonical-cache, metadata-cache, interning-index admission, and temporary-publication-buffer
growth. Container-capacity accounting and execution-identity map charging remain required
hardening before this can be treated as a complete native-process memory limit.
An internal agent option permits a smaller test budget only. Exhaustion stops exact definition
admission, leaves the proxied native operation intact, and is retained as incomplete evidence.
The collector's independently enforced budget is reported separately because it runs in another
process.

The Release benchmark runner performs three warm-ups and ten measured samples per scenario.
The matched-flavour runner executes five rotated JVM forks for no agent, dictionary full-stream,
dictionary unique-edge, inline full-stream, and inline unique-edge with identical batch arguments.
It records workload-batch distributions separately from serialization and shutdown publication.
Native-agent-only evidence is labelled as such; privileged end-to-end overhead remains a separate
rootful fixture because it includes BPF and collector costs. Allocation and RSS instrumentation,
class churn, retransformation, and per-operation tail latency remain required hardening.
USDT remains experimental until ordering, identity, failure behavior, and wire output
are proven identical to the plain-uprobe path.

A 25 percent increase is a review trigger, with separate blocking budgets for total
workload, allocation, retained memory, and tail latency. A baseline may be renewed
only with an explicit hardware, JDK, kernel, or workload rationale. The historical
1,324 ns and 596 ns values are host-specific evidence, not universal guarantees.

### Evidence dimensions and implementation matrix

Every retained artifact uses these dimensions:

```text
evidence_integrity: complete | incomplete | unverified
performance_gate: passed | failed | not_measured
policy_eligibility: false
capture_domain_validity
transport_completeness
attribution_coverage
dictionary_integrity
lifecycle_validity
runtime_overhead
```

| Contract | Implementation or fixture | Required evidence | Dimension |
|---|---|---|---|
| Collector-owned unique-edge acknowledgement | `AttributionCollector`, `UniqueEdgeGate`, BPF builder | retry and duplicate-ack tests | attribution_coverage |
| Producer lifecycle and terminal barrier | native agent, session state, marker detach fixture | terminal drain and detach artifacts | lifecycle_validity |
| Session-bound snapshot replay | `SnapshotEnvelope`, `AgentDefinitionFile`, replay ledger | v1/v2 and conflict fixtures | dictionary_integrity |
| Bounded ownership | retained-state budget and dictionary encoder | pressure and abandoned-batch artifact | transport_completeness |
| Native outcome preservation | proxy admission and native tests | return/exception/errno fixture | capture_domain_validity |
| Selected `sys_enter` boundary | Tier E BPF program | denied-attempt limitation artifact | capture_domain_validity |
| Phase-level performance | native benchmark and baseline gate | JSON baseline and warmed run | runtime_overhead |
| JDK-qualified narrowed capture profile | `VM_INIT` admission gate, `OpenatProfileWorkload`, paired benchmark runner | clean rootful five-fork full-versus-openat0 pair with explicit exclusions; `tier-e-capture-profile-20260917.jsonl` | `capture_domain_validity`, `attribution_coverage`, `lifecycle_validity`, `runtime_overhead` |

Unimplemented hardening is marked `planned` in evidence, never `implemented`.
Tier E remains finite, fail-closed, and ineligible as policy input until the required
fixtures and durable artifacts exist.
