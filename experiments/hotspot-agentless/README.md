# Agentless HotSpot event profiler experiment

This is the test/evidence harness for the [canonical collector](../../profiler/hotspot-agentless/README.md).
Runtime packages, module pins, native gadget and overlays are source links to
that single tree; preparation and collector builds delegate there. Benchmark,
oracle, stress fixtures and historical results stay here. The canonical module
retains its existing Go import namespace for internal-package compatibility.
The current implementation covers dependency/preflight verification, live
event-triggered capture and **typed, bounded enrichment**. The `--capture` mode
emits versioned event, terminal enrichment and session-summary JSONL records.
Lost-first-definition recovery now has a controlled live correctness gate.
The controlled-C2 inline corpus verifies two distinct paths by exact operation
tokens and checks real compiler inline evidence.
The broader fidelity/runtime matrix and Kubescape remain gated.

The [local Kubescape contribution proposal](../../docs/plans/2026-10-03-hotspot-kubescape-contribution-proposal.md)
collects the genuine event/frame sample, measured runtime/pressure evidence and
independent patch review units. Its pinned source inspection identifies a
Gadget-bridge/dependency mismatch and a storage-schema gap; node-agent wiring
awaits the selected event/data/dependency contract. No upstream submission or
compatible node-agent build is claimed.

## Build and verify

```bash
cd experiments/hotspot-agentless
make verify
make container-build
```

Live preflight acceptance/refusal test (a privileged collector and an
ordinary-user HotSpot fixture, both removed after the test):

```bash
COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock make preflight-test
```

## First live Java stack

```bash
DOCKER_HOST=unix:///var/run/docker.sock \
COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock make demo
```

This uses Gadget's maintained image builder and a privileged collector with
host PID visibility. Java runs as the caller's ordinary UID from the pinned
Temurin image. The fixture starts behind a gate; after probe attachment, it
issues 400 marked file opens along two paths. Acceptance requires the event's
own OTel correlation reference and an `InlineOpenFixture` application frame.
Captured cold-start/unresolved records remain in JSONL. This first gate does
not prove full inline reconstruction, zero loss, or first-definition recovery.

Build outputs include `build/gadget-image.ref` with the actual built OCI digest.
The collector resolves that digest directly from the local read-only OCI store;
it never falls back to a mutable tag. Each run retains its JSONL and collector
log in a new `build/capture.*` directory and cleans up the containers it owns.

All container images are digest-pinned in `dependencies.lock.json`. `go.sum`
locks module content. `make graph` records the original graph in
`build/modules-base.json` and the build-selected patched graph in
`build/modules.json`. The root `go.mod` carries Gadget's required profiler and
notation replacements explicitly. Do not use upstream `main` as a build input.

`make deps` verifies the original modules, checks the locked Gadget and profiler
checksums, applies scoped patches without fuzz to generated sources under ignored
`profiler/hotspot-agentless/build/deps/`, and writes temporary module replacements
for this harness. The patch/addition
bundle hash is embedded in the collector and preserved in every terminal result.
The module cache is never edited. Build/test through Make, which selects
`build/experiment.go.mod`; direct `go test ./...` against the unpatched upstream
module does not contain the proposed structured API.

If your configured Docker endpoint is not running, select the existing daemon
explicitly without changing your configuration:

```bash
make container-build DOCKER_HOST_ARG='--host unix:///var/run/docker.sock'
```

## Structured evidence and limits

- `event`: occurrence ID and separate OTel reference; host PID/TID, process
  incarnation, cgroup ID, BOOTTIME timestamp, raw filename bytes and read status.
- `enrichment`: one terminal outcome for that event, with ordered typed provider
  frames or an explicit unavailable/skipped reason. Native addresses, source
   lines and HotSpot raw positions remain distinct. HotSpot frames include scope
   provenance, BCI state and scope-walk status; BCI is present only with explicit
   observed-in-range evidence. Fallback and invalid inputs never become BCI zero.
