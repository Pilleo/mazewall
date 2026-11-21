---
document_type: execution_plan
title: "Portal Developer Experience Implementation Execution Guide"
base_revision: b2c9b335720fd0491a8d86917a3b0593b818a4ec
---

# Portal developer experience: implementation execution guide

**Status:** Instructions for future implementation, not evidence that the changes
exist. All CG01–CG07 and P01–P13 implementation checkboxes remain open.

**Audience:** Developers who know Java/Kotlin but are new to this repository,
generated code, Linux process ownership or performance measurements.

**Goal:** Make each approved CG/P issue executable as a small, testable change,
with enough concrete preparation, regression and evidence guidance for a junior
contributor to know what to do and when to request a scoped review.

**Architecture:** Preserve the main plan's existing runtime, broker, codegen and
retirement owners. This companion adds implementation instructions and test
mechanics only; it creates no application architecture or new lifecycle owner.

**Tech Stack:** The repository's existing Kotlin/Java, JDK 25 toolchain with JDK 22+
compatibility obligations, Gradle/TestKit, JUnit, Unix IPC and Linux containment.
No new dependency is introduced by this guide.

**Spec:** [Thread-like Portal developer experience](../../internals/designs/portal/portal-developer-experience.md).

**Read with:** The [implementation plan](2026-11-03-portal-developer-experience.md)
defines scope, exact file inventories and dependencies. The
[design](../../internals/designs/portal/portal-developer-experience.md) defines the
intended public contract. The [codegen review](../../internals/reviews/2026-11-03-portal-codegen-hardening.md)
records existing defect evidence. This guide explains how to work through them.
It does not authorize extra features, worktrees, agents, publishing or merges.

## Start here

1. Pick **one** issue from the plan's dependency table. Read its acceptance criteria,
   its plan section and its work card below. Do not implement the whole roadmap in
   one change. A card's steps are ordered; its tests are part of the deliverable.
2. Read root `AGENTS.md`, `.agents/CODE_QUALITY.md`, and the nearest module's
   `AGENTS.md`. Re-read them in the checkout you actually use; this plan has a
   historical committed anchor and was written over existing uncommitted work.
3. Inspect the checkout without changing it:

   ```bash
   git status --short
   git diff --stat
   git diff --cached --stat
   git rev-parse HEAD
   java -version
   ./gradlew --version
   ./scripts/adkw --doctor
   ```

   Expected: you can identify your source revision, existing changes, selected JDK,
   Gradle wrapper and ADK installation. JDK 25 is the implementation/test toolchain;
   do not silently drop the repository's JDK 22+ compatibility obligations.
4. Existing modifications, including codegen functional tests and dependency locks,
   belong to their existing author. Read them before extending the same files.
   Never reset the checkout, clean somebody else's output tree, replace dependency
   locks, or create a worktree merely because an older recipe uses one.
5. Follow the operator-approved start/review route required by current repository
   instructions. If a historical instruction names an unavailable tool or deleted
   orchestrator, ask the operator for the current route; do not restore it, invent
   a replacement control plane, or push directly to the default branch.
6. Check impact before changing core/public symbols. For example:

   ```bash
   ./scripts/adkw blast-radius PortalRuntime
   ./scripts/adkw blast-radius ProcessBroker
   ./scripts/adkw blast-radius PortalCodec
   ```

   Run only the relevant queries for your issue. Read callers, tests, API dumps and
   generated-consumer use, not just the declaration you intend to edit. These are
   read/analysis commands, not permission to change every reported caller.

### What to do when preparation fails

| Observation | Correct next action | Incorrect shortcut |
|---|---|---|
| Wrong JDK or missing ADK installation | Record the diagnostic and use approved setup instructions/operator help | Change version checks or download an unreviewed tool |
| Another worker owns the checkout/files | Coordinate assignment before editing those files | Concurrently overwrite the same fixture |
| Missing kernel feature or cgroup delegation | Run applicable host checks and leave native qualification open | Catch the startup error and run unprotected |
| Dependency verification rejects an artifact | Investigate coordinates/checksums and follow the approved dependency process | Disable verification or add metadata-skip flags |
| A public change needs more authority than this plan grants | Explain the exact signature/compatibility decision to the reviewer | Break existing callers and update snapshots to hide it |

## Vocabulary and ownership

| Term | Meaning in this project | Does not mean |
|---|---|---|
| Host / broker | Application JVM and the runtime owning child processes | A sandboxed place to run arbitrary DTO getters |
| Worker | Restricted child JVM containing guest implementation/state | A thread sharing the host heap |
| Service | One declared interface plus generated metadata | An independent process for every interface |
| Isolation group | Explicit shared trust, launch shape and failure boundary | A grouping the library may merge to save memory |
| Binding | Runtime-owned view used to submit a declared operation | A new owned pool/session |
| Checkout | Temporary exclusive ownership of an exact worker slot | Ownership that can end before a corrupt reply is identified |
| Admission | Permission to own a bounded outstanding invocation | A guarantee that work has started |
| Snapshot | Encoded copied-value request acquired during generated submit | A clone of arbitrary Java objects or file contents |
| Result | Delivered value or failure from the call | Proof the worker/cleanup has physically ended |
| Execution end | Supported execution and owned cleanup ended, or work never started | Successful business completion |
| Replacement | Restoring stateless capacity after failure | Replaying the failed application call |
| Readiness | Validated startup/containment/generation handshake | JIT steady state or a warmed application cache |
| Configured memory ceiling | Sum of explicit worker cgroup limits | Allocated memory, RSS, or reserved deployment capacity |

Read the existing ownership chain in this order:

```text
PortalRuntime / PortalBrokerOwnership
  -> ProcessBroker: exact workers, launch, checkout, replacement and close
     -> PortalOperationOwner: admitted snapshots, queue and invocation ownership
        -> InvocationHandle: result observation and separate execution-end signal

Worker startup: parent authorization -> Landlock -> Seccomp -> construction -> ready
```

The arrow from the broker to operation ownership is an ownership overview, not a
new class hierarchy. Extend those components; do not create a second scheduler,
pool, mailbox, shutdown registry or process monitor just to implement convenience.

### Invocation evidence is not a transaction

- **NOT_STARTED:** Guest work did not start. Any acquired host resources still have
  to retire before execution end can be published.
- **COMPLETED:** A validated reply reports completion, including a business exception.
  The method may already have changed worker state or performed an authorized effect.
- **UNKNOWN_COMPLETION:** The host cannot establish whether all/part of the effect
  happened. Do not retry automatically. Application reconciliation/idempotency is
  application work, not a new Portal retry framework.

For a stateful singleton, unknown completion or active cancellation loses state
terminally. A queued call cancelled before entry is a different case. Always test
both; treating all cancellations alike breaks either safety or usability.

## Work order and the common test cycle

For a serial first implementation, use CG01, CG07, CG02, CG03, CG04, CG06, CG05;
then P01/P12, P02, P03, P04/P05, P06/P07/P08/P09, P10/P13 and final qualification.
P11 follows the final measurement milestone and may finish with a no-go report.
This is a suggested order, not a new dependency graph or parallel-worker approval.
The existing packaging/adoption issues retain their separate ownership.

P01 and P12 can start against existing APIs. If a P01 byte-array fixture encounters
CG01's known compilation defect, land that corrective prerequisite rather than
patching generated source or silently swapping in an ungenerated transport path.
P12 must use explicit configuration: P02 depends on P12, not the other way around.

For **each individual behavior** in a card:

- [ ] Identify the owner and write one externally observable expectation: result,
  entry count, wire bytes, worker identity, close outcome or generated compilation.
- [ ] Add the smallest regression. Run it before changing production logic and
  retain its failure. A new API may initially be absent; after adding its shape,
  prove the substantive behavior rather than accepting compilation alone as GREEN.
- [ ] Check that RED has the intended reason. A missing worker JAR, classpath error,
  unrelated fixture syntax error or “no tests found” is not a reproduced bug.
- [ ] Make the minimum production change in the existing owner. Generated output
  is an observation; fix the generator/model, not files beneath `build/`.
- [ ] Run the focused test again. Then run the connected regressions from the card.
- [ ] Read the connected diff once against the issue's acceptance criteria. Update
  guide/design text and record exact evidence before the review checkpoint.
