# Thread-like Portal developer experience

**Status:** Proposed implementation design, requested on 2026-11-03. The existing
runtime remains the implemented contract until the linked tasks land.

**Goal:** Submit isolated work almost as easily as work to an executor, return
ordinary copied values, and achieve lower warm-call overhead than an equivalent
API service on measured workloads. In-process object-return latency is not the
target. A child JVM remains the security and failure boundary.

**Delivery:** [implementation plan](../../../superpowers/plans/2026-11-03-portal-developer-experience.md).
First-time contributors should also use the detailed
[execution guide](../../../superpowers/plans/2026-11-03-portal-developer-experience-execution-guide.md)
for per-task regression mechanics, commands, expected outcomes and evidence limits.
Read this alongside [local service isolation](local-service-isolation-design.md)
and [process portal ownership](../enforcer/process-portal-design.md).

## 1. Reuse the existing owners

`PortalRuntime` owns group acquisition and shutdown; `ProcessBroker` owns workers,
transport and replacement; `PortalOperationOwner` owns admitted asynchronous work;
`InvocationHandle` separates result delivery from physical execution end. Extend
these owners. Generated operations must not create another executor, scheduler,
broker registry, process pool or independent shutdown protocol.

Prepared calls already use a bounded executor with one dispatcher per worker.
For `STATEFUL_SINGLETON`, that is one dispatcher and one process. Its prepared
queue is the foundation for actor-style use; synchronous `get()` calls retain
their existing overlap rejection. Stateful loss remains terminal for the group.

## 2. Target application surface

The following names are planned additions, not presently available APIs:

```kotlin
PortalRuntime.open(PortalRuntimeConfig.loadBaseline()).use { runtime ->
    runtime.warmUp(listOf(PortalServiceId("parser")))
    val parse = runtime.bind(DocumentParserPortal.PARSE)
    val first = parse.submit(firstBytes)
    val second = parse.submit(secondBytes)
    val documents = listOf(first.awaitResult(), second.awaitResult())
}
```

`DocumentParserPortal.PARSE` is generated host metadata for a declared method,
not an implementation instance, captured closure or executable transferred to
the child. Java uses the same binding and `handle.result()` CompletionStage.
The coroutine extensions are optional at runtime, following enforcer's existing
compile-only coroutine pattern. `awaitResult()` requests cancellation through the
handle; `awaitExecutionEnd()` observes retirement independently.

Additive public contracts:

```kotlin
fun <S : Any, A, R> PortalRuntime.bind(
    operation: PortalOperation<S, A, R>,
): PreparedPortalOperation<A, R>

fun <S : Any, A, R> PortalRuntime.bind(
    operation: PortalOperation<S, A, R>,
    serviceId: PortalServiceId,
): PreparedPortalOperation<A, R>

fun PortalRuntime.warmUp(): List<PortalServiceHealth>
fun PortalRuntime.warmUp(services: Collection<PortalServiceId>): List<PortalServiceHealth>

suspend fun <R> InvocationHandle<R>.awaitResult(): R
suspend fun InvocationHandle<*>.awaitExecutionEnd(): ExecutionEnd
```

The bind/warmUp signatures denote members on `PortalRuntime`; the await functions
are extensions in `io.mazewall.enforcer.api.execution`. Bind uses the existing
unambiguous registry rules. A generated descriptor carries contract class,
canonical method signature, existing method ID, existing service schema identity,
bounded request encoder and result decoder/declared-exception mapping. Its
generated-code construction entry is public only for external generated classes
and marked `@MazewallInternal`. Binding validates metadata against the selected
manifest and resolves no guest class in the host. Host codecs remain trusted host
code outside the child's protection.

Keep Java source access explicit: use boxed interoperability on the existing
selector value classes and the new selector-taking methods, qualified with javac
against published artifacts. Kotlin's `@JvmExposeBoxed` is available from 2.2;
apply it locally with the required opt-in, preserving existing mangled methods.
Use a collection rather than a value-class vararg for warm-up. No module-wide
compiler flag or replacement of the strict selector types is needed.