- `session_summary`: delivered-event terminal accounting, observed queue maxima,
  forwarded Gadget loss reports, and owned OTel trace-reader lost/empty/error
  counts plus actual perf data-buffer bytes per CPU. Both observations explicitly
  have `complete=false`; whole-session `event_loss` and `definition_loss` remain
  absent. Complete terminal accounting does not establish complete collection.

Full capture health is sampled after cancellation, pending enrichment completion and a bounded
join of the owned definition reader/consumer, before closing the symbolizer.
`definition_stop_observation` reports those joins separately from
`buffers_drained=false`. A two-second stop timeout is a session error; the summary
labels incomplete joins as a partial snapshot. There is no producer/reader drain barrier. Gadget's
lookup/reset loss polling can race producers, so its forwarded reports are a
lower bound. OTel counts consumed perf records only, independently sampled;
unread losses and producer submit failures remain outside this snapshot. Empty
records and reader errors are reported separately, not converted into a count
of missing definitions. Zero observations never certify zero whole-session loss.

The [queued-tail characterization](results/queued-tail.md) demonstrates why:
reader Close and receiver join leave published records unconsumed, while native
Flush preserves them after producer quiescence. Run the race-enabled control with
`make queued-tail-kernel-test DOCKER_HOST_ARG='--host unix:///var/run/docker.sock'`.
The [bounded finalization design](../../docs/plans/2026-10-02-agentless-finalization-design.md)
specifies explicit producer/reader/callback ownership gates; it is proposed,
and full-mode buffer drain/whole-session loss-finality acceptance remains open.

### Opt-in owned event-only stop

`--capture --collection-mode events --drain-events` enables the supported
event-only finalization path. For strict structured full capture,
`--capture --collection-mode full --hotspot-identity=safepoint-epoch --drain-full`
additionally drains owned definition perf readout/final consumption and finishes
bounded enrichment before retirement under the same stop budget. See
[`results/full-definition-finalization.md`](results/full-definition-finalization.md)
for supported runtime/profile, actual acceptance and partial-loss limitations.

The events-only option enables the supported
single-openat-ring finalization path. It retires the writer, flushes and joins the
original reader/callbacks, samples retained native reserve counters without a
background reset poller, and retires owned readers/maps/programs. A component
`event_finalization_observation` names achieved phases and failure; all native
loss observations remain partial and final event/definition loss remains absent.
Full, strict-epoch and debug-rendered capture cannot opt into this first gate.

```bash
COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock bash scripts/run-benchmark.sh \
  --operations 1200 --warmup 600 --repeats 3 --modes baseline,events \
  --diversity 8 --pause-ns 0 --drain-events
DOCKER_HOST=unix:///var/run/docker.sock make event-finalization-kernel-test \
  DOCKER_HOST_ARG='--host unix:///var/run/docker.sock'
```

The one shutdown budget is two seconds within the remaining owned 60-second
session cap. Permission/read/output/retirement failures remain errors; timed-out
active callbacks keep their resources owned and prevent a false final summary.
See [event-only finalization evidence](results/event-only-finalization.md) for
real queued-tail, detach, native pressure, timeout and exact-discard controls.
Producer capacity loss remains possible even when buffer drain succeeds.

## Three-mode measurements and event-loss control

```bash
DOCKER_HOST=unix:///var/run/docker.sock make benchmark-fixture benchmark-report build
COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock bash scripts/run-benchmark.sh \
  --operations 1200 --warmup 600 --repeats 3 --pause-ns 500000
COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock bash scripts/run-benchmark.sh \
  --operations 1200 --warmup 600 --post-attach-warmup 600 \
  --repeats 3 --diversity 8 --pause-ns 500000
DOCKER_HOST=unix:///var/run/docker.sock COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock \
  make event-loss-control DOCKER_HOST_ARG='--host unix:///var/run/docker.sock'
```

Build the normal gadget with `make gadget` if `build/gadget-image.ref` is absent.
Modes run serially in rotated order with the same pinned interpreter/Serial JVM:
`baseline` has no collector, `events` disables both stack helpers and OTel startup
and emits explicit `unwind_disabled` skips, and `full` uses strict epoch enrichment.
Measured file tokens establish missing operations independently of native loss
reports. Terminal counters must match delivered JSONL outcomes. Events mode
rejects definition-profiler observations and enrichment claims.
Admission count/byte limits emit explicit `skipped` terminals with their original
reason. A native loss observation accompanying a valid ring event is reported
without discarding that event; empty/loss-only reads emit no fabricated event.