- [ ] At a completed implementation checkpoint run the required full quality gate
  and distinct consumer/native checks. Do not rerun an equivalent already-valid
  gate for the same revision without a changed input or concrete concern.

Steps above are repeated for one case at a time. Do not write twenty failing
concurrency tests, change several owners, and attempt to debug all of them together.

### Deterministic tests, finite waits

Use `CountDownLatch`, controlled futures and existing fake clocks to establish
ordering. `Thread.sleep(100)` does not establish that a request entered a worker.
All waits have finite bounds and release barriers from `finally` blocks.

```kotlin
val entered = CountDownLatch(1)
val release = CountDownLatch(1)
val executor = Executors.newSingleThreadExecutor()
try {
    val task = executor.submit<String> {
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS))
        "finished"
    }
    check(entered.await(5, TimeUnit.SECONDS))
    check(!task.isDone)
    release.countDown()
    check(task.get(5, TimeUnit.SECONDS) == "finished")
} finally {
    release.countDown()
    executor.shutdownNow()
    check(executor.awaitTermination(5, TimeUnit.SECONDS))
}
```

This is a test synchronization example using `java.util.concurrent`, not an executor
to add to Portal. A native test additionally owns/reaps every child it starts.
Use the project's failure-preserving cleanup pattern when both an assertion and
cleanup fail; do not let a `finally` exception erase the original failure.

### Choose the right level of proof

| Check | Proves | Cannot prove |
|---|---|---|
| Pure machine/unit test | Arithmetic, transitions and injected failures | An actual kernel limit or child death |
| Compiled generated fixture | Emitted declarations/codecs are executable | A real restricted child performed the call |
| TestKit fixture | Real plugin wiring, incremental/relocated builds | Published dependency closure unless explicitly staged |
| Independent published consumer | Real artifact/API/Java-Kotlin interoperability | Containment unless a real worker is launched |
| Fresh kernel integration | Specific startup/denial/lifetime behavior on that environment | Other kernels/JDKs or a universal security claim |
| Controlled benchmark | Measured costs for its frozen workload/resources | General “faster than HTTP” or thread-sized memory |

Read [testing strategy](../../internals/designs/core/testing-strategy.md) before
native execution. Use the existing fresh-child harness for containment experiments;
never install a permanent policy in the reusable Gradle/controller JVM or from a
virtual thread. Closing a receipt does not undo installed Seccomp/Landlock. Use
`./scripts/run_containerized_tests.sh kernelCheck` only in the configured container
environment when that is the selected native gate, not as an extra duplicate build.
Run ordinary iteration with the wrapper, for example:

```bash
./gradlew :portals:runtime:compileKotlin
./gradlew :portals:runtime:test --tests 'io.mazewall.portal.PortalRuntimeTest'
./gradlew :portals:codegen:test --tests 'io.mazewall.portal.codegen.PortalStubGeneratorTest'
```

A focused test invocation must select at least one test and report zero unexpected
failures/skips. Empty LSP diagnostics before indexing, cached host results and a
successful `--dry-run` are not substitutes for compilation or fresh native evidence.
Use only changed modules while iterating; use `./gradlew build` at completion.

## CG01 — Compilable boundary types

**Read first:** `PortalStubGenerator.kt` type mapping and the existing generated-source
round-trip case in `PortalCodegenFunctionalTest.kt`. Exact paths are in
[CG01](../../internals/backlog/resolved/issue-20261103-095305-625-make-accepted-portal-byte-array-and-list-contracts.md).

- [ ] Add the following Java interface to a compiler-backed fixture and generate
  **both** host and worker sources without hand edits:

  ```java
  package fixture;

  public interface ArrayAndListService {
      byte[] echo(byte[] value);
      java.util.List<Integer> many();
  }
  ```

- [ ] Observe current failures: byte-array declarations disagree with `ByteArray`
  codecs, and a list override disagrees with the decoder's returned collection type.
  Preserve compiler logs showing the actual emitted declarations and diagnostics.
- [ ] Correct declaration mapping once in the generator. Do not change wire encoding
  to accommodate an incorrect declaration, cast an incompatible result, or disable
  compiler checking. Recheck exact Java and Kotlin override contracts.
- [ ] Add empty/nonempty array/list round trips and Kotlin `ByteArray`, `List` and
  `MutableList` fixtures. Exercise generic inheritance and supported nested members.
- [ ] Compare existing scalar/DTO/map/overload results and golden wire bytes.

```bash
./gradlew :portals:codegen:test --tests '*PortalStubGeneratorTest' --tests '*PortalCodegenFunctionalTest'
```

**Done when:** Unmodified generated classes compile and execute, not merely when a
source string contains `ByteArray`. Wire bytes and existing IDs remain unchanged.

## CG02 — Complete validated DTO shapes

**Read first:** `BoundaryTypes.kt`, `PortalServiceSchema.kt`,
`ResolvedPortalContract.kt` and [CG02](../../internals/backlog/issue-20261103-095306-294-validate-complete-portal-dto-shapes-before-source-.md).

- [ ] Add three separate fixtures: an abstract DTO, a Kotlin nullable DTO member,
  and `record Names(java.util.List<String> values)`. Record whether each currently
  reaches emission, fails compilation, or loses its generic member information.
- [ ] Resolve generic types and member nullability before emission. Walk records,
  constructor parameters and readable fields/accessors using their actual generic
  metadata, not just erased `Class` values.
- [ ] Validate both directions: host/worker must be able to read each component and
  construct the resulting value legally. Cover inaccessible enclosing classes,
  abstract classes, recursive shapes and unresolved type variables.
- [ ] Keep copied values, direct inbound capabilities/snapshots and result-only
  Unit distinct in the validated model. Invalid combinations must not be a pair
  of contradictory flags that downstream code is expected to remember to reject.
- [ ] For unsupported nullable/nested shapes, reject with a path such as
  `Service.parse parameter request field customer.name`; do not invent a new wire
  representation. Never execute a getter or constructor while validating its shape.
- [ ] Compile/round-trip accepted fixtures; negative fixtures must fail resolution
  before output publication and leave the previous complete generation intact.

```bash
./gradlew :portals:codegen:test --tests '*ResolvedPortalContractTest' --tests '*PortalStubGeneratorTest' --tests '*PortalCodegenFunctionalTest'
```

**Done when:** Every fixture is explicitly supported and executable or rejected at
resolution with the correct field path. No accepted shape becomes an erased guess.

## CG03 — Full schema identity

**Read first:** `PortalManifestGenerator.kt` and
[CG03](../../internals/backlog/issue-20261103-095306-992-fingerprint-the-complete-versioned-portal-wire-sch.md).

- [ ] Compile two API JARs with **identical** service/DTO binary names, one with an
  `int` field and one with a `long` field. Load each in its own noninitializing
  classloader. Assert different schema identities; the current shallow hash fails.
- [ ] Write down the canonical schema record before hashing: version, method order,
  actual scalar encodings, component order, nested generic members, supported
  nullability, capability positions and snapshot bounds. Document enum and declared
  exception inclusion rules. Hash that record, not `toString()` on reflection objects.
- [ ] Derive source, metadata and diagnostics from CG02's same resolved model.
  Preserve public generator entrypoints with narrow adapters rather than maintaining
  two independent resolution paths.
- [ ] Prove equal schemas loaded independently have equal identities. Then change
  one property at a time and prove a change when the documented compatibility rule
  requires it. Changing the service name at the same time invalidates that control.
- [ ] Exercise matching and mixed host/worker generations. A mismatch must reject
  before dispatch without weakening artifact digests or required startup protection.

```bash
./gradlew :portals:codegen:test --tests '*PortalManifestGeneratorTest' --tests '*ResolvedPortalContractTest'
./gradlew :portals:runtime:test --tests '*PortalServiceManifestTest'
./gradlew :portals:worker:test --tests '*PortalGeneratedDescriptorTest'
./gradlew :portals:runtime:integrationTest --tests '*RequiredWorkerGenerationTest'
```

**Done when:** Identity tracks the actual schema deterministically. Corrected schema
IDs may change; method IDs and valid wire bytes do not. A new public manifest format
is not authorized without the separate compatibility decision specified by CG03.

## CG04 — Strict decoding

**Read first:** `PortalCodec.Reader`, generated host/dispatcher decoding and
[CG04](../../internals/backlog/resolved/issue-20261103-095307-699-reject-noncanonical-and-incompletely-consumed-gene.md).