Use one descriptor per method. One value parameter is its argument type; multiple
parameters get a generated constructor-backed argument class. Zero-argument
methods use a generated singleton argument value. Overloaded descriptor names
include an identifier-safe encoding of their existing method ID; other member
names use KotlinPoet's allocators. Extend `PortalGeneratedNames` for descriptor
holders and argument classes so nested binary names and cross-service collisions
retain the existing centralized checks.
Existing IDs, synchronous stubs, `get`, `prepare`, and failure evidence remain
compatible. Initial descriptors cover copied-value contracts. Direct inbound FDs
are added by P08; annotated InputStream methods retain their existing synchronous
adapter until an equally explicit owned snapshot binding is qualified. Diagnostics
must explain availability rather than fail generation of an otherwise valid
existing service.

## 3. Build conventions and explicit policy selection

An opt-in `portalCodegen.services` container takes a stable registration name,
contract binary name, worker implementation binary name, explicit instance mode
and policy-profile key. Derive service ID/group from the registration name and a
stable project worker-artifact key. Explicit existing registrations/source-set
configuration continue to work; conflicting declarations reject.

For convention users, the plugin creates `portalApi` and `portalWorker` source
sets and an API JAR, then wires API compilation before generation and host/worker
compilation after generation. API dependencies are shared explicitly; host
implementation/runtime-only dependencies never flow into the worker. KotlinPoet
and the already-managed Kotlin compilation remain implementation details even
for Java-authored applications. Do not add an annotation processor or dependency.

`PortalRuntimeConfig.loadBaseline()` is an opt-in convenience for the reserved
`portal-compute` profile: the current required static sandbox, the launcher's default
64 MiB JVM maximum heap through `PortalWorkerProfile()`,
one worker per group, 64 admissions, existing 30-second deadlines, 8 MiB message
and eight-FD limits. It supplies only that profile name and rejects other missing
profiles. This corrects the earlier proposed 256 MiB heap-only baseline: current
custom heap and cgroup memory limits must be configured together. Larger heaps use
an explicit paired profile and delegated cgroup root; preserve that invariant.
Heap maximum is not a process-RSS, CPU or cgroup guarantee. Explicit
resource/launch profiles continue through `load` and existing constructors; no
unknown profile is silently replaced. Preserve every published constructor,
method and Kotlin default bridge when adding configuration overloads.

`portalCheck` validates build metadata and bundles. `portalDescribe` reports
resolved registrations, groups, boundary support and generated-operation names.
Runtime warm-up validates actual containment/readiness. Errors identify service,
group, artifact and failure stage, preserving the cause and bounded child output;
they do not enumerate deployment environment or credential values.

## 4. Submit-time copying, deadlines and admission

For generated value operations, successful `submit(arguments)` takes its encoded
snapshot before returning. Later mutations cannot change the queued request.
The caller must avoid concurrent mutation during the submit call itself. This is
a value-schema snapshot, not a deep clone of arbitrary Java objects.

Create the absolute call deadline before admission and encoding. Acquire the
existing count admission plus a broker-owned byte reservation before allocating
the bounded encoder. A new immutable `PortalSubmissionLimits` configures
`maxRetainedRequestBytes` (default 64 MiB per group). Reserve the worst-case live
request capacity plus bounded encoding scratch/ownership-transfer copies using
checked arithmetic, then shrink to actual retained capacity. Reject excessive
reservations as NOT_STARTED. Clear encoded request references when no longer
needed. Preserve count admission through the existing execution-end/retirement
boundary. Aggregate byte accounting must never be released while an encoder,
transport or queued owner still references the buffer.

Register a snapshot-in-progress phase with the existing operation owner before
encoding on the submitter. Run application getters outside lifecycle locks. Close
stops new snapshots and retains/joins admitted encoders under its deadline; it
cannot publish execution end or free their reservations while host encoding is
still running. On return from encoding, recheck cancellation/close/expiry before
queue publication. An unresponsive host getter leaves cleanup incomplete and
retryable; it never authorizes late guest dispatch.

The shared bounded writer (`PortalRequestWriter`) mirrors the binary `PortalCodec` encoding and
checks byte length, collection count (`PortalCodec.MAX_COLLECTION_ELEMENTS`), aggregate traversal
budget, and `Long` arithmetic overflow before allocation or buffer growth rather than building temporary
byte-array concatenations; generated host stubs (`ProcessBroker.newRequestWriter()`) and worker
dispatchers (`PortalRequestWriter.forReply()`) now stream requests and replies through it, and P03
submit-time snapshots reuse it while retaining admission and deadline ownership. Encoding remains
host computation: a malicious/blocking application getter is not preempted by the child deadline; if
encoding returns after expiry, reject without guest entry.