`--warmup` happens **before attachment**. Optional `--post-attach-warmup` issues a
fixed, separately tokenized preparation workload after attachment, paced at
500,000 ns. It is excluded from measured latency/throughput. In full mode the
measurement gate requires an ordered leaf/route segment with checked even epoch
for every configured route, with a five-second observation wait after preparation.
`readiness-before-measurement.json` preserves that actual pre-gate observation;
the final report recomputes preparation coverage separately. Preparation runs in
all modes; only full mode has a profiler-readiness gate. This does not promise
future coverage or loss completeness. Default zero preparation retains cold runs.

CPU/RSS samples cover the collector process from startup/attachment through target
completion, including discovery and optional preparation, rather than workload-only
CPU overhead. Kernel memory is unmeasured. Raw proc samples, exact runtime/image/
source/class hashes, target timing and per-run reports remain in `build/benchmark.*`.
Rejected runs retain reports and cannot produce a passing aggregate. The isolated
loss gadget discards measured token 42 after sequence allocation; its gate requires
that **exact** missing token, not merely one missing operation.

See [loss/readiness/measurement evidence](results/loss-benchmark.md): the paced
ready control passed, but cold useful coverage and repeated warm burst collection
failed their gates. These measurements are not a production performance envelope.

The subsequent [event-read loss investigation](results/event-read-loss.md) fixed
an extra valid-event discard in Gadget's loss-report path and an admission-skip
label mismatch. Three identical ready-burst repeats captured all 1200 tokens;
a larger 10000-operation pressure diagnostic still correctly rejected 4569
missing tokens and retained 735 explicit enrichment skips. The isolated native
pressure control can be run with `make event-read-kernel-test`.

Native LRU capacity and existing pruning characterization:

```bash
make kernel-cache-test DOCKER_HOST_ARG='--host unix:///var/run/docker.sock'
```

This privileged test exercises real LRU eviction with the pinned provider's
256-entry map layout and retains the existing first-absence grace cycle.

Defaults are four workers, 1,024 pending events including active lookups,
16 MiB pending serialized-event payloads, and a one-second absolute BOOTTIME
deadline. Lookup waiting also respects Gadget's existing adaptive 800 ms limit.
These limits do not cap OTel's caches, maps, frames or total collector RSS.
Provider waits do not block event intake; output I/O can exert backpressure,
and output failure is fatal. A pidfd terminates the session on target exit;
the BPF process-leader birth-time filter prevents PID reuse from being admitted.

Low-rate pressure correctness check using the same fixture:

```bash
COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock \
  bash scripts/run-demo.sh --max-pending 1
```

This deliberately skips enrichment when the one-event budget is occupied while
retaining event records and terminal reasons. It is not a capacity benchmark.
The old rendered gate remains available through `--debug-rendered`.

## HotSpot quality metadata

`profiler-quality.patch` attaches comparable value metadata to existing interned
frames. Per-frame `scope_lookup` distinguishes `interpreter`, `exact_pc`,
`preceding_pc`, `method_only`, `stub` and `unknown`. `scope_walk` is separately
`returned`, `partial` or `unknown`; it describes the decoder's scope chain, not a
complete native/Java walk. Stack scope is `mixed` for differing known Java modes,
or `unknown` when any Java frame lacks quality. Stack walk never says `complete`.

`bci_state=observed_in_range` allows optional `bci`, including a genuinely observed
zero. `unavailable`, `out_of_range` and `unknown` omit BCI. `raw_bci` retains a
captured interpreter index or decoded scope index before legacy range fallback;
compiled positive encoded values retain the pinned provider's existing decrement.
Encoded scope zero is unavailable, and interpreter `0xffff` is invalid. These
are provider range/provenance observations, not semantic opcode-boundary checks.
The legacy raw position/source line remains visible even when it is approximate.