Use one known-good payload for each shape, then change only the indicated property:

| Case | Mutation | Required observation |
|---|---|---|
| Integer | Append one byte; separately truncate one byte | Reject before service entry/result delivery |
| Zero arguments / Unit result | Supply one nonempty byte | Reject, not “ignore unused bytes” |
| Boolean | Replace a valid byte with `2` | Reject; only `0`/`1` are canonical |
| String | Supply UTF-8 bytes `C3 28` with a valid length prefix | Reject, not replacement characters |
| Map | Encode two entries decoding to the same key | Reject, not last/first-write wins |
| Capability | Too few or extra direct inbound owners | Reject and retire only actually acquired owners |

- [ ] Run malformed requests against a compiled dispatcher with a service-entry
  counter; require zero entries for every rejected request. Also exercise malformed
  replies on the host path before any value is delivered.
- [ ] Apply checks recursively and require full payload/capability consumption.
  Retain existing count/length/overflow checks; validating a frame header alone is
  not validating the generated value inside it.
- [ ] Use a distinct malformed-wire classification. Test an ordinary remote business
  exception and a trusted host DTO constructor throwing the same Java exception
  class as the parser; neither may become wire corruption just by exception type.

```bash
./gradlew :portals:runtime:test --tests '*PortalCodecTest'
./gradlew :portals:codegen:test --tests '*PortalCodegenFunctionalTest'
./gradlew :portals:worker:test --tests '*PortalWorkerDriverTest'
```

**Done when:** Malformed input cannot cross the relevant validation boundary. CG05,
not this parser-only card, proves the offending worker cannot be reused.

## CG05 — Validation-owned checkout

**Read first:** `ProcessBroker.kt`, `PortalBrokerCallMachine.kt`,
`PortalStatefulInvocationGate.kt` and [CG05](../../internals/backlog/resolved/issue-20261103-095308-453-keep-portal-worker-checkout-owned-through-generate.md).

- [x] Begin with a **real child** returning a matching success frame/header but a
  generated body that CG04 rejects. A wrong sequence number or stale generation is
  insufficient: those already exercise a different broker check.
- [x] Record the exact worker identity. Pause generated reply validation, submit a
  competing call, and prove the same checkout cannot become available while
  validation is incomplete. Use a test barrier, not a guessed sleep.
- [x] Move the release/failed-checkout decision into the existing broker call
  lifecycle around generated validation. Keep only the narrow bridge needed by
  generated classes in external artifacts. Do not change raw public invoke behavior.
- [x] On malformed reply, retire that exact worker. Singleton later calls reject
  without entry; stateless capacity may replace according to existing policy, but
  the failed invocation is never replayed.
- [x] Add controls for valid replies, completed business exceptions and trusted host
  constructor failures, plus cancellation/close while validation is held. Assert
  original failure identity and separate physical cleanup accounting.

```bash
./gradlew :portals:runtime:test --tests '*PortalBrokerCallMachineTest' --tests '*PortalBrokerOwnershipTest' --tests '*PortalRuntimeTest'
./gradlew :portals:runtime:integrationTest --tests '*ProcessBrokerIntegrationTest' --tests '*PreparedPortalOperationIntegrationTest'
```

**Done when:** Real-child identity, validation order, non-reuse and retirement are
observed. The earlier source review alone does not close this issue.

## CG06 — Bounded encoding before allocation

**Read first:** Current encode concatenation in `PortalStubGenerator.kt`,
`PortalCodec.kt` and [CG06](../../internals/backlog/resolved/issue-20261103-095309-136-bound-generated-portal-encoding-before-allocation-.md).

- [x] Reproduce a 16,385-element collection and an over-limit reply. Then use a small
  configured byte limit to make routine tests fast; do not attempt to cause an OOM.
- [x] Add counting getters/iterators and allocation/growth observations at existing
  test seams. The rejection must occur before over-budget growth or unnecessary
  element traversal, not after an oversized array has already been created.
- [x] Implement one shared bounded writer (`PortalRequestWriter.kt` is the planned
  file). Before writing/growing, use checked length/count arithmetic. Bound the
  aggregate nested value, not just every individual field. Account for UTF-8 bytes,
  prefixes, scratch storage and ownership-transfer copies.
- [x] Use it in existing synchronous request **and worker-reply** encoding. P03 later
  owns submission/admission integration; it must reuse this writer, not add another.
- [x] Compare valid old/new wire bytes at empty, exact-limit and nested boundaries.
  Test limit-plus-one, numeric overflow and a user getter that throws. A user getter
  can itself block/allocate; library bounds do not make arbitrary host code safe.

```bash
./gradlew :portals:runtime:test --tests '*PortalCodecTest' --tests '*PortalConfiguredTransportBoundsTest'
./gradlew :portals:codegen:test --tests '*PortalCodegenFunctionalTest'
```

**Done when:** Bounds control library work before allocation/traversal and valid wire
bytes stay compatible. A late frame-size assertion is not a memory bound.

## CG07 — Disjoint output publication

**Read first:** `GeneratedOutputPublisher` in `PortalCodegenPlugin.kt` and
[CG07](../../internals/backlog/resolved/issue-20261103-095309-827-reject-overlapping-portal-generated-output-destina.md).

- [ ] Create old-generation sentinel files in an owned temporary fixture. Publish
  `output/child` followed by `output`; reverse that order in a second test. The
  current child-first defect can report success while losing the new child tree.
- [ ] Also cover equal destinations, `..` aliases, staging/destination overlap and
  symlinked existing ancestors. A string-prefix comparison is not path ownership.
- [ ] Validate every output/staging ownership relationship **before** creating,
  moving or deleting destinations. Reject conflicts with both paths in the error.
- [ ] Inject one failure at a time at backup, publish, rollback and cleanup. Verify
  the old generation when restoration succeeds and inspect primary/suppressed
  failures when restoration does not. Never turn a partial generation into success.
- [ ] Re-run valid sibling, stale-output removal and relocated build/cache cases.
  Keep the existing publisher; do not introduce a general transaction framework.

```bash
./gradlew :portals:codegen:test --tests '*PortalCodegenFunctionalTest'
```

**Done when:** Rejected plans mutate no previous output and valid publication retains
the original/fatal error across cleanup. Do not test against real application trees.

## P01 — Fair performance and whole-deployment memory measurements

**Read first:** The plan's P01 file inventory, the historical
[benchmark README](../../../benchmarks/integration-boundaries/README.md), and
[P01 issue](../../internals/backlog/issue-20261103-085927-123-compare-warm-portal-calls-with-executor-and-persis.md).
Create `benchmarks/portal-comparison`; do not rewrite the historical harness/data.

### P01.1 — Build a correct comparison before collecting timings

- [ ] Add a small fixed ASCII transform with an independently computed expected
  output, plus a useful bounded parse/summarize workload. The host oracle may know
  the algorithm; it must not load the worker implementation.
- [ ] Implement direct, prestarted executor, generated packaged Portal and persistent
  binary HTTP-to-child-JVM modes. Observe actual child ownership/health identities;
  use a separate untimed fixture identity probe if needed. Compare them with the
  host PID without adding mode-specific PID fields to the measured useful output.
- [ ] Give HTTP the same useful algorithm, request/result encoding and comparable
  worker/core budget. Reuse its connection and use a finite request executor.
  Direct/executor modes are in-process reference costs, not equal-isolation peers.
- [ ] Corrupt a single output and require a failed run with no accepted success row.
  Reject missing samples, duplicate invocation IDs and inconsistent accounting.
- [ ] Record the actual producer/consumer/compiler/JDK versions, selected artifacts
  and strict dependency locks. A new coordinate gets its own reviewed locks; do not
  overwrite P617 locks or silently resolve current source with project substitution.

### P01.2 — Define timer boundaries explicitly

| Measurement | Start | Stop | Included production cost |
|---|---|---|---|
| Cold startup | Before artifact materialization/start request | Required readiness observed | Hashing/materialization, process launch, containment, constructors and handshake |
| First call | Immediately before public call/submit | Public result settles | Admission, submit snapshot, encoding, queue, RPC, decode/validation |
| Warm call | Immediately before public call/submit | Public result settles | Same production path, with already-ready workers |
| Offered-load latency | Scheduled arrival time | Public result settles | Generator scheduling delay plus the complete call path |
| Physical cleanup | Cancellation/close request | Observed end/owned-child retirement | Cleanup and real wait/reap, reported separately from result |