The request reservation bounds library-owned encoding/queued buffers, not caller
input graphs, decoded responses or results retained in completed futures. A wire
byte limit is not an equal-sized JVM object limit. Examples consume results through
a finite window and release handles/values; P01 measures decoding expansion and
retention separately. CG02/CG04 retain strict supported-shape, count and parse checks;
host application constructors remain outside those allocation guarantees.

The generated path calls the exact bound broker through the same stateful gate
and invocation-retirement context, with one encoding pass. Legacy `prepare`
retains its documented caller-stability semantics. Add a Duration-bounded
`PreparedPortalOperation.submit(arguments, timeout)` overload and a package-private
`submitUntil(arguments, Deadline)` hook for the batch owner; clamp to configured
limits and never renew an existing absolute deadline.

## 5. Warm-up, placement and state locality

`warmUp()` warms all configured groups; the collection overload snapshots selected
service IDs and warms their groups once (an empty selection warms none). Reject
unknown selectors before acquiring children. Reuse
`brokerFor` and `PortalBrokerOwnership` for concurrent warm-up/bind/close. Return
selected health only after actual required readiness. On failure, retain complete
runtime ownership for close and report the failing group; successful prior groups
stay owned. There is no second all-groups transactional lifecycle.

Group membership is explicit shared trust, state and failure scope. Never merge
groups automatically to satisfy a process-count or memory target. Configuration
and examples state the planned total process count (groups times configured
capacity, singleton groups always one). Warm-up initializes workers and their
constructors, not arbitrary application methods or guaranteed JIT steady state.

Stateful prepared calls share the existing FIFO dispatch queue (enqueue order;
concurrent submitter order is unspecified). A worker-local collaborator or model
is constructed once per worker after containment. A completed business exception
does not discard state. Unknown completion, fatal failure or active cancellation
loses the state; queued operations then fail without guest entry. Recreate state
only through explicit application lifecycle action. A binding is a view of the
runtime-owned singleton, not a newly owned process/session on every bind.

`STATELESS` means no affinity/recovery guarantee, not a new instance or erased heap
per request. Each pooled worker still retains its own service graph, statics and
caches. Do not store tenant/request authorization in those fields or depend on a
later call selecting the same worker. Sharing a group shares this trust/failure
boundary; explicit isolation takes priority over memory savings.

Move related hot computations into one worker method or worker-local collaborator
graph. Examples compare `parseAndSummarize` with deliberately chatty calls, keeping
intermediate object graphs in the worker and returning only needed values.

## 6. Batching over ordinary contracts

Use an explicit value method such as `parseBatch(List<ByteArray>): List<Summary>`.
Each chunk is one ordinary generated RPC. A list of separately submitted scalar
RPCs is concurrency, not wire batching. No new frame kind or hidden worker method
is required.

```kotlin
data class PortalBatchOptions(
    val batchSize: Int = 32,
    val maxInFlight: Int = 2,
    val timeout: Duration = Duration.ofSeconds(30),
    val closeTimeout: Duration = Duration.ofSeconds(10),
)
interface PortalBatchCursor<R> : Iterator<List<R>>, AutoCloseable
fun <A, R> PortalBatches.map(
    operation: PreparedPortalOperation<List<A>, List<R>>,
    input: Iterator<A>,
    options: PortalBatchOptions,
): PortalBatchCursor<R>
```

`PortalBatches` is a public object with a Java-static map method. The cursor is
single-caller, lazy, ordered and pulls at most `batchSize * maxInFlight` elements
ahead. Validate positive values, protocol collection bounds, durations and integer
overflow. A batch response must have the submitted cardinality. A single absolute
traversal deadline starts when the cursor is created and bounds chunk
submission/waiting; pass it to `submitUntil`, clamped to each call's configured
maximum. `maxInFlight` counts both unconsumed chunks and invocations with pending
execution end, including those whose result was already yielded. Refill only
after the prior slot is consumed and retired; surface retirement failures before
forgetting a handle. This also bounds retained cleanup handles. No dispatcher
thread, executor or independent process ownership is added.