```bash
make quality-fuzz
DOCKER_HOST=unix:///var/run/docker.sock \
COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock \
MW_CAPTURE_GATE=TestHotSpotQualityMetadataIsDelivered make demo
COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock \
MW_JAVA_XINT=true MW_CAPTURE_GATE=TestHotSpotQualityMetadataIsDelivered \
  bash scripts/run-demo.sh
```

The fuzz target compiles with the experiment's selected module graph and executes
bounded mutations without coverage guidance. The live gate checks actual fixture
quality and retains unknown/invalid evidence. The harness uses the prepared
modfile; fault builds select `MW_CAPTURE_MODFILE=build/recovery-test.go.mod`.

Raw decoder diagnostics are exported for retained partial frames. The upstream
process manager also logs symbolization failures and appends an unknown frame;
failures before any frame is emitted do not carry their raw error into this
structured API. The interpreted method cache ignores its captured check argument;
the quality patch alone does not establish interpreter incarnation identity.
The subsequent `profiler-identity.patch` validates the captured interpreted
fingerprint and the cached/live full ConstMethod pointer using checked reads
before and after lookup. Zero is checked explicitly, not treated as absent.
Mismatches/read failures produce unavailable method evidence; the process manager
may retain other frames and its existing unknown placeholder. The producer's
32-bit fingerprint and existing frame-cache reuse still cannot distinguish all
pointer-reuse/ABA cases. Full inline fidelity, class-unloading correctness and
memory/overhead measurements remain separate acceptance gates.

The disposable-loader investigation now reproduces wrong-generation attribution
in the normal collector after confirmed unloading. Stable-workload corpus success
does not establish identity across unloading/reuse. See
[`results/identity-investigation.md`](results/identity-investigation.md) for the
failing live evidence and the producer-reference reuse mechanism.

### Scoped safepoint-epoch identity

`--hotspot-identity=safepoint-epoch` enables a verified lifetime boundary for the
locked Temurin **interpreter-only, Serial-GC** profile. Startup checks the mapped
JVM SHA-256/build ID and live `UseCompiler`/`UseSerialGC` flags. Unsupported
profiles are refused. Sessions are capped at 60 seconds.

The event and producer independently bracket stack capture with the JVM's
64-bit safepoint counter. Producer references and HotSpot frame-cache entries
are separated by captured epoch. Deferred decoding uses immutable trace-local
readers, checked before/after epochs and abort-time invalidation of the existing
method, Symbol, JIT and stub-name caches. Captured identity remains separate from
validation outcome. Already validated historical definitions remain immutable.

The collector attaches an owned `sched_process_exec` barrier before discovery;
one monotonic session poison word prevents post-exec lookup acceptance. Native
permission/unexpected errors and identity conflicts reach a required session
fatal owner even when no event performs a lookup. The existing queues, capacities,
deadlines, buffers and two-cycle pruning remain in use. Binary capture ABI is now
version 2; both embedded BPF objects and C/Go layouts are regenerated with the
existing pinned builder and verified by BTF. New JSON identity fields are additive.

```bash
DOCKER_HOST=unix:///var/run/docker.sock \
COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock \
  make unloading-epoch DOCKER_HOST_ARG='--host unix:///var/run/docker.sock'

DOCKER_HOST=unix:///var/run/docker.sock \
COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock \
  make recovery-epoch DOCKER_HOST_ARG='--host unix:///var/run/docker.sock'
```

Three independent strict runs each resolved 1,580/1,600 application segments,
with 20 cold-start unavailable, all 80 generations confirmed unloaded, and zero
wrong-generation labels. Every branch in generations 1–79 resolved all ten
operations. The non-epoch control still reproduces stale attribution. See
[`results/identity-investigation.md`](results/identity-investigation.md) for exact
artifacts and provenance. This profile does not certify concurrent collectors,
compiled-code reclamation, semantic BCI, complete collection or arbitrary JDKs.

## Lost-first-definition recovery

```bash
DOCKER_HOST=unix:///var/run/docker.sock \
COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock make recovery
```