For asynchronous calls capture the start **before** `submit`; starting the clock
after it returns hides the proposed snapshot cost. Capture completion timestamps
in a lightweight completion observer, not when a serial collector eventually
visits each future. Retain synchronous/direct mode timing separately.
Use durations from one monotonic clock within the measuring JVM; do not subtract
guest `System.nanoTime()` values from host values or combine absolute timestamps
across fresh forks. Worker-local timings, if separately collected, remain labelled
as local durations rather than an inferred cross-process clock.

Only benchmark-only oracle comparisons, environment fingerprints and observer
logging move outside these intervals. Required production checks stay enabled.
Hashing worker artifacts belongs to cold startup even if the benchmark also hashes
its evidence outside the timing interval. Do not relabel cached materialization
as a fresh cold-cache run; distinguish fresh JVM, first-use bundle and warm cache.

### P01.3 — Accounting and bounded sampling

- [ ] Keep the plan's finite payload/concurrency/worker matrix. Start with one small
  case, prove accounting, then add cases; do not run an unbounded Cartesian sweep.
- [ ] Declare warm-up duration/sample floor before sampling. Use at least three
  fresh-JVM forks and 10,000 warm observations for each reported p99. A smaller
  diagnostic sample may run, but it must not be labelled qualified p99 evidence.
- [ ] For fixed-rate tests record scheduled arrival independently of completion.
  Waiting for one result before scheduling the next measures closed-loop load and
  hides overload. A late generator records its lateness rather than resetting time.
- [ ] Retain per-invocation case/fork/sequence, scheduled/start/settled monotonic
  times, outcome, encoded byte counts and worker identity where observed. Keep
  rejected/timeouts in offered-load accounting. Label success-only quantiles and
  publish failure counts/latencies alongside them, not instead of them.
- [ ] Check conservation at shutdown: offered equals rejected plus admitted;
  admitted equals completed plus failed plus still-pending. A passing finished run
  has zero still-pending and all acquired physical owners retired.
  Define mutually exclusive counters using the actual admission boundary. An admitted
  encode/queue failure is not also an admission rejection. Here `completed` counts
  successful results, not the `COMPLETED` evidence enum; an admitted business exception
  is a failed result with completed evidence, never two terminal counter increments.
- [ ] Freeze raw samples before calculating summaries. Save kernel/CPU/JVM flags,
  pool/admission/heap/cgroup limits, workload bytes and source/JAR/lock identities.
  No numeric latency or footprint in this guide is a claimed measurement.

### P01.4 — Memory is a separate pass

1. Measure the host without workers, then cold startup, ready-idle workers, busy
   useful work, bounded queue saturation, consumed versus retained results and close.
2. Sweep one/two/four total workers and one/multiple groups. Keep workload data,
   caches, input/result sizes and sample interval explicit. Compare equal workloads
   and worker budgets with the HTTP child as a whole deployment.
3. Sample authorized `/proc/<pid>/smaps_rollup` RSS/PSS/private memory and appropriate
   cgroup current/peak/events. Preserve unavailable/denied fields with a reason.
   An absent cgroup limit is unbounded; an absent sample is unknown, never zero.
4. Keep per-process RSS. Sum PSS only over the declared sample scope; it apportions
   shared pages, while RSS sums double-count them. A parent cgroup already contains
   descendant charges: never add it to its children to get deployment consumption.
5. Convert kernel-reported units explicitly. Procfs fields labelled `kB` use 1024-byte
   units; cgroup memory files report bytes. Report sample times and disclose missed
   transients. Parent charges can include child allocations made before attachment.
6. Use finite retained-result and cache fixtures. Do not allocate until the machine
   fails. NMT, if available/authorized, belongs in a separately labelled diagnostic
   fork; attach availability and JVM coverage are not assumed under containment.

A host-only arithmetic control can use Kotlin `check` without a new dependency:

```kotlin
val rssKiB = listOf(100L, 100L)
val pssKiB = listOf(60L, 60L)
check(rssKiB.sum() == 200L) // Reported RSS sum, not unique resident bytes.
check(pssKiB.sum() == 120L)
check(Math.multiplyExact(pssKiB.sum(), 1024L) == 122_880L)
val absentPss: Long? = null
check(absentPss == null) // The report retains unavailable, not a zero sample.
```

Add a production-aggregator test with those fixtures too; a duplicate calculation
in the self-check cannot prove the actual report writer handles missing fields.

### P01.5 — Commands and deliverables

`portalComparisonSelfCheck` and `portalComparison` are **planned tasks**. Implement
their finite defaults/input validation and document exact argv in the new README
before expecting these commands to work. Use the existing staging workflow to
provide `PORTAL_COMPARISON_REPOSITORY` and `PORTAL_COMPARISON_VERSION`:

```bash
: "${PORTAL_COMPARISON_REPOSITORY:?Set the absolute staged Maven repository}"
: "${PORTAL_COMPARISON_VERSION:?Set its exact pinned publication version}"
./gradlew -p benchmarks/portal-comparison \
  -PconsumerRepository="$PORTAL_COMPARISON_REPOSITORY" \
  -PconsumerVersion="$PORTAL_COMPARISON_VERSION" portalComparisonSelfCheck
./gradlew -p benchmarks/portal-comparison \
  -PconsumerRepository="$PORTAL_COMPARISON_REPOSITORY" \
  -PconsumerVersion="$PORTAL_COMPARISON_VERSION" portalComparison
```

Use the authorized outer execution/resource controller; it must bound build/run
time, memory, logs and descendant rescue. It does not make a rescued run successful.
Store raw samples, identities, summary rules, corruption self-check outcome and
physical-end outcomes. Lower measured warm latency for specified workloads is a
qualified result; a noisy or slower run is not permission to remove validation.

## P12 — Pure aggregate capacity and pre-launch rejection

**Read first:** `PortalRuntimeConfig.kt`, `PortalServiceRegistry.kt`,
`PortalWorkerProfile.kt`, singleton sizing in `PortalRuntime.brokerFor`, and
[P12 issue](../../internals/backlog/issue-20261103-101536-305-expose-and-validate-aggregate-portal-capacity-befo.md).
Do this without P02's future `loadBaseline()` or convention DSL.

### P12.1 — Separate three questions

1. **Configuration:** How many processes can this runtime's configured groups use,
   and are all their cgroup memory ceilings specified? `capacityPlan()` answers this
   without launching, materializing bundles or sampling the OS.
2. **Admission to open:** Does that report fit the caller's optional process/
   configured-memory budget? `open(config, budget)` rejects before broker creation.
3. **Real startup:** Can the actual machine provide the required protection and
   delegated controllers? Existing startup/readiness answers this later. A capacity
   report cannot substitute for it.

### P12.2 — Concrete host-only regression fixture

Add the following test structure to the planned `PortalCapacityPlanTest.kt`, in
package `io.mazewall.portal`. It uses current manifest/config/profile constructors;
only capacity types/functions are proposed. JUnit's `@Test`, `@TempDir`, Kotlin test
assertions, `java.nio.file.Path` and `Files` are the imports needed beyond that package.