On failure/timeout stop pulling, request cancellation of outstanding handles, and
preserve the original failure. `close` waits for all outstanding execution ends
under its finite close deadline, retains unfinished handles for retry, and reports
incomplete cleanup. The runtime remains the physical owner. Iterator/user-code
blocking is host work and not forcibly preempted. Chunk failure can mean partial
application effects inside that chunk; no per-item rollback or automatic replay
is promised.

## 7. File capabilities and possible bulk buffers

Add `PortalRuntime.openReadOnly(serviceId, rootId, relative): Capability.ReadFd`
through the selected broker and existing configured roots/openat2 checks. New
generated FD submissions duplicate each direct inbound capability under a bounded
borrow with real `F_DUPFD_CLOEXEC` acquisition evidence. The invocation owns those
duplicates before submit returns and retires them on every terminal path. The
caller may close its original afterward. Track failed pre-native cleanup through
the existing retirement owner; never adopt an invented descriptor number. Extend
that owner to cover submit-time acquisitions and queued cancellation, outside the
current dispatch-only thread-local hook. Even NOT_STARTED must wait for owned
duplicates to retire before publishing execution end.

The duplicate shares the underlying file description/offset and does not freeze
file contents. Examples use a fresh grant per call or positional reads and describe
mutable-file semantics. Returned values remain copied data. FD ownership continues
only broker-to-worker, with neither host paths nor worker-provided FDs as shortcuts.

P11 compares copied bytes with granted regular files, then conditionally prototypes
an immutable broker-created bulk input. Require a reproducible copy-cost crossover
before introducing production shared buffers. Any later production design must
specify backing-object identity, immutability against concurrent writers, bounded
mapping size, one-way grants, worker-death cleanup and lifetime. This plan does not
adopt shared mutable heaps, bidirectional capabilities or shared result buffers.

## 8. Bounded supervision

Offer opt-in `PortalRestartBudget(maxRestarts: Int, window: Duration)`, counting
replacement attempts per stateless group under a monotonic clock. `maxRestarts`
may be zero; the window must be positive. Absence retains current replacement
behavior. Budget enforcement and lifecycle decisions are pure; process work stays
in the existing maintenance owner. Always retire the exact failed worker before
replacement. Each admitted replacement is one attempt, not a retry loop.

Exhaustion stops further replacement and transitions the group to Failed admission;
existing owners remain tracked for cleanup and active calls settle through existing
paths. The triggering call keeps its original failure. Replacement never replays
application work, reloads lost singleton state, weakens readiness, or outlives
runtime close. Existing health/diagnostic failure surfaces carry the reason.
Window expiry permits later attempts only while the group remains Running; a
group already Failed from exhaustion does not revive on a timer.

## 9. Evidence and optimization policy

The comparison uses direct calls, a warmed JDK executor, a packaged Portal and a
persistent loopback HTTP connection to another JVM. Compare the same algorithm,
input and output; use equal binary payloads to isolate transport effects and label
any HTTP/JSON comparison separately. Include no-op, small copied values, bounded
parse/summarize, large inputs, batching and retained-state workloads. Report warm
p50/p95/p99, throughput, CPU, allocation and process RSS separately from startup,
materialization, first call and replacement. HTTP policy and Portal protection are
different and must be disclosed; same-machine HTTP does not establish a WAN claim.

Use fixed offered-load and closed-loop runs, equivalent worker/core budgets, payload
size/concurrency sweeps, several fresh JVM forks and sufficient warm-up/samples.
Benchmark-only oracle checks, environment fingerprinting and observer logging run
outside measured intervals. Production admission, snapshots, encoding/decoding and
reply validation stay inside end-to-end call timing; required artifact hashing and
launch/readiness validation stay inside cold/startup timing. Record environment, exact
artifacts, raw samples, bounds, overload and physical child retirement. Keep the
historical checking harness/dataset unchanged. The existing adoption issue owns
representative external-app SLOs and unfamiliar-developer integration effort.