A separate, provenance-labelled test binary drops exactly one genuine `pathA`
definition after symbolization, leaving its BPF cache entry resident. An
interpreter-only JVM keeps the path stable. Acceptance requires reference
reuse, targeted invalidation, and correctly attributed subsequent operations
after the recorded recovery clock. Original unavailable records never change.
Fault injection is absent from the normal collector binary.

After a terminal unavailable result, an existing enrichment worker scans the
existing Gadget LRU for the exact PID/TID/reference, with a 50 ms context budget
and an entry-count bound. Deletion requires a complete scan; late definitions
and already-replaced references are retained. No recovery pool, retry queue or
second stack cache is added. `recovery` JSONL records and summary counters report
each action. Permission failures are fatal; iterator aborts/expiry are explicit
unsuccessful attempts. LRU capacity, prune policy and buffers remain unchanged.

Linux has no compare-and-delete for this LRU value. A concurrent
eviction/republication race can cause an additional availability miss, never
substitution of another ID's frames; the next operation republishes normally.
This gate does not certify every transport-loss boundary or the full runtime,
JIT and concurrent-thread matrix.

The harness permits bounded cold startup: 30 seconds for attachment and a
60-second collector lifetime cap. The target pidfd ends a completed fixture
session immediately.

## Deterministic controlled-C2 inline corpus

```bash
DOCKER_HOST=unix:///var/run/docker.sock \
COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock \
  make inline-corpus DOCKER_HOST_ARG='--host unix:///var/run/docker.sock'
```

This separate fixture retains the original 400-event demo/recovery workloads.
The digest-pinned JDK compiles Java with debug information as the caller's ordinary
UID. The target uses `-XX:-TieredCompilation`, `-Xbatch`, threshold 100, compiler
logging, non-inlined branch roots and an explicit inline command for `sharedLeaf`.
The log must show a successful active **C2** compilation of both roots with actual
`sharedLeaf` inline success; command-line intent or source nesting is insufficient.
Compilation task and nmethod IDs must agree, and the log's PID must match capture.

After 3,000 unmarked warmup operations per path, the fixture predeclares 1,000
operations per path in `operations.csv`, each with a unique
`(run,worker,sequence,branch)` filename. The collector attaches before this marked
phase. A single platform worker then opens each path once with 10 ms between
pairs. Java completion checks the bytes read; a one-second tail is only a bounded
async-reader opportunity, not a producer/drain barrier.

Acceptance pairs captured filenames exactly with the manifest and requires one
terminal outcome per delivered event. Wrong process/incarnation, duplicate
operation/event/terminal, unexpected or missing token, wrong branch, duplicated
application frame, reversed leaf/root order and unsupported quality claims fail.
At least one compiled `sharedLeaf → pathA` and `sharedLeaf → pathB` application
segment is required. Every unresolved/missing application segment stays explicit;
successful provider symbolization alone is not counted as resolved application
coverage. Non-corpus events are kept in the raw session accounting.

Artifacts include `operations.csv`, `compiler.xml`, `events.jsonl`, `target.log`,
`inline-report.json` and `corpus-environment.json`. Provenance includes JVM flags,
pinned images/modules, kernel, target UID/PID and class/source hashes. The test
validator bounds manifest, compiler XML and capture input sizes. Kernel loss
observations remain partial even when every intended corpus token was observed.

This is a controlled diagnostic configuration and a selected-segment correctness
gate, not the full runtime/thread/class-unloading matrix, semantic BCI oracle,
USER_NOTIF reference comparison or production overhead envelope. Default tiered
and interpreter-only behavior continue to have independent quality gates.

## Runtime and platform-worker corpus matrix

```bash
DOCKER_HOST=unix:///var/run/docker.sock \
COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock \
  make corpus-matrix DOCKER_HOST_ARG='--host unix:///var/run/docker.sock'
```

This runs C2, tiered and interpreter-only modes with 1, 4 and 16 platform workers,
keeping 1,000 global sequence tokens per path in each case. Worker assignment is
`sequence % workers`; the filename/manifest carries the worker number. All workers
are created before attachment and gated together. A worker's captured OS TID must
remain consistent and distinct from every other worker; every worker must have
application-segment coverage on both paths. This mapping check is not a separate
JVM-to-OS thread oracle.