```kotlin
private const val MIB = 1024L * 1024L

private fun capacityManifest(
    id: String,
    group: String,
    mode: PortalInstanceMode,
    profile: String,
): PortalServiceManifest = PortalServiceManifest(
    serviceId = PortalServiceId(id),
    contractName = "fixture.CapacityContract",
    implementationName = "fixture.CapacityImplementation",
    dispatcherName = "fixture.CapacityDispatcher",
    schemaId = "capacity-fixture-schema",
    isolationGroup = group,
    instanceMode = mode,
    policyProfile = profile,
    workerArtifact = "capacity-worker",
)

private fun capacityConfiguration(root: Path, cappedB: Boolean = true): PortalRuntimeConfig {
    val capped = PortalWorkerProfile(64L * MIB, 128L * MIB)
    return PortalRuntimeConfig(
        manifests = listOf(
            capacityManifest("a-one", "A", PortalInstanceMode.STATELESS, "profile-a"),
            capacityManifest("a-two", "A", PortalInstanceMode.STATELESS, "profile-a"),
            capacityManifest("b-one", "B", PortalInstanceMode.STATEFUL_SINGLETON, "profile-b"),
        ),
        workerBundleCache = root.resolve("cache"),
        workerProfiles = mapOf(
            "profile-a" to capped,
            "profile-b" to if (cappedB) capped else PortalWorkerProfile(),
        ),
        cgroupRoot = root.resolve("delegated"),
        limits = PortalRuntimeLimits(workers = 2),
    )
}

class PortalCapacityPlanTest {
    @TempDir
    var directory: Path? = null

    @Test
    fun `capacity counts groups not service registrations`() {
        val root = requireNotNull(directory)
        val config = capacityConfiguration(root)
        val report = config.capacityPlan()

        assertEquals(3L, report.totalWorkerProcesses)
        val memory = assertIs<PortalWorkerMemoryTotal.Limited>(report.configuredWorkerMemory)
        assertEquals(384L * MIB, memory.bytes)
        assertFalse(Files.exists(root.resolve("cache")))
        assertFalse(Files.exists(root.resolve("delegated")))
    }

    @Test
    fun `process budget rejects before artifact acquisition`() {
        val root = requireNotNull(directory)
        val failure = assertFailsWith<IllegalArgumentException> {
            PortalRuntime.open(capacityConfiguration(root), PortalCapacityBudget(2))
        }
        assertTrue(failure.message.orEmpty().contains("process", ignoreCase = true))
        assertFalse(Files.exists(root.resolve("cache")))
    }

    @Test
    fun `missing memory ceiling is explicit rather than zero`() {
        val report = capacityConfiguration(requireNotNull(directory), cappedB = false).capacityPlan()
        assertEquals(3L, report.totalWorkerProcesses)
        val memory = assertIs<PortalWorkerMemoryTotal.Unbounded>(report.configuredWorkerMemory)
        assertEquals(setOf("B"), memory.groups.toSet())
    }
}
```

The worker artifact is deliberately absent and the cgroup-root path is deliberately
not created. Reporting must still work: it needs only validated metadata. A non-null
root avoids the unrelated missing-root configuration error. These tests do **not**
prove no native calls by themselves, real cgroup delegation, readiness or budgeted
open's complete absence of launch side effects. Add the existing owner/launcher
observations for that last assertion; do not add a public test-only launch option.

### P12.3 — Implement one validated calculation

- [ ] Validate profiles/required launch contracts and the existing registry's
  duplicate-ID/compatible-group rules using existing code. Do not repeat a weaker
  local version of those rules in the planner.
- [ ] Take one representative **validated group**, not one service. Count one
  singleton process or `limits.workers` stateless processes, including lazy groups.
- [ ] Use `Long` throughout the accumulation. Multiply a group's count by its
  explicit per-worker `memoryMaxBytes` with checked arithmetic, then checked-add
  bounded totals. Reject overflow with group/quantity context, not a wrapped value.
- [ ] Record every uncapped group's identity as `Unbounded(groups)`. An `-Xmx` flag
  is not a cgroup limit. Validate all groups; do not return early in a way that
  hides a later conflicting/missing profile.
- [ ] Copy returned collection ownership. Kotlin `List`/`Set` types alone do not
  prevent mutation through Java or the original backing collections. Keep report
  constructors narrow; Java still needs public read access to the report.
- [ ] For empty configuration return zero processes and `Limited(0)` memory. Budget
  constructors still require positive supplied bounds; do not make zero a special
  “unlimited” value.

Expected arithmetic for the fixture:

```text
A: two services, STATELESS, workers=2  -> 2 processes * 128 MiB = 256 MiB
B: one service, SINGLETON, workers=2  -> 1 process  * 128 MiB = 128 MiB
Total                               -> 3 processes,          384 MiB
```

### P12.4 — Budget decision table

| Configuration / budget | Required outcome |
|---|---|
| Fixture / process limit 3, memory limit 384 MiB | Accept configuration; no eager launch |
| Fixture / process limit 2 | Reject before broker creation |
| Fixture / process limit 3, memory limit 384 MiB minus 1 byte | Reject configured-memory excess |
| B uncapped / process-only limit 3 | Accept configuration and report Unbounded |
| B uncapped / any finite configured-memory budget | Reject and identify B |
| Empty manifests / positive process budget | Zero-capacity report and successful lazy open/close |
| Missing profile or conflicting A members | Reject configuration; no partial report or launch |
| Zero/negative process bound, or supplied zero/negative byte bound | Reject invalid budget |
| Two workers with a valid huge per-worker ceiling whose product overflows Long | Reject overflow rather than a small/negative total |

For overflow use metadata-only fixtures, for example a normal small heap paired
with a very large positive memory ceiling; never allocate that amount or create
billions of services. Cover addition separately with multiple finite group totals.
Use existing narrow arithmetic helpers when appropriate, not a new public API just
to force an otherwise unrepresentable process-count input.

- [ ] Add the static `open(config, budget)` overload without changing existing
  constructor/default bridges or unbudgeted `open(config)` behavior. After
  validation both enter the same runtime/ownership path.
- [ ] Test exact-boundary acceptance and every rejection row. Capture zero preparation,
  launch and guest-construction events on budget rejection. An artifact/classpath
  exception is not an acceptable substitute for the intended budget diagnostic.
- [ ] Compile a Java caller using the complete new budget constructor arguments
  (`new PortalCapacityBudget(3, null)` for process-only) and the static open method.
  Kotlin defaults do not automatically create a one-argument Java constructor.
- [ ] Keep independent consumers on current explicit registration/profile wiring.
  P02 later adds the convenience DSL/`loadBaseline()` examples. Do not introduce
  P02 as a hidden test dependency while leaving the issue graph acyclic on paper.

```bash
./gradlew :portals:runtime:test --tests '*PortalCapacityPlanTest' --tests '*PortalRuntimeTest' --tests '*PortalWorkerProfileTest'
```

Run the separate published-consumer workflow below. P13 owns actual child counts
and required cgroup behavior. The report is per runtime, not a global reservation;
other runtimes, host objects, swap and parent bootstrap charges remain outside it.

## P02 — Build conventions and onboarding

**Start from:** Existing `PortalCodegenFunctionalTest.writeFixture` and the
[published Portal consumer](../../../tests/consumers/portal-plugin/README.md).

- [ ] Create a separate Java-authored and Kotlin-authored fixture using
  `src/portalApi`, `src/portalWorker`, `src/main` and the planned services container.
  First prove plain `build` includes the worker by putting a deliberate guest
  compile error in one fixture. It must fail for that error, not pass host-only.
- [ ] Wire API JAR -> generator -> host/worker compilation lazily using task/provider
  outputs. Preserve explicit registration/source-set paths. No eager dependency
  resolution or guest implementation on the host classpath is permitted.
- [ ] Compare clean/repeated/relocated output, config-cache reuse and changed/removed
  registrations. Assert all host/worker manifests and bundle resources agree.
- [ ] Add only the explicit reserved baseline profile through `PortalWorkerProfile()`.
  Unknown required profiles still fail; custom heap/cgroup values remain paired.
- [ ] Compile full Java/Kotlin usage examples and P12 capacity calls. Static build
  diagnostics show known topology and label runtime-selected limits unresolved.

```bash
./gradlew :portals:codegen:test --tests '*PortalConventionsFunctionalTest' --tests '*PortalCodegenFunctionalTest'
```

**Do not:** Implement runtime config evaluation in Gradle, copy host dependencies
into a guest bundle, infer artifact roles from new class-name suffixes, or claim
plain Java support from a Kotlin-only compiler test.

## P03 — Generated submissions and byte ownership

**Start from:** `PortalOperationOwner`, `PortalCallAdmission`,
`PortalInvocationLifecycle`, `PortalInvocationRetirement`, the existing
`PreparedPortalOperationIntegrationTest`, and CG05/CG06's corrected boundaries.

- [ ] Add one generated copied-byte operation. Hold an existing invocation, submit
  the next input, mutate the caller array after submit returns and require the
  original bytes at the worker. Repeat for supported nested DTO/collection values.
- [ ] Test count rejection and byte rejection independently. A rejected submission
  must not invoke its encoder/getters or enter the guest. Reserve before allocating;
  include peak scratch/copy bytes, then shrink to retained bytes after encoding.