Optimize measured allocation/copy/syscall costs one at a time. Candidates are the
bounded single-pass codec, internal ownership-preserving transfer of immutable
bytes, coalesced frame writes, and attempting nonblocking I/O before polling on
EAGAIN. Preserve public defensive-copy behavior, framing bytes, one deadline,
partial-frame terminality, cancellation, EINTR and cleanup invariants. Shared
confined arenas and a JVM zygote/fork-after-initialization are not candidates.

## 10. Process-boundary usage safeguards

P13 qualifies these rules in real Java/Kotlin consumers. Existing runtime checks
remain authoritative until the proposed tasks land; code generation cannot prove
business idempotency, callback purity or the absence of application state leaks.

| Developer surprise | Prevention / owner |
|---|---|
| A runtime created per request repeatedly starts JVMs and duplicates caches | One application-owned runtime, reused bindings, explicit `use`/try-with-resources at application shutdown; P05/P12/P13 |
| Static fields, DI objects, thread-local/MDC/security/transaction context are absent or different | Worker-local construction and explicit bounded value fields for required context; no automatic context capture; P02/P13 |
| A normal-looking interface call copies values rather than preserving aliases | Immutable value-oriented contracts; mutation-after-submit controls and clear legacy `prepare` stability rules; CG02/P03/P13 |
| "Stateless" workers retain request data or calls land on different instances | Pure/request-local service methods, explicit singleton mode for affinity, bounded worker-local caches and no per-tenant implicit instance assumption; P07/P13 |
| A timeout/cancelled future is treated as rollback or permission to retry | Preserve NOT_STARTED/COMPLETED/UNKNOWN_COMPLETION, no automatic replay, application idempotency/reconciliation where effects are authorized; P03/P04/P09/P13 |
| A completed business exception is assumed to undo worker state changes | Explain that completed describes evidence, not a transaction; singleton remains usable but application invariants are its responsibility; P07/P13 |
| Cancelled results are mistaken for child death; close errors are ignored | Observe execution end separately; bounded close with retained retryable cleanup; no dropping runtime/handles while retirement is pending; P03/P04/P13 |
| Host crash or SIGKILL is assumed to run close and stop all workers | P13 qualifies abrupt host death during startup, idle, hung calls and guest background work; observe actual worker death and name any required deployment supervisor |
| Host code synchronously waits on work queued behind itself | No host callbacks across the contract; avoid blocking mappings/completion callbacks or holding application locks while waiting; no arbitrary deadlock-detection promise; P03/P13 |
| Dependencies assume sockets, writable paths, temp extraction or subprocesses | Curated worker dependencies and actual workload startup/denial fixtures under the fixed policy; file inputs use granted FDs; P02/P05/P08/P13 |
| Host containment is installed before worker startup, or later prevents replacement | Qualify the entire spawn/replacement lifecycle under the chosen host policy; warming first does not authorize future spawns; P05/P09/P13 |
| Kernel/JDK/container support or cgroup delegation is missing | Static `portalCheck`, real startup/readiness, then an explicit side-effect-free representative workload check if needed; preserve fail-closed errors; P02/P05/P13 |
| Many groups, workers, queued arguments or retained results exhaust memory | P12 aggregate capacity budget, P03 byte admission, P06 finite result window, CG06 early encoding bounds, and P01 whole-deployment memory evidence |
| A granted FD is treated as immutable bytes | Duplication shares file offset/live contents; fresh grants or positional reads, explicit close and mutation semantics; P08 |
| A stack trace/log message lacks the failing boundary | Service/group/method, phase, worker identity, generation and invocation evidence; bounded existing diagnostics, no payload or arbitrary environment logging; P02/P13 |
| A separate process is assumed to sanitize all launch authority | Existing explicit operator launch contract; no new environment/credential inspection or automatic propagation; P13 |

Use DTO fields for correlation identifiers and domain-required context. They are
ordinary data, not an automatically authenticated principal or inherited transaction.
Host codecs/constructors/mappings run in the host; only worker methods run under the
worker policy. Result consumers must still validate domain meaning before taking
privileged host actions. Background tasks created by the guest are not joined merely
because a method returned; they retain group lifetime and can increase CPU/memory use.