Only C2 mode requires active compiler-inline proof and compiled segments for every
worker/branch. Tiered mode has compiler logging but no threshold/forced-inline
commands; interpreter mode has `-Xint` and cannot claim compiled application
frames. Their reports do not inherit the controlled-C2 inline guarantee.
`MW_CORPUS_WORKERS=4` or `16` and `MW_CORPUS_MODE=tiered` or `interpreter` also work
with `make inline-corpus`. The matrix retains each capture and emits a combined
`matrix-report.json`; unobserved tokens or wrong attribution fail immediately.
Unresolved application segments remain explicit even when all operations arrived.

## Independent USER_NOTIF application-segment reference

```bash
DOCKER_HOST=unix:///var/run/docker.sock \
COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock \
  make oracle DOCKER_HOST_ARG='--host unix:///var/run/docker.sock'
```

Candidate capture finishes before a separate reference JVM uses the existing
public `Profiler.profile(Callable, false, true)` API. The reference opens 1,000
distinct files per branch and requires exact path coverage and ordered
`sharedLeaf → pathA/pathB` Java frames, no duplicates/wrong branches and complete
reference coverage. The candidate never has this reference profiler injected.
The comparison is between agreed application segments across separate executions,
not the same physical syscalls. StackTraceElement cannot establish BCI equality;
the differential report explicitly leaves that unverified.

The digest-pinned Temurin JDK is exported to ignored content-addressed build
storage for an ordinary-user host reference process. A Gradle initialization
script supplies the existing profiler runtime classpath without modifying that
module or its APIs. This initialization uses `--no-configuration-cache` because
its temporary classpath task wiring is not configuration-cache serializable.
The reference retains raw Java stacks, the profiler's serialized coverage/behavior,
its report, JDK version and source/class/image/UID/kernel provenance. Candidate
and reference coverage remain separate; reference success does not upgrade
candidate unavailable frames or kernel-loss accounting.

## Preflight

Use an ordinary-user target's **host PID**, while running the collector with
appropriate host privileges:

```bash
bash scripts/preflight.sh --pid 12345
```

This emits JSON and exits nonzero on refusal. Checks include Linux x86-64,
readable kernel BTF, an exact probe-name match, the syscall wrapper's BTF
`pt_regs *` argument, target UID/starttime and readable process metadata, and
actual loading/closing of a temporary BPF program. It does not attach a tracing
probe, change host security settings, or claim that HotSpot unwinding works.
`ready_for_attach_test=true` authorizes attempting the next test, not claiming a
working profiler. Native permission failures remain visible in the report.

For a dedicated test host, the preflight binary can run in a privileged collector
container with host PID visibility and read-only host sysfs. The target remains
unprivileged. Use the Go image digest from the lock:

```bash
docker run --rm --privileged --pid=host \
  --mount type=bind,src=/sys,dst=/sys,readonly \
  --mount type=bind,src="$PWD",dst=/work,readonly \
  --entrypoint /work/build/mw-hotspot-demo \
  golang@sha256:fb612b7831d53a89cbc0aaa7855b69ad7b0caf603715860cf538df854d047b84 \
  --preflight --pid 12345
```

## Correctness and reuse boundaries

- Capture the operation and stack reference at the same `openat` handler hook.
- A handler-entry observation does not establish open success or universal
  syscall-attempt coverage.
- Reuse Gadget's LRU, pruning, waiters, process lifecycle and buffer settings.
- Prove lost-first-definition recovery before deduplication tuning or high-rate
  benchmarks. Original timed-out events stay unavailable; recovery is for later
  operations, with no substitute stacks.
- Keep typed frames, optional BCI, source lines and addresses distinct.
- Initial runtime: pinned Temurin JDK 25 and platform threads. Installed GraalVM
  is a separate validation target.
- Existing MazeWall Tier E and USER_NOTIF remain unchanged.

See `../../docs/plans/2026-09-29-agentless-hotspot-kubescape-plan.md` for the
general plan and `patches/README.md` for upstream patch status.