- [ ] Record the absolute deadline before admission/encoding. Hold a host getter,
  overlap runtime close, and require finite incomplete close without early end or
  budget release. Release the getter; the expired/closed request must never dispatch.
- [ ] Generate zero/one/multiple argument forms from the validated schema. Test
  overloaded IDs/names, foreign/ambiguous descriptors, Java boxed selectors and
  matching synchronous golden bytes. Reuse CG05 validation-owned checkout.
- [ ] Exercise encode exception, queued/active cancellation, fatal failure and close
  retry. Assert one release per acquired reservation and no buffer reference after
  ownership ends. Do not equate cancelled result futures with released admission.
- [ ] Keep legacy `prepare` input-stability rules. Explain unsupported generated
  FD/InputStream bindings without breaking their existing synchronous service.

```bash
./gradlew :portals:runtime:test --tests '*PortalSubmissionSnapshotTest' --tests '*PortalSubmissionBudgetTest' --tests '*PreparedPortalOperationTest' --tests '*PreparedPortalOperationVisibilityTest' --tests '*PortalGeneratedNamesTest'
./gradlew :portals:codegen:test --tests '*PortalOperationGeneratorTest'
./gradlew :portals:runtime:integrationTest --tests '*PreparedPortalOperationIntegrationTest'
```

Request reservations do not bound caller graphs, completed futures retaining
results, or arbitrary host getter code.

## P04 — Coroutine observation and cancellation

- [ ] Build a test-only `InvocationHandle` with separate controlled result/end
  futures and a cancellation counter. Complete them independently in both orders.
- [ ] In `awaitResult`, request cancellation through the handle when the coroutine
  cancels. In `awaitExecutionEnd`, cancellation ends observation only. Neither helper
  may manufacture execution-end evidence or cancel only a converted future copy.
- [ ] Test cancel-before-result, result-before-cancel and racing delivery. Retain
  original errors after completion-wrapper unwrapping. Require exactly one supported
  cancellation request from the cancelled result wait, not two from race callbacks.
- [ ] Reuse the existing Koog cancellation bridge pattern; preserve all argument/
  result validation if switching the adapter to the shared helper.
- [ ] Compile/run ordinary Java Portal use with no coroutines runtime. The existing
  compile-only dependency does not authorize adding a runtime requirement.

```bash
./gradlew :enforcer:test --tests '*InvocationHandleCoroutinesTest'
./scripts/koog-build.sh -- :integrations:koog:test
```

Run Koog commands only if that adapter is touched/qualified in this task; if changed,
the completion build is `./scripts/koog-build.sh -- build` to include the opt-in module.

## P05 — Warm-up through existing acquisition

- [ ] Resolve every selector before acquiring anything. Test unknown IDs alongside
  valid ones: the whole invalid selection must cause zero acquisitions.
- [ ] Test no-argument all-groups, explicit empty selection, duplicate IDs and two
  services sharing one group. Return only selected health after required readiness.
- [ ] Hold acquisition and overlap warm-up, binding and close. Observe one broker
  acquisition per group and no late publication. Retain a failed/late child owner
  for close; do not create a separate all-groups transaction.
- [ ] Repeat warm-up/calls with real workers and observe constructor count/PID.
  Failure after another group succeeded retains that group for runtime close.
- [ ] Keep readiness distinct from application/JIT warming. Warming before a host
  spawn restriction does not authorize later replacement under that restriction.

```bash
./gradlew :portals:runtime:test --tests '*PortalWarmUpTest'
./gradlew :portals:runtime:integrationTest --tests '*PortalWarmUpIntegrationTest'
```

## P06 — Finite batching, not a list of scalar RPCs

- [ ] Generate a declared batch method taking a list and returning an equal-length
  result list. A worker-entry counter must observe one method entry per chunk.
- [ ] Use a counting input iterator with batchSize=3/maxInFlight=2. Hold responses:
  at most six elements may be pulled ahead. Complete chunk 2 first; the first
  yielded result must still belong to chunk 1.
- [ ] Start one traversal deadline at cursor creation. Pass it through `submitUntil`
  with each call's configured clamp, rather than renewing time per chunk or wait.
- [ ] Deliver a result while holding its execution-end signal. Its slot remains
  occupied until consumed **and** physically ended. Keep unfinished handle references;
  a delivered result is not a free slot for unbounded cleanup accumulation.
- [ ] Test wrong cardinality, iterator exception, oversize, timeout and close retry.
  Stop pulling on failure; cancel only outstanding handles and preserve the primary
  error. Unknown chunk completion never permits replay or per-item rollback claims.

```bash
./gradlew :portals:runtime:test --tests '*PortalBatchesTest'
```

Then run the compiled generated batch fixture natively and P01 scalar/batch
comparison. The cursor needs no new executor or process owner.

## P07 — State locality and loss boundaries

- [ ] Use a singleton counter and a worker-local dictionary collaborator. Three
  queued calls from one submitter produce 1/2/3 with one PID/construction; concurrent
  submitters have no globally promised submission order.
- [ ] Two services intentionally in the same group share their collaborator and
  trust/failure scope. Separate groups use distinct processes. Conflicting launch
  shapes reject rather than silently splitting or merging the group.
- [ ] A completed business exception may have changed state but leaves the group
  usable. A crash/unknown reply/active cancellation makes it terminally lost.
  Queued cancellation before entry must not destroy the still-healthy singleton.
- [ ] Preserve synchronous overlap rejection; an async FIFO guarantee does not
  authorize queuing a previously rejected synchronous call behind itself.
- [ ] Exercise two stateless workers by holding concurrent calls, not relying on
  round-robin. Their per-worker instances/caches persist independently. Keep request/
  tenant authorization out of those caches; measure duplicated dictionary cost.

```bash
./gradlew :portals:runtime:test --tests '*PreparedPortalOperationTest' --tests '*PortalServiceRegistryTest' --tests '*PortalRuntimeTest'
./gradlew :portals:worker:test --tests '*PortalWorkerServiceRegistryTest'
./gradlew :portals:runtime:integrationTest --tests '*PortalStatefulQueueIntegrationTest'
```

## P08 — Acquired file capabilities through submission

- [ ] Read the FFM safety instructions before native changes. Reuse configured grant
  roots and `openat2` checks; do not reopen a host-supplied path inside the worker.
- [ ] Open a real owned grant, submit it while dispatch is held, close the caller's
  original, then require the worker to read the intended bytes from its duplicate.
- [ ] Duplicate under bounded borrow with `F_DUPFD_CLOEXEC` and a real acquisition
  receipt. Register each owner immediately; inject failure after the first of two
  acquisitions to prove partial-list cleanup. Never wrap an invented FD integer.
- [ ] Test queued cancellation, encode/send failure, worker death and failed pre-native
  close/retry. Execution end waits for invocation-owned duplicates even if guest
  work never started. The caller's original remains outside invocation ownership.
- [ ] Keep direct-inbound-only placement, traversal/symlink/nonregular/unknown-root
  controls and Java boxed root/service selectors. Qualify shared file offsets/live
  contents separately; descriptor duplication does not copy/freeze bytes.

```bash
./gradlew :portals:runtime:test --tests '*PortalCapabilitySubmissionTest' --tests '*OpenGrantedReadTest' --tests '*PortalGrantRootsTest'
./gradlew :portals:runtime:integrationTest --tests '*PortalFileInputIntegrationTest'
```

No new syscall is expected. If one is actually necessary, stop and scope its native/
architecture obligations rather than slipping it into a grant convenience change.

## P09 — Replacement budgets without application retries

- [ ] Test the pure reducer with supplied monotonic times: max=2/window=60 seconds
  admits attempts at 0 and 1, rejects at 2, and removes entries at the documented
  window boundary in a still-Running state. Zero attempts is valid; negative is not.
- [ ] Reject backwards test clock input. Model simultaneous requests through the
  existing serialized lifecycle owner, not a second independent atomic counter.
- [ ] Retire the failed slot, then count one admitted replacement attempt before
  launch/readiness. A launch failure consumes the attempt. Do not loop until ready.
- [ ] Exhaustion sets existing Failed admission and retains every resource for close.
  Clock expiry does not revive an already-Failed group. The triggering invocation
  keeps its original error; singleton workers never replenish themselves.