Abrupt-host-death cleanup is an explicit P13 qualification gap, not a reproduced
orphaning finding. The worker driver handles peer closure between dispatches;
that does not establish interruption of a hung guest call or termination of other
non-daemon threads. Ordinary broker close/reaping controls do not prove cleanup
after the broker itself dies. A surviving controller must observe worker death with
bounded rescue cleanup; a rescue kill fails the control. Any required deployment
supervisor is declared and tested separately. A failing case blocks the corresponding
cleanup claim pending a scoped correction and requalification.

P13's guide distinguishes compile-time rejection, startup validation, runtime checks
and application responsibilities. It introduces no generic context propagation,
distributed transactions, hidden retries or per-request child JVMs.

## 11. Memory model and aggregate capacity

### Costs and limits

A process is more expensive than a thread in the same JVM. Workers have independent
heaps, class metadata, JIT code, GC bookkeeping, native allocations and thread stacks.
Mutable application caches/models are copied per worker. The host also retains its
application, input/output graphs and IPC buffers. Moving a workload out can remove
some host allocations, but a second process does not automatically unload the old
host graph or make its heap shrink promptly.

A planning model is:

```text
total deployment memory ≈ host after moving the workload
                        + all workers' private resident memory
                        + shared resident pages counted once
                        + kernel/IPC overhead
```

Shared libraries, file-backed pages and compatible CDS archives can reduce physical
duplication. Ordinary mutable heaps, per-worker caches and independently generated
JIT code remain separate. Bundle size on disk, maximum heap, reserved virtual address
space, RSS, PSS and cgroup-charged memory are different quantities.

The current default launcher supplies `-Xmx64m` unless the selected launch overrides
it. This is a maximum Java heap, neither an immediate 64 MiB allocation nor a total
process limit. Eight such workers have 512 MiB of combined nominal maximum heaps,
plus non-heap and host costs; that arithmetic is not a measured resident footprint.
The current profile requires an explicit heap/memory pair and at least 32 MiB of
headroom. That floor is validation, not evidence that every JVM/workload fits.
The proposed convenience profile uses the existing default instead of inventing a
256 MiB heap-only configuration.

For hard worker memory containment, configure the existing per-worker cgroup v2
limit and delegated root. Account for the host and siblings within the enclosing
deployment limit too. `memory.max` covers cgroup-charged memory and may trigger OOM
termination; it is not a promise that work succeeds, an exact RSS ceiling, or a swap
limit. Swap policy remains deployment-owned. A missing required controller/delegation
still fails startup rather than silently dropping the limit.

Current launch attaches a running child before guest readiness. Cgroup migration
does not move all existing memory charges, so bootstrap allocations can remain
charged to the parent. Include startup and those charges in parent limits and
measurement; the child ceiling is not a complete accounting of its lifetime cost.

Request/response graphs can coexist with source values and encoded/transport copies.
For scale intuition, 64 retained 8 MiB buffers alone occupy 512 MiB; nested decoded
objects and caller-held results add more. P03's proposed 64 MiB request reservation
is per group and includes library scratch/copy accounting, not total application
memory. Multiple runtimes/groups multiply budgets. Use a small measured worker pool,
bounded submissions/results, compact DTOs, worker-local intermediate computation,
and file grants for appropriate large inputs. Never merge trust groups automatically.

### P12 proposed capacity contract

Add a pure `PortalRuntimeConfig.capacityPlan(): PortalCapacityPlan` and an additive
Java-static `PortalRuntime.open(config, budget: PortalCapacityBudget)` overload.
Existing open/config constructors and default bridges retain their semantics.

- `PortalCapacityBudget(maxWorkerProcesses: Int, maxConfiguredWorkerMemoryBytes: Long? = null)`
  requires a positive process count and a positive byte bound when supplied.
- `PortalCapacityPlan` owns immutable per-group entries, `totalWorkerProcesses: Long`
  and `configuredWorkerMemory: PortalWorkerMemoryTotal`. Entries report group, mode,
  policy profile, planned worker count and configured per-worker cgroup memory limit.
- `PortalWorkerMemoryTotal` is closed: `Limited(bytes)` or `Unbounded(groups)`.
  Missing per-worker memory limits are never represented by zero or guessed from Xmx.
  Construction of returned report state is restricted to the validated planner.
- Deduplicate by validated isolation group. Count one process for a singleton and
  `limits.workers` for a stateless group, across all configured groups including lazy
  ones. Use checked `Long` addition/multiplication; an empty configuration totals zero.