- [ ] Kill actual workers, deny a later spawn after initial successful warm-up and
  race close with replacement readiness. Observe bounded launch count, no late
  publication, no replay and real retirement. An exit alone is not OOM evidence.

```bash
./gradlew :portals:runtime:test --tests '*PortalReplacementMachineTest'
./gradlew :portals:runtime:integrationTest --tests '*ProcessBrokerIntegrationTest'
```

## P10 — One measured optimization at a time

- [ ] Use the post-correctness P01 baseline to identify the dominant cost. Write down
  the specific hypothesis, case matrix and expected observable improvement before
  changing encoding or transport. A no-beneficial-change result is valid evidence.
- [ ] Add a differential test: old/new valid wire bytes for codecs, or forced short
  write/EAGAIN/EINTR/EOF/deadline/cancellation behavior for I/O. Test both request and
  response paths touched by the change, plus exact retirement behavior.
- [ ] Change that one cost center. Keep CG06 writer bounds, public defensive copies,
  strict decode, absolute deadlines and confined arena ownership. Attempt-before-poll
  only applies to nonblocking sockets with correct EAGAIN handling.
- [ ] Re-run the same relevant cases/forks/resource budgets. Report distribution,
  failures, allocations and memory, not only a best sample. Revert only your own
  speculative change if it does not help; never remove a safety check for a speedup.

```bash
./gradlew :portals:runtime:test --tests '*PortalCodecTest' --tests '*PortalFrameTest' --tests '*PortalConfiguredTransportBoundsTest' --tests '*PortalOwnershipDeadlineTest'
```

Add affected native integration and P01 measurements. Native changes require their
own safety review; this issue does not authorize a buffer pool, shared arenas or a
JVM fork/zygote architecture.

## P11 — Bulk-input go or no-go

- [ ] Compare existing copied input and P08 regular-file grants at 64 KiB, 1 MiB,
  the frame limit and larger FD-only files. Include single-use and reused inputs,
  useful parsing, cleanup, RSS and allocation.
- [ ] If grants meet the application budget or copy cost is not dominant, write a
  no-go report with the raw comparison. That completes the research; do not build
  a shared-buffer feature just because the plan mentions one.
- [ ] Only if needed, use an explicitly disposable prototype of broker-created
  immutable input. Exercise mutation/truncation, wrong backing identity, size,
  cancellation, crash and cleanup. Keep mappings bounded and FDs one-way.
- [ ] Publish the ownership design and reproducible benefit/limits in the planned
  `docs/internals/research/portal-bulk-input-transport.md`. A go decision creates a
  separate scoped production issue; it does not activate the prototype.

**Required evidence:** P01 comparison/self-check, prototype controls if attempted,
and written decision. No production shared-memory API or worker-to-host FD result
is delivered by this task.

## P13 — Real-consumer usage and abrupt-host-death qualification

**Read first:** [P13 issue](../../internals/backlog/issue-20261103-101536-562-qualify-portal-process-boundary-usage-safeguards-w.md),
the design's usage matrix, `PortalWorkerMain.kt`, `PortalWorkerDriver.kt`,
`PreparedPortalOperationIntegrationTest.kt` and `WorkerPhysicalReceiptLifetimeTest.kt`.
Create the planned `PortalUsageSafeguardsIntegrationTest.kt` using the existing
packaged-worker fixture conventions, not a new runtime or protocol framework.

### P13.1 — Join evidence without duplicating owners

- [ ] Host/worker value fixture: set different host static/thread-local markers,
  send an explicit correlation field and require a distinct worker PID plus the
  explicit value. Never propagate arbitrary context/environment automatically.
- [ ] Reuse P03 mutation and P06 bounded-consumption controls. Keep constructors,
  getters, mappings and result processing identified as trusted host work.
- [ ] Repeated bind/warm/calls: assert configured processes/constructor counts, not
  a new pool per binding. Hold two stateless calls to prove persistent independent
  graphs without assuming scheduling affinity.
- [ ] Failure fixture: queued NOT_STARTED, completed business error and an authorized
  effect before response loss/UNKNOWN_COMPLETION. Observe no replay and terminal
  singleton loss. Result cancellation is separate from physical-end observation.
- [ ] Missing bundle/profile/delegation and real denied filesystem/network operation:
  preserve stage/service/group/cause. Do not widen the policy to make the dependency
  or test work. OOM controls use authorized finite child **and parent** budgets,
  cgroup event deltas and separate results; an unexplained crash is not OOM proof.

### P13.2 — Why killing the worker is not the host-death test

In ordinary close tests, the live broker can kill/reap its workers. In a crash,
the broker itself is gone. The driver notices EOF between synchronous dispatches;
it cannot notice it while a guest never returns. Main return also need not end
non-daemon threads, and shutdown hooks can stall JVM exit.

The existing receipt-lifetime test proves containment evidence remains retained
while guest/hook work is alive, then lets its **live parent** force reap. Keep that
test; it proves something different from orphan-free cleanup after the host dies.
No end-to-end orphaning defect is claimed until the new control is actually run.

### P13.3 — Three separate process roles

```text
controller/test process (survives; owns observation deadline and rescue)
  -> application-host subprocess (real runtime/broker; deliberately SIGKILLed)
     -> actual restricted Portal workers (the cleanup subjects)
```

The controller is outside the failure being tested. Never SIGKILL the Gradle test
runner, shell session, all Java processes or an arbitrary numeric PID from a log.
Use only test-acquired identities/handles. The controller must have a safe retirement
path for reparented workers before it starts any destructive scenario.

Keep protocol/barrier events test-local, bounded and validated. Suggested event
names and required content:

| Event | Sent by | Required content / purpose |
|---|---|---|
| `HOST_STARTED` | Fixture host | Host PID/start identity and case ID |
| `WORKER_ACQUIRED` | Existing launch seam wrapped by fixture | Exact child identity before readiness; controller registers rescue ownership |
| `CASE_ARMED` | Fixture after the relevant real barrier | Case ID and matching worker identity; not a guessed delay |
| `HOST_DEATH_OBSERVED` | Controller | Actual host wait/exit observation after forced death |
| `WORKER_DEATH_OBSERVED` | Controller | Which acquired identity ended, when, and observation method |
| `RESCUE_REQUIRED` | Controller | Identity still alive after the declared observation bound; qualification fails |

Events are not a production wire format or a new diagnostic API. Do not log payloads
or environment values. Use existing launcher injection/fixture conventions rather
than adding a public bypass/testing switch. For startup-before-readiness coverage,
hold the actual acquired-child handoff until the controller has registered that
child; waiting for `runtime.health()` after readiness misses the startup window.

### P13.4 — Required scenario matrix

| Case | Barrier before killing host | What it distinguishes |
|---|---|---|
| Graceful control | Ready/call finished; do not SIGKILL, close normally | Existing close/reap still works |
| Startup | Child acquired, controller registered it, required readiness not published | Provisional child ownership after parent loss |
| Idle | Required ready, no active guest call | EOF handling without active dispatch |
| Non-returning call | Guest-entry event, method held beyond observation deadline | Cleanup not dependent on the method returning |
| Non-daemon background thread | Method returns while a proven guest thread remains active | Main/driver return is not JVM death |
| Stalled shutdown hook | Real hook-entry event while the hook is held | Cleanup not dependent on cooperative shutdown completion |

Use a single worker first. Add more only where needed to test the declared group
counts. Keep the guest held longer than the observation interval so a timed fixture
release cannot make a broken cleanup path appear successful. All fixture holds
have an outer rescue deadline; a deliberately non-returning method must never make
the test controller itself unbounded.

### P13.5 — Controller sequence and bounded cleanup

Use this sequence for each crash scenario; a prose instruction to “kill the parent”
without acquisition, observation and rescue is not a complete test:

1. Validate kernel/permission/process-ownership prerequisites. Establish outer owned
   descendant cleanup using existing project facilities. If unavailable, leave the
   lane unqualified and do not launch children you cannot safely retire.
2. Start the host fixture, drain bounded stdout/stderr and acquire its process owner.
   Validate event count/order/case/identity. Keep the host/worker graph outside the
   soon-to-die host; a descendant query only after parent death can miss reparenting.
3. Register each worker as it is acquired. Associate PID with start identity and an
   acquired stable process reference/OS ownership primitive where available. Never
   reacquire and signal an unrelated process because its PID matches an old line.
   Missing/changed identity is a failed or unqualified observation, not cleanup proof.
4. Wait for the selected scenario's barrier. Suggested fixture bounds are 15 seconds
   for setup, 10 seconds for post-host-death observation and 5 seconds for rescue,
   within an overall case bound that also allows pump shutdown. Declare these as
   test bounds, not a new public runtime cleanup guarantee. Tune only with recorded
   environment evidence, not by deleting the worst case.
5. Force-kill only the acquired host and confirm actual host death. The Linux lane
   must establish SIGKILL/no host close hooks; merely sending a cooperative signal
   exercises a different guarantee. Start the observation deadline at confirmed death.
6. Observe each acquired worker without signalling it. Capture the outcome **before**
   running any rescue, resource-wrapper teardown or deployment supervisor cleanup
   that is not part of the mode being qualified. EOF, removed sockets, a result
   future and a receipt-retention flag do not establish physical death.
7. For direct child owners use the existing wait/reap path. For reparented workers,
   use the controller's approved ownership/identity mechanism and distinguish
   execution cessation from final reap. Do not label a still-alive/zombie or unknown
   observation “clean”. PID/start checks alone are not race-proof signal ownership;
   reuse existing safe process primitives rather than implementing native PID logic
   casually in this task.
8. Save a pass only when every required worker ended within the observation bound
   through the claimed cleanup owner. If still alive/unknown, save failure first,
   then rescue only owned processes, observe retirement, drain/join pumps and report
   rescue failure too. A rescue kill does not turn qualification GREEN.
9. Run `finally` cleanup on success, assertion failure, bad events and timeout. It
   must retire remaining acquired resources without discarding the primary failure.
   The test is unfinished if it leaves a worker for the developer to kill manually.

The controller owns the monotonic clock for its observation bounds and records
event-receipt times. A guest timestamp does not share a portable epoch with the
controller's clock. Enforce one overall case deadline as well as phase bounds;
do not restart a full observation budget on each poll or worker.

If the mode relies on a deployment supervisor/container process-tree kill policy,
name its exact configuration and run it as a **separate** mode. Show which owner
killed the worker. An outer CI resource probe rescuing descendants after a failed
test is safety infrastructure, not evidence that the library handles host death.

### P13.6 — Failure triage and evidence

For each scenario keep: source/artifact/JDK/kernel identities; host/worker start
identities; barrier and death timestamps; configured bounds; observed result; rescue
attempt/outcome; and claimed cleanup owner. Store failures as failures even if the
controller subsequently recovered the test machine safely.

If a real scenario fails, create a scoped corrective issue using the installed ADK,
link the reproduction/evidence, and add it as a P13 closure blocker. Identify the
existing launch/broker/worker owner; do not immediately add a watchdog, parent-death
syscall or privileged service. Those require their own reviewed lifecycle/native
design if investigation demonstrates they are necessary. Keep current public
guidance explicit about the unqualified mode until fixed/requalified.

```bash
./gradlew :portals:runtime:integrationTest --tests '*PortalUsageSafeguardsIntegrationTest'
```

Require actual execution on the target environment, relevant generated-source
checks, strict Java/Kotlin consumers and the full completion gate. Mocks, skipped
cases, ordinary close tests and rescued children cannot close the crash lane.

## Independent published consumers

Follow [the consumer README](../../../tests/consumers/README.md) and
[Portal consumer README](../../../tests/consumers/portal-plugin/README.md). Their
historical worktree paths are context, not permission to create one for this task.

- [ ] Stage matching core/runtime/worker/codegen and plugin marker publications
  locally using the existing approved staging/bootstrap flow. Keep all MazeWall
  coordinates on one exact source/artifact identity; no remote publication occurs.
- [ ] If source is dirty, record its complete owned source identity/diff alongside
  the committed anchor. Never label a publication “exact HEAD” while ignoring its
  changed source. Coordinate a proper candidate with the operator for acceptance.
- [ ] Copy the fixture into a new owned evidence directory; do not overwrite its
  historical locks. Generate/review that coordinate's locks once, then run acceptance
  without lock rewriting. Retain graph/compiler/JAR hash reports.
- [ ] Use the shared verification lock correctly. Tasks that already acquire it
  internally must not be wrapped with the same external lock; standalone Portal
  fixture execution follows its README's external lock requirement.
- [ ] Require real Java and Kotlin compilation of affected entrypoints, actual JAR
  origins and no project/source substitution. Keep compiler metadata/prerelease
  skip flags forbidden. Java callers need boxed selector overloads where specified.
- [ ] Keep compile-only publication proof separate from native guest execution.
  `verifyPublishedPlugin` currently launches no worker; use the owning real-worker
  tests for startup/denial/lifetime guarantees and record that distinction.

P02 supplies convention-based consumers. **P12's earlier gate uses existing explicit
registration/configuration** and all Java constructor arguments, so it can complete
before P02. P03/P08 add descriptor/selector/grant calls to the appropriate consumers.

## Review checkpoint and definition of done

Ask for the required scoped review **before broadening the fix** if investigation
points at one of these boundaries:

| Boundary | Bring to review | Do not decide silently |
|---|---|---|
| CG03 public metadata compatibility | Old/new canonical records and mismatch controls | A new public manifest format |
| CG05 validation/checkout lifetime | Exact current call chain and held-validation reproduction | A second checkout owner or changed raw invoke semantics |
| P03/P06 blocked host work/cleanup | Held-getter/held-end evidence and current owner state | Forcibly stopping host threads or dropping owners to make close pass |
| P08/native changes | Actual acquisition receipt, layouts and failure paths | New syscalls, raw FFM in runtime or invented FD ownership |
| P13 failed host-death mode | Acquired identities, event/death/rescue evidence | A new watchdog, parent-death mechanism or privileged deployment requirement |

This is a review of a concrete proposed correction, not a reason to request
permission before every already-scoped test or edit.

Before presenting a task as complete, answer all of these with file/test evidence:

1. **Scope:** Does every changed production/test/build file belong to this issue?
   Were all callers and generated/public consumer implications covered?
2. **Types:** Are closed outcomes exhaustive, selectors strict, constructors/setters
   minimally visible, and returned collections truly owned? New sealed subtypes
   follow existing architecture-test obligations, not an ad hoc source scan.
3. **Ownership:** Who owns every child, FD, buffer, admission and unfinished handle?
   On every failure path, when is that owner allowed to disappear?
4. **Deadline:** Is there one absolute deadline instead of a renewed timeout at
   each stage? Can blocked trusted host code leave retryable cleanup correctly held?
5. **Compatibility:** Did Java and Kotlin callers compile against actual artifacts?
   Are legacy methods/default bridges/valid bytes preserved as the issue requires?
6. **Security:** Did required startup/permission failures remain fail-closed? No
   fallback execution, weakened baseline, environment capture or extra authority?
7. **Proof:** Does the evidence level match the claim? Real child/kernel/delegation
   requirements are not satisfied by unit mocks, cached results or a diagnostic flag.
8. **Documentation:** Do examples label proposed/current APIs correctly, explain
   unsupported shapes, retained results, memory scope and actual cleanup owner?

Completion commands for included modules after focused checks:

```bash
./gradlew build
./scripts/adkw check-backlog
git diff --check
git diff --stat
git diff --cached --stat
```

The full build may require the configured native/container environment. If it
cannot run, record the blocker and focused evidence; do not claim a full pass or
archive an issue with unmet acceptance. Koog changes require its opt-in build as
specified in P04. Documentation-only edits require link/reference, backlog and
whitespace checks rather than a gratuitous kernel run.

Use a review note with these concrete fields:

```text
Issue/card and changed behavior
Source revision plus owned dirty-source identity, if any
Changed files and why each belongs to the issue
Regression before the fix: exact command and intended failure
Focused checks after the fix: commands, selected tests, counts and outcomes
Native/consumer/benchmark evidence: exact identities, environment and limitations
Full quality gate: command and outcome, or explicit blocker
Unfinished qualification, corrective dependencies and residual risks
```

Do not tick a checkbox because code was written; tick it when its stated result
exists and its required proof has run. Resolve/archive only through the current
approved backlog/review path. Keep skipped/unqualified work visible, and never
commit, merge or publish unrelated changes as part of “finishing” this plan.