- Budgeted open validates this report before broker creation/child acquisition.
  Excess processes, excess configured memory or an unbounded group with a finite
  memory budget reject with the responsible group/limit. It does not start probes,
  resize pools, rewrite policies or merge groups.
- The memory sum is a sum of configured worker ceilings, not an OS reservation or
  observed footprint. A deployment still supplies host/IPC headroom and parent limits.
  Reports are per runtime; two runtimes/processes do not share a global allocator.

`portalDescribe` can show build-known group topology and label runtime-selected
capacity as unresolved. It must not execute application configuration to invent a
numeric memory report. Runtime `capacityPlan()` uses the actual immutable config;
`health()` remains the existing observational surface for live worker/cgroup usage.

### Measurement and acceptance

P01 adds a separate memory sampling pass: no-worker host baseline, cold startup,
warm idle, useful busy calls, bounded queue saturation, consumed versus deliberately
retained results, and replacement/close. Sweep one/two/four total workers and
one/multiple groups without a full Cartesian latency matrix. Record loaded workload
state/caches, heap caps, pool count, queue limits, GC/JDK identity and sampling interval.

Read host and worker RSS/PSS/private memory through authorized `/proc/<pid>/smaps_rollup`
or equivalent. Report per-process RSS and summed PSS with collection scope; summing
RSS double-counts shared pages. Also report appropriate parent/worker
`memory.current`, `memory.peak` where available and `memory.events`; never add a parent
cgroup to its children as if disjoint. Unavailable/denied measurements are explicitly
unavailable, not zero. Sampled peaks may miss transients; disclose that limit.

NMT is optional for separate qualified diagnostic runs: it needs startup enablement,
adds overhead and does not cover all native allocations. Worker attach tools may be
unavailable under containment; measurement never relaxes the policy. Compare Portal
and HTTP as whole deployments at equal worker/workload budgets; both add server JVMs,
so neither a similar nor a lower footprint is assumed. Existing packaging work owns
slimming dependencies; P10 may examine compatible CDS use only after a measured cost,
with generation/allowlist checks preserved and no JVM fork/zygote design.

## Memory sources consulted

- [JDK 25 native memory tracking](https://docs.oracle.com/en/java/javase/25/vm/native-memory-tracking.html): JVM categories, coverage limits and diagnostic overhead. Context7 retrieved `/openjdk/jdk25u-dev` (JDK 25 update branch), not an exact installed vendor patch.
- [JDK 25 class data sharing](https://docs.oracle.com/en/java/javase/25/vm/class-data-sharing.html): shared read-only metadata and conditional memory savings.
- [Linux procfs memory accounting](https://docs.kernel.org/filesystems/proc.html): RSS, proportional set size and `smaps_rollup`.
- [Linux cgroup v2 memory controller](https://docs.kernel.org/admin-guide/cgroup-v2.html): memory usage/limits/events and distinct swap accounting.

## Pattern sources consulted

- [Chromium Mojo services](https://github.com/chromium/chromium/blob/main/docs/mojo_and_services.md): typed async interfaces and endpoint lifetime.
- [Chromium process model](https://github.com/chromium/chromium/blob/main/docs/process_model_and_site_isolation.md): compatible process reuse and spare workers.
- [Python 3.14 executor documentation](https://docs.python.org/3.14/library/concurrent.futures.html): process pools, initializers, chunking and bounded map submission.
- [Ray 2.53.0 actors](https://github.com/ray-project/ray/blob/ray-2.53.0/doc/source/ray-core/actors.rst): reusable task workers versus state-affine actors.
- [Erlang/OTP 29.1.2 supervision](https://github.com/erlang/otp/blob/OTP-29.1.2/system/doc/design_principles/sup_princ.md): owned children and restart intensity.
- [kotlinx.coroutines 1.10.2 future integration](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/jvm/src/future/Future.kt): future cancellation versus an external invocation owner.
- [Kotlin 2.2 Java interoperability addition](https://kotlinlang.org/docs/whatsnew22.html#improved-java-interop-with-inline-value-classes): local boxed entrypoints for value classes. Context7 returned the official unversioned site and its 2.2 introduction, not a 2.4-specific snapshot; qualify on the repository's pinned producer/consumer compilers.
