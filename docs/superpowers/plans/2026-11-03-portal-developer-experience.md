---
document_type: execution_plan
title: "Thread-like Portal Developer Experience Implementation Plan"
base_revision: b2c9b335720fd0491a8d86917a3b0593b818a4ec
---

# Thread-like Portal Developer Experience Implementation Plan

> **For agentic workers:** Use `executing-plans` task-by-task; use
> `subagent-driven-development` only when the operator selects delegation.
> Follow the repository's orchestrator-managed start/review/merge path. Checkboxes
> track implementation, not approval or evidence already obtained.

**Goal:** Deliver executor-like use of sandboxed local JVMs, with generated typed
operations, predictable ownership and measured warm-call costs below an equivalent
API service on explicitly qualified workloads, with explicit process-boundary usage
rules and measured whole-deployment memory costs.

**Architecture:** Extend the current runtime, code generator, broker admission,
worker queue and lifecycle machines. Preserve process containment and copy/capability
boundaries; put interface convenience above those owners. Measure before changing
transport mechanics, and make shared-buffer work an evidence-driven decision.

**Tech Stack:** Existing Kotlin/Java, JDK 25 target with the repository's JDK 22+
compatibility rules, Gradle/TestKit, KotlinPoet, JUnit, Unix sockets, Seccomp/Landlock,
existing compile-only kotlinx.coroutines; no new third-party dependencies.

**Spec:** [Thread-like Portal developer experience](../../internals/designs/portal/portal-developer-experience.md),
[local service isolation](../../internals/designs/portal/local-service-isolation-design.md),
and [process portal ownership](../../internals/designs/enforcer/process-portal-design.md).

**First implementation in this repository?** Read the detailed
[execution guide](2026-11-03-portal-developer-experience-execution-guide.md) before
starting a task. It provides vocabulary/ownership diagrams, preflight commands,
RED/GREEN procedure, a work card for every CG/P issue, concrete capacity-test
fixtures, benchmark timer/accounting rules and a crash-controller walkthrough.
This plan remains the file/scope/dependency inventory; the design remains the API
contract. Examples and expected results in either document are not executed proof.

## Global constraints

- Fail closed. Never swallow, downgrade, or silently bypass `EPERM` or `EACCES`;
  fallback behavior is opt-in only. No host execution fallback is added here.
- Preserve broker-to-worker-only capability FDs and copy-in/copy-out values;
  no Java serialization, host-captured guest factories or arbitrary closure shipping.
- Guest construction follows the existing process-wide containment and required
  generation-bound readiness. Landlock setup precedes Seccomp installation.
- Keep one absolute deadline through admission, encoding, queueing and transport.
  Result delivery/cancellation never proves physical execution end.
- Preserve legacy public signatures, wire IDs and failure evidence. New configuration
  retains existing constructors and Kotlin default bridges. No added dependency or
  breaking API change is authorized by this plan.
- Stateful singleton loss is terminal; replacement never replays an unknown call.
  Groups share trust and failure scope only when explicitly configured together.
- Report total configured processes and distinguish heap/cgroup ceilings from actual
  footprint. Request-byte admission does not bound caller-held results or DTO code.
  Keep custom heap/cgroup memory limits paired; the convenience baseline uses the
  existing 64 MiB default heap. No automatic trust-group merging or pool resizing.
- Read nearest module AGENTS, required design documents, and relevant safety skills
  before implementation. Use `./scripts/adkw blast-radius <Symbol>` before changing
  core/cross-module symbols and inspect all callers, fixtures and public API dumps.
- Existing uncommitted changes are user-owned, including the codegen functional test,
  dependency locks and backlog index. This plan was prepared against the current
  dirty checkout; `base_revision` is the committed anchor, not a clean-tree claim.
- Work in the current checkout unless the operator explicitly authorizes a worktree.
  Follow scoped backlog starts and serial reviews/merges; planning starts no worker.

## Current implementation and reuse

| Existing location | Reuse / extension |
|---|---|
| `portals/runtime/.../PortalRuntime.kt` | Registry, binding, broker acquisition, close; add generated bind and warm-up |
| `PortalOperationOwner.kt`, `OwnedPortalInvocation.kt` | Existing finite queue, count admission, cancellation and physical retirement |
| `PortalStatefulInvocationGate.kt`, `PortalServiceRegistry.kt` | Atomic loss/overlap handling and compatible group validation |
| `PortalCodec.kt`, `PortalPayload.kt`, `PortalIo.kt`, `PortalChannel.kt` | Bounded readers/frames, ownership copies and transport deadlines; CG06 adds encoding bounds |
| `portals/codegen/.../PortalServiceSchema.kt`, `PortalStubGenerator.kt` | Existing method/shape model and named IDs; generate operation metadata |
| `PortalCodegenPlugin.kt`, `PortalManifestGenerator.kt` | Explicit seven-field registration, separate worker sources, verified bundles |
| `portals/worker/.../PortalWorkerConstructionContext.kt` | Worker-local shared collaborators after containment |
| `enforcer/.../api/execution/InvocationHandle.kt` | Existing result/end stages and cancellation owner |
| `integrations/koog/.../PortalToolAdapter.kt` | Existing cancellation-aware coroutine delivery pattern |
| `benchmarks/integration-boundaries` | Historical checking harness; reuse staging/measurement conventions in a separate comparison build |
| `tests/consumers/portal-plugin` | Strict external artifact/plugin/host/worker closure checks |

Paths abbreviated in this table expand under `src/main/kotlin/io/mazewall/`.
Per-task inventories below use exact paths. Reuse an existing focused file if its
responsibility matches; do not split unrelated code or edit generated output.

## Backlog and dependencies

| ID | Deliverable / issue | Depends on |
|---|---|---|
| P01 | [Equivalent-work Portal/API benchmark](../../internals/backlog/issue-20261103-085927-123-compare-warm-portal-calls-with-executor-and-persis.md) | Current runtime |
| P02 | [Build conventions and plain consumers](../../internals/backlog/issue-20261103-090025-581-simplify-portal-registration-and-single-project-wo.md) | CG02, CG03, CG07, P12 (see prerequisites below) |
| P03 | [Generated operations and bounded snapshots](../../internals/backlog/issue-20261103-090025-757-generate-typed-portal-operations-with-bounded-subm.md) | P02, CG04, CG05, CG06 |
| P04 | [Coroutine result/end awaits](../../internals/backlog/issue-20261103-090025-919-expose-cancellation-aware-coroutine-awaits-for-own.md) | P03 for generated consumer qualification; helper can be developed against existing InvocationHandle |
| P05 | [Explicit group warm-up](../../internals/backlog/issue-20261103-090026-112-add-explicit-portal-warm-up-through-existing-group.md) | P03 for bind/close qualification; acquisition logic uses current runtime |
| P06 | [Bounded batching](../../internals/backlog/issue-20261103-090026-291-provide-bounded-portal-batching-over-explicit-batc.md) | P01, P03 |
| P07 | [Stateful actor-style bindings](../../internals/backlog/issue-20261103-090026-517-qualify-actor-style-stateful-portal-bindings-on-th.md) | P01, P03, P05 |
| P08 | [Owned asynchronous file capabilities](../../internals/backlog/issue-20261103-090026-755-make-granted-portal-file-inputs-convenient-and-own.md) | P01, P03 |
| P09 | [Bounded stateless supervision](../../internals/backlog/issue-20261103-090026-932-bound-stateless-portal-worker-replacement-with-an-.md) | P05 for warm-up/close qualification; reducer can be developed against current lifecycle |
| P10 | [Measured codec/transport optimization](../../internals/backlog/issue-20261103-090027-201-reduce-measured-portal-codec-and-transport-overhea.md) | P01, P03, P06 |
| P11 | [Bulk-input transport evaluation](../../internals/backlog/issue-20261103-090027-452-evaluate-an-optional-immutable-bulk-input-transpor.md) | P01, P08, P10 |
| P12 | [Aggregate capacity report and pre-launch budget](../../internals/backlog/issue-20261103-101536-305-expose-and-validate-aggregate-portal-capacity-befo.md) | Current validated runtime configuration |
| P13 | [Process-boundary usage qualification](../../internals/backlog/issue-20261103-101536-562-qualify-portal-process-boundary-usage-safeguards-w.md) | P06, P07, P08, P09, P12 |

Reuse these existing work items without reopening or replacing their ownership:

- [Production worker packaging separation](../../internals/backlog/issue-20261028-230731-061-separate-production-portal-worker-packaging-from-p.md): slim the profiler dependency closure and preserve CLI compatibility. It can proceed alongside P01/P02; include its resulting artifact in final measurements.
- [Representative packaged application adoption](../../internals/backlog/issue-20261028-230731-805-measure-realistic-packaged-application-adoption-an.md): owns application SLO selection, unfamiliar-developer effort, real deployment comparison and final adoption evidence. P01 supplies a controlled baseline rather than duplicating that study.

**Milestones:** M0 = P01 latency/memory baseline and P12 capacity planning;
M1 = P02/P03/P04/P05 (small typed submission and ready workers);
M2 = P06/P07/P08/P09 (bulk work, retained state, inputs and failure control);
M3 = P01 comparison updated for M1/M2, packaging separation, P10, P13 and adoption
qualification. P11 is conditional research after M3, with a valid no-go outcome.
These are dependency lanes, not authorization to run agents in parallel.
Dependencies are completion prerequisites, including promised consumer/measurement
checks; isolated helpers may be developed earlier without closing their issue.

### Codegen hardening prerequisites

The subsequent [codegen review and CG01–CG07 issues](../../internals/reviews/2026-11-03-portal-codegen-hardening.md)
identify corrective work before expanding the generated API. P02 consumes the
validated contract/schema and corrected publisher; its diagnostics report method
IDs, field paths, schema/codec version, limits and artifact roles without payload
values. P03 reuses strict decoding, validation-owned checkout and CG06's bounded
writer, and assigns explicit host/worker/metadata roles to new artifacts instead of
routing by filename suffix. P10 profiles that same writer after correctness lands.
These are shared prerequisites, not additional execution engines or duplicate codec
implementations. CG05 still requires a real-worker malformed-response regression.

Junior implementation work cards:
[CG01](2026-11-03-portal-developer-experience-execution-guide.md#cg01--compilable-boundary-types),
[CG02](2026-11-03-portal-developer-experience-execution-guide.md#cg02--complete-validated-dto-shapes),
[CG03](2026-11-03-portal-developer-experience-execution-guide.md#cg03--full-schema-identity),
[CG04](2026-11-03-portal-developer-experience-execution-guide.md#cg04--strict-decoding),
[CG05](2026-11-03-portal-developer-experience-execution-guide.md#cg05--validation-owned-checkout),
[CG06](2026-11-03-portal-developer-experience-execution-guide.md#cg06--bounded-encoding-before-allocation),
[CG07](2026-11-03-portal-developer-experience-execution-guide.md#cg07--disjoint-output-publication).

### Usage and memory prerequisites

The design's [usage safeguards](../../internals/designs/portal/portal-developer-experience.md#10-process-boundary-usage-safeguards)
and [memory/capacity contract](../../internals/designs/portal/portal-developer-experience.md#11-memory-model-and-aggregate-capacity)
add P12/P13 and extend existing owners. P01 owns footprint evidence; P03 owns retained
request bytes; P06 owns bounded result traversal; P07 explains pooled state; P09 owns
replacement behavior. The earlier 256 MiB convenience heap was incompatible with the
existing paired heap/cgroup profile invariant and is corrected to the 64 MiB launcher
default. Larger profiles remain explicit and require delegated cgroup authority.

## Verification applied to every implementation task

Each task starts with a meaningful failing regression for its changed behavior,
then the minimal implementation, focused GREEN checks and one scoped connected-code
review. Each task updates the relevant guide/design section and its issue evidence.
Do not archive an issue on mock-only evidence when its acceptance requires a child.

```bash
./gradlew :portals:runtime:compileKotlin :portals:codegen:compileKotlin :portals:worker:compileKotlin
./gradlew :portals:runtime:test :portals:codegen:test :portals:worker:test
./scripts/adkw check-backlog
git diff --check
```

Select only affected modules during iteration; exact focused tests are listed below.
Use the owning integration task or fresh-JVM task for native changes, following
[testing strategy](../../internals/designs/core/testing-strategy.md). At each completed
implementation checkpoint run `./gradlew build` once after focused checks. A final
build already selecting a required integration suite is sufficient; do not repeat
equivalent gates without a change/failure or concrete unresolved concern. Publication
consumers use the existing local staging/strict-lock workflow in
[tests/consumers/README.md](../../../tests/consumers/README.md), not remote publishing.
When dependency declarations change, regenerate/review locks and verification
metadata with the repository's prescribed command.

---

## P01 — Establish an equivalent-work performance baseline

**Files:**
- Modify `benchmarks/integration-boundaries/README.md` to link the comparison.
- Create `benchmarks/portal-comparison/build.gradle`, `settings.gradle`, `README.md` and independent dependency locks.
- Create `benchmarks/portal-comparison/src/main/kotlin/io/mazewall/benchmark/PortalComparison.kt`, `PortalComparisonSelfCheck.kt`.
- Create `benchmarks/portal-comparison/src/api/java/io/mazewall/benchmark/ComparisonService.java`.
- Create `benchmarks/portal-comparison/src/portalWorker/kotlin/io/mazewall/benchmark/ComparisonWorker.kt`.

**Interfaces:** A separate `benchmarks/portal-comparison` build with `portalComparison`
and assert-based `portalComparisonSelfCheck` tasks and versioned comparison output;
existing `BoundariesKt`, P617 locks, raw schema and acceptance remain valid. Reuse
the existing benchmark's strict staged-artifact workflow and pin the existing
Portal plugin/runtime to that same version with independent locks. Use
`jdk.httpserver` and `java.net.http` for the persistent HTTP peer; no JMH, test or
server framework dependency is needed.

**Step-by-step execution:** [P01 work card](2026-11-03-portal-developer-experience-execution-guide.md#p01--fair-performance-and-whole-deployment-memory-measurements)
defines timer start/stop points, offered/admitted/completed accounting, result
observation, RSS/PSS units, cold-cache labels, exact future task commands and required
evidence. Implement correctness controls before any measurement matrix.

- [ ] **1. Add independent correctness controls before timing.** Use a fixed ASCII
  transform oracle and bounded parse/summarize fixture. Assert all four modes
  produce identical bytes and the Portal/HTTP implementations run in child PIDs:
  `check(expected.contentEquals(actual)); check(hostPid != workerPid)`.
  Corrupt one output and require a failed run; failure cannot produce a success row.
- [ ] **2. Add the new task and actual packaged stateless worker.** Use current
  explicit source-set/registration wiring until P02 lands. Separate host oracle
  code from opaque worker implementation. HTTP gets its own JVM, reused connection,
  equal binary codec and bounded request executor. HTTP/JSON is a separately
  labelled optional end-to-end comparison, never an assumed transport advantage.
- [ ] **3. Implement a bounded matrix.** No-op and useful work; 64 B, 1 KiB, 64 KiB
  and 1 MiB payload cases; one/four workers and concurrency 1/4/16 where configured
  capacity permits. Use at least three fresh-JVM forks, warm-up until a declared
  duration/sample floor, and at least 10,000 warm observations for reported p99.
  Keep timeout/overload samples in accounting. Add fixed-rate runs that measure
  arrival-to-completion to expose queueing, beside closed-loop throughput.
- [ ] **4. Keep measurement attribution honest.** Record startup/materialization,
  first-call and warmed distributions separately; retain raw samples, bytes,
  offered/accepted/rejected counts, JVM/kernel/CPU identity, artifact digests,
  host/worker RSS and CPU/allocation observations with their collection scope.
  Only benchmark oracle checks, environment fingerprinting and observer logging stay
  outside timed intervals. Warm end-to-end calls include production admission,
  snapshot/encoding, transport, decoding and reply validation. Cold/startup timing
  includes required artifact hashing and launch/readiness validation. Check real
  ProcessHandle death after close. Publish no universal faster-than-HTTP conclusion.
- [ ] **5. Verify and record the baseline.** Run the new host-only
  `portalComparisonSelfCheck` and `portalComparison` against staged pinned artifacts
  using the README's finite
  outer execution/resource bounds. Expected: equal outputs, complete accounting,
  clean physical ends and descriptive metrics for every declared case. Link the
  report to the existing adoption issue; no latency threshold is inferred from P617.
- [ ] **6. Add a separate whole-deployment memory pass.** Sample no-worker host,
  cold start, warm idle, useful busy work, a saturated finite queue, consumed/retained
  results and replacement/close. Sweep one/two/four total workers and one/multiple
  groups. Include mutable worker-cache duplication and compact versus nested DTOs.
  Record host/worker RSS, PSS/private memory and parent/worker cgroup current/peak/events
  where authorized, with timestamps and unavailable reasons. Do not add parent and
  child cgroups or present summed RSS as unique physical usage. Compare the HTTP child
  and Portal at equal workload/pool budgets; never infer results from heap caps.
  Self-check sample aggregation with two fixtures whose RSS is 100 each and PSS is
  60 each: reported summed PSS is 120, summed RSS 200 is separately labelled. Missing
  samples remain unavailable. Qualify physical shutdown separately from heap/RSS decay.
  Memory sampling/NMT runs stay outside the principal latency timing pass.

## P02 — Make ordinary single-project builds sufficient

**Files:**
- Modify `portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalCodegenPlugin.kt`, `PortalManifestGenerator.kt`, `PortalDiagnosticTasks.kt`.
- Create `portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalServiceSpec.kt`.
- Modify `portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRuntimeConfig.kt`.
- Create `portals/codegen/src/test/kotlin/io/mazewall/portal/codegen/PortalConventionsFunctionalTest.kt`.
- Extend `tests/consumers/portal-plugin/build.gradle` and add plain Java/Kotlin fixtures under that consumer.
- Modify `docs/guides/portal-service-isolation.md` and `tests/consumers/README.md`.

**Interfaces:** `PortalCodegenExtension.services: NamedDomainObjectContainer<PortalServiceSpec>`;
each spec has `contractName`, `implementationName`, `instanceMode`, `policyProfile`,
and optional `isolationGroup`/`workerArtifact` Gradle properties. Registration name
is the stable service ID. Add Java-static `PortalRuntimeConfig.loadBaseline()`.

**Step-by-step execution:** [P02 work card](2026-11-03-portal-developer-experience-execution-guide.md#p02--build-conventions-and-onboarding).

- [ ] **1. Write clean Java-authored and Kotlin-authored TestKit fixtures.** Use
  `src/portalApi`, `src/portalWorker`, and `src/main` with this opt-in declaration:
  ```kotlin
  portalCodegen.services.register("parser") {
      contractName.set("example.DocumentParser")
      implementationName.set("example.DocumentParserImpl")
      instanceMode.set(PortalInstanceMode.STATELESS)
      policyProfile.set("portal-compute")
  }
  ```
  Assert `build`, `portalCheck` and `portalDescribe` work without manual API JAR
  wiring. A fixture containing a guest compile error must fail ordinary build.
- [ ] **2. Wire the dependency graph lazily.** API compilation produces an API JAR;
  generation consumes it; host and worker compilation consume generated outputs.
  Only explicitly declared API/worker dependencies enter the worker bundle. Keep
  the existing atomic output publisher, old registration list, source-set overrides
  and metadata format. Reject duplicate/colliding configuration rather than merging
  unrelated groups or choosing a service arbitrarily.
- [ ] **3. Add the explicit baseline convenience.** Supply only `portal-compute`
  through `PortalWorkerProfile()` and the existing 64 MiB launcher heap plus
  protocol/deadline defaults. Test that
  an unknown required profile still rejects. Preserve old constructor/load/open
  entrypoints and their behavior; do not change zero-argument open implicitly.
  Custom heap/memory profiles still require both values and cgroup delegation.
  Explain P12's actual runtime capacity report; build-only diagnostics report known
  group topology and label runtime-selected limits unresolved rather than executing
  application configuration or guessing effective heap/RSS.
- [ ] **4. Exercise packaging failures and build reuse.** Test clean/repeated and
  relocated builds, configuration-cache reuse, changed/deleted registrations, bad
  nested boundary values, overload names, missing bundle and guest-class absence
  from the host loader. Plain Java may use plugin-managed Kotlin-generated code;
  it must require no handwritten Kotlin or new language processor.
- [ ] **5. Deliver runnable guidance and verify.** Include complete Java
  try-with-resources and Kotlin use examples, explicit supported Linux/JDK setup,
  synchronous typed result and failure diagnostics. Run
  `./gradlew :portals:codegen:test --tests '*PortalConventionsFunctionalTest'`
  and the strict published-plugin consumer. Expected: no absolute build paths in
  metadata and the same example works from a fresh installation directory.

## P03 — Bind generated operations with bounded submit-time ownership

**Files:**
- Create `portals/runtime/src/main/kotlin/io/mazewall/portal/PortalOperation.kt`, `PortalSubmissionLimits.kt`; reuse/extend CG06's `PortalRequestWriter.kt` or equivalent shared codec helper.
- Modify `portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRuntime.kt`, `PortalRuntimeConfig.kt`, `PortalCallAdmission.kt`, `PortalOperationOwner.kt`, `OwnedPortalInvocation.kt`.
- Modify `portals/runtime/src/main/kotlin/io/mazewall/portal/PortalInvocationLifecycle.kt`, `PortalInvocationRetirement.kt`, `PortalServiceManifest.kt` for owned snapshot phases and Java-boxed service selectors.
- Extend `portals/runtime/src/main/kotlin/io/mazewall/portal/PortalGeneratedNames.kt` and `portals/runtime/src/test/kotlin/io/mazewall/portal/PortalGeneratedNamesTest.kt` for descriptor/argument-class collision checks.
- Modify `portals/runtime/src/main/java/io/mazewall/portal/PreparedPortalOperation.java`.
- Create `portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalOperationGenerator.kt`; modify `PortalStubGenerator.kt`, `PortalServiceSchema.kt`, `PortalCodegenPlugin.kt`.
- Create `portals/runtime/src/test/kotlin/io/mazewall/portal/PortalSubmissionSnapshotTest.kt`, `PortalSubmissionBudgetTest.kt`.
- Create `portals/codegen/src/test/kotlin/io/mazewall/portal/codegen/PortalOperationGeneratorTest.kt`.
- Extend `portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/PreparedPortalOperationIntegrationTest.kt` and the P02 published fixtures.

**Interfaces:** The `PortalOperation<S,A,R>` descriptor and `runtime.bind` overloads
specified in the design; `PortalSubmissionLimits(maxRetainedRequestBytes: Long)`;
generated `DocumentParserPortal.PARSE`. Add `submit(A, Duration)` and internal
`submitUntil(A, Deadline)` while retaining `submit(A)`. Encoder/decoder types are
public only for generated cross-artifact use, immutable and marked internal API.

**Step-by-step execution:** [P03 work card](2026-11-03-portal-developer-experience-execution-guide.md#p03--generated-submissions-and-byte-ownership).

- [ ] **1. Prove the snapshot and budget contract in RED.** Extend the existing
  held-worker fixture: hold the first call, submit a second with mutable bytes,
  mutate immediately after submit returns, release and assert original bytes:
  ```kotlin
  val bytes = byteArrayOf(1, 2, 3)
  val queued = copyOperation.submit(bytes)
  bytes.fill(9)
  release.countDown()
   assertContentEquals(byteArrayOf(1, 2, 3), queued.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
  ```
  Also reserve the full byte budget and assert a further call ends NotStarted
  without encoder or guest entry. Exercise nested DTO/collection/byte-array values.
  Block a getter during submit, then close the runtime: assert no early execution
  end or released reservation, finite close failure, and no late dispatch when
  the getter resumes. Retry close and require complete retirement.
- [ ] **2. Implement ownership in the existing admission path.** Capture deadline
  before reservation; reserve count and worst-case live encoder/request/copy bytes
  atomically with admission. Register snapshot-in-progress before invoking getters
  outside locks; shrink bytes after encoding and publish only while still admitted,
  uncancelled and before expiry. Close joins admitted encoders through this owner,
  even though the dispatch executor has not seen them. Transfer one immutable
  request to queued execution. Release every reservation exactly once on encode failure,
  timeout, cancellation, close and fatal error. Preserve retained physical cleanup
  independently; a converted future cannot release admission. Use pure transition
  decisions and existing retirement hooks rather than another owner registry.
- [ ] **3. Generate descriptors from the resolved schema.** Emit CG06 bounded-writer
  calls and CG04 strict decoders with existing declared-exception handling; map zero/one/multi-arg
  methods as specified. Validate contract/signature/ID/schema on bind and reuse
  existing unambiguous service selection. Dispatch through the exact broker and
  stateful gate and CG05 validation-owned checkout without a second encoding or host guest construction. Add output
  to the existing atomic staging/publishing transaction. Centralize holder/argument
  names in PortalGeneratedNames and test nested names and cross-service collisions.
- [ ] **4. Preserve compatibility and edge semantics.** Keep synchronous bytes and
  IDs identical using golden round trips. Test Java/static access, overloaded and
  inherited generic methods, nullable values, count/length overflow, bad metadata,
  full consumption of generated responses, expired encoding, foreign descriptor,
  no-use-after-close and failure evidence. Keep legacy prepare's input-stability
  contract and original constructors/bridges intact. Describe unsupported new
  bindings for annotated InputStream/FD methods until their qualified path exists.
  Add local boxed Java access for PortalServiceId and selector-taking bind methods;
  javac must exercise explicit and ambiguous selection, not only the no-ID overload.
- [ ] **5. Verify.** Run `:portals:runtime:test --tests '*PortalSubmission*Test'`,
  `:portals:codegen:test --tests '*PortalOperationGeneratorTest'`, existing
  `PreparedPortalOperationTest`/`PreparedPortalOperationVisibilityTest`, and the
  generated packaged-consumer native round trip. Require caller mutation isolation,
  finite retained memory, real worker identity and physical cleanup on cancellation.
  Qualify the ownership limit explicitly: completed futures can retain decoded
  values after admission ends. Include a finite consume/release example and measure
  response expansion through P01; do not describe request reservations as a total
  host-memory bound or promise to preempt user constructors/getters.

## P04 — Offer optional coroutine result and end waits

**Files:**
- Create `enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/InvocationHandleCoroutines.kt`.
- Create `enforcer/src/test/kotlin/io/mazewall/execution/InvocationHandleCoroutinesTest.kt`.
- Modify `enforcer/api/enforcer.api` if required by the existing API gate.
- Modify `integrations/koog/src/main/kotlin/io/mazewall/koog/PortalToolAdapter.kt` only to reuse the proven helper.
- Extend the P02 Kotlin and coroutine-free Java consumer fixtures and the portal guide.

**Interfaces:** `awaitResult(): R` and observational `awaitExecutionEnd(): ExecutionEnd`
extensions from the design. Reuse enforcer's existing compileOnly coroutine
dependency and test dependency; introduce no new module or runtime requirement.

**Step-by-step execution:** [P04 work card](2026-11-03-portal-developer-experience-execution-guide.md#p04--coroutine-observation-and-cancellation).

- [ ] **1. Build a controllable fake InvocationHandle.** Give it separate result/end
  futures and a cancellation-request counter. Tests cancel a waiting coroutine and
  require `assertEquals(1, cancellationRequests.get())` while
  `assertFalse(handle.executionEnd().toCompletableFuture().isDone)`.
  Completing either future alone must not complete the other.
- [ ] **2. Bridge owned callbacks.** Use suspendCancellableCoroutine; unwrap only
  completion wrappers and preserve the original failure. Result-wait cancellation
  calls requestCancellation; end-wait cancellation merely stops observation. Retain
  prompt-cancellation semantics under completion races without double delivery or
  cancelling a detached CompletionStage copy as the only effect.
- [ ] **3. Reuse and qualify.** Keep Koog argument/result validation around the helper
  and retain its race tests. Run `./gradlew :enforcer:test --tests '*InvocationHandleCoroutinesTest'`
  and affected Koog tests through `./scripts/koog-build.sh -- :integrations:koog:test`.
  If the adapter changes, use `./scripts/koog-build.sh -- build` for the completion
  gate so the opt-in module is included. Compile/run a Java consumer with no coroutine library on
  its runtime classpath. Expected: ordinary portal APIs remain usable and Kotlin
  cancellation reaches physical ownership without claiming immediate worker death.

## P05 — Warm selected process groups through the existing runtime

**Files:**
- Modify `portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRuntime.kt`.
- Extend `PortalBrokerOwnership.kt` only if required to expose existing acquisition joining.
- Create `portals/runtime/src/test/kotlin/io/mazewall/portal/PortalWarmUpTest.kt`.
- Create `portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/PortalWarmUpIntegrationTest.kt`.
- Update `docs/guides/portal-service-isolation.md` and P02 consumers.

**Interfaces:** `warmUp(): List<PortalServiceHealth>` and
`warmUp(services: Collection<PortalServiceId>): List<PortalServiceHealth>`.
No-argument means all groups; snapshot/deduplicate explicit selectors (empty means
none). Collections support the existing value-class IDs without forbidden varargs.

**Step-by-step execution:** [P05 work card](2026-11-03-portal-developer-experience-execution-guide.md#p05--warm-up-through-existing-acquisition).

- [ ] **1. Test group resolution and concurrent ownership.** Two selected services
  in one group must acquire one broker; an unknown ID acquires none. With a held
  launcher, overlap warmUp, bind and close and assert no proxy/process is published
  after closing admission and all late acquisitions remain joinable.
- [ ] **2. Implement via registry and brokerFor.** Validate all selectors before
  acquisition; reuse required readiness and configured startup budget per group.
  Return selected local health only after successful readiness. Report group
  failure with original cause; retain prior successful groups for runtime close.
- [ ] **3. Prove constructor-once and launch failure behavior.** A generated fixture
  exposes PID and construction count: `assertEquals(1, count)` after two warm-ups
  and several calls. Check missing artifacts/unsupported required protection fail
  before publication. Verify actual ProcessHandle death after close.
- [ ] **4. Verify.** Run `./gradlew :portals:runtime:test --tests '*PortalWarmUpTest'`
  and `:portals:runtime:integrationTest --tests '*PortalWarmUpIntegrationTest'`.
  Update examples to warm once at application startup and distinguish ready from
  JIT-warmed or application-cache-warmed. Warm before any restrictive host process
  baseline, following existing spawn rules. Report total planned processes per group.
  State that a later host spawn restriction can still prevent stateless replacement;
  pre-warming does not grant future launch authority. An optional representative
  application check is an explicit side-effect-free method, not arbitrary code run
  automatically by warm-up.

## P06 — Amortize calls through bounded batch contracts

**Files:**
- Create `portals/runtime/src/main/kotlin/io/mazewall/portal/PortalBatches.kt`, `PortalBatchOptions.kt`, `PortalBatchCursor.kt`.
- Create `portals/runtime/src/test/kotlin/io/mazewall/portal/PortalBatchesTest.kt`.
- Extend P02/P03 fixtures with a declared `parseBatch(List<ByteArray>): List<Summary>` method.
- Extend P01 comparison modes and `docs/guides/portal-service-isolation.md`.

**Interfaces:** The PortalBatches.map/options/cursor signatures in the design.
The cursor owns handle references and bounded input traversal, not processes or
an executor. Each chunk invokes one ordinary generated batch service method.

**Step-by-step execution:** [P06 work card](2026-11-03-portal-developer-experience-execution-guide.md#p06--finite-batching-not-a-list-of-scalar-rpcs).

- [ ] **1. Test with a counting iterator and controllable prepared batch operation.**
  At batchSize=3/maxInFlight=2, a held first response permits at most six input
  elements pulled. Complete the second response first and assert the first next()
  still returns the first chunk. On close assert no more iterator reads and one
  cancellation request per unfinished handle. A short/extra response rejects.
  Deliver results while holding execution-end signals and verify the cursor cannot
  accumulate more than maxInFlight unconsumed or physically unfinished handles.
- [ ] **2. Implement a small synchronous cursor.** Fill only the finite window;
  start the traversal deadline at cursor creation and pass it to submitUntil,
  which also clamps each call to its configured maximum. Yield in input order;
  refill a slot only after its result is consumed and execution end observed.
  Surface retirement failures before dropping handles. Validate option arithmetic
  and encoded payload bounds.
  For example, document and compile:
  ```kotlin
  PortalBatches.map(batchOperation, inputs.iterator(), PortalBatchOptions()).use { batches ->
      while (batches.hasNext()) consume(batches.next())
  }
  ```
  Here `consume` is ordinary caller processing; it is outside worker protection.
- [ ] **3. Test failure and retirement.** Cover iterator exceptions, already-expired
  traversal, oversized chunk, late result, queued/active cancellation, retained
  cleanup failure and repeated close. Keep physical owners in the broker and
  handles in the cursor until end; preserve the primary failure with cleanup
  suppressed. Never replay a chunk after unknown completion.
- [ ] **4. Verify real batching and performance.** Run
  `./gradlew :portals:runtime:test --tests '*PortalBatchesTest'` plus a generated
  native batch fixture. Count worker method entries to prove one entry per chunk,
  not one RPC per item. Compare scalar/batched calls in P01 at equal work and caps.

## P07 — Qualify stateful bindings as actor-like workers

**Files:**
- Extend `portals/runtime/src/test/kotlin/io/mazewall/portal/PreparedPortalOperationTest.kt`, `PortalServiceRegistryTest.kt`, `PortalRuntimeTest.kt`.
- Create `portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/PortalStatefulQueueIntegrationTest.kt`.
- Extend `portals/worker/src/test/kotlin/io/mazewall/portal/worker/PortalWorkerServiceRegistryTest.kt`.
- Modify `portals/runtime/src/main/kotlin/io/mazewall/portal/PortalStatefulInvocationGate.kt` and `portals/runtime/src/main/kotlin/io/mazewall/portal/PortalOperationOwner.kt` only for a regression-demonstrated gap.
- Add a worker-local cached dictionary/session example to P02 consumers and the guide.

**Interfaces:** Existing STATEFUL_SINGLETON plus generated bindings. No new actor
runtime, dynamic session factory, mailbox implementation or remote object identity.

**Step-by-step execution:** [P07 work card](2026-11-03-portal-developer-experience-execution-guide.md#p07--state-locality-and-loss-boundaries).

- [ ] **1. Prove existing serial behavior.** Warm a counter service, submit three
  generated calls without awaiting, and assert results `listOf(1, 2, 3)` for that
  submitter, one PID and one construction. Two bindings/services in the same group
  share the stateful queue and collaborator. Different groups have distinct PIDs.
- [ ] **2. Test state-loss boundaries.** A completed business exception leaves the
  counter usable. Active timeout/crash/cancellation terminally loses it; queued
  calls get no guest entry and no replacement instance. Cancelling an untouched
  queued call preserves the active worker/state. Synchronous get() overlap still
  returns NOT_STARTED, and mixed synchronous/prepared usage documents that rule.
- [ ] **3. Demonstrate locality.** Load a worker-local dictionary once after
  containment and perform parse+validate+summarize in one method. Return a copied
  summary, not object handles. Show restart requires explicit new application
  runtime/state construction. Reject conflicting profile/artifact/mode groups.
  Add a two-worker stateless fixture proving separate persistent per-worker graphs;
  do not rely on round-robin scheduling or treat STATELESS as per-call reset. A
  bounded cache may persist, while request/tenant authorization must remain explicit
  and request-local. Record duplicated cache memory in P01.
- [ ] **4. Verify.** Run affected runtime/worker unit tests and
  `./gradlew :portals:runtime:integrationTest --tests '*PortalStatefulQueueIntegrationTest'`.
  Update P01's state-locality workload; report initialization and calls separately.

## P08 — Expose safe file grants and own asynchronous inputs

**Files:**
- Modify `portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRuntime.kt`, `Capability.kt`, `PortalInvocationRetirement.kt`, `PortalGrantRoots.kt` and P03 request encoding (including boxed Java grant-root selectors).
- Extend `portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalOperationGenerator.kt` for direct inbound ReadFd.
- Create `portals/runtime/src/test/kotlin/io/mazewall/portal/PortalCapabilitySubmissionTest.kt`.
- Extend `portals/runtime/src/test/kotlin/io/mazewall/portal/OpenGrantedReadTest.kt`, `PortalGrantRootsTest.kt`.
- Create `portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/PortalFileInputIntegrationTest.kt`.
- Read `platform/src/main/kotlin/io/mazewall/ffi/internal/RealNativeEngine.kt` and `platform/src/main/kotlin/io/mazewall/platform/fd/FileDescriptor.kt`; existing fcntl acquisition receipts should suffice without a new syscall.

**Interfaces:** `runtime.openReadOnly(serviceId, rootId, relative): Capability.ReadFd`;
P03 descriptors accept direct inbound capability parameters after this task.

**Step-by-step execution:** [P08 work card](2026-11-03-portal-developer-experience-execution-guide.md#p08--acquired-file-capabilities-through-submission).

- [ ] **1. Test close-after-submit with a held worker.** Open a real granted file,
  submit its capability, close the caller owner, then release dispatch and assert
  the worker reads the expected bytes. Mock acquisition failure separately and
  assert no invented ownership token or guest entry. Test queued cancellation
  closes duplicates while leaving the caller's original usable.
- [ ] **2. Reuse grant validation and native acquisition.** Resolve service/group,
  delegate to the existing broker openat2 path, borrow each input under the absolute
  deadline, call F_DUPFD_CLOEXEC and consume its real getFdOrThrow acquisition
  receipt. Register each duplicate before further allocation. Transfer invocation
  ownership atomically; unwind partially acquired lists with fatal-first cleanup.
- [ ] **3. Join ownership to physical end.** On encode/send failure, cancellation,
  worker crash and runtime close, retain pre-native close failures for retry;
  do not double-close completed native results. Preserve broker-to-worker-only
  transfers and direct-parameter validation. Document shared offset/live file
  contents; a descriptor duplicate is not an immutable content snapshot. Extend
  retirement registration outside the current dispatch-only thread-local hook:
  partial submit failure and queued cancellation must also retain duplicates, and
  cannot report execution end until those owners retire. Qualify Java grant calls.
- [ ] **4. Verify.** Run runtime capability/grant tests and
  `:portals:runtime:integrationTest --tests '*PortalFileInputIntegrationTest'`.
  Keep symlink/traversal/nonregular/unknown-root rejection controls. Compare large
  file inputs with byte-copy inputs in P01 and update ownership examples.

## P09 — Add an opt-in finite replacement budget

**Files:**
- Create `portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRestartBudget.kt`, `PortalReplacementMachine.kt`.
- Modify `ProcessBroker.kt`, `ProcessBrokerLifecycle.kt`, `PortalRuntimeConfig.kt` in that package.
- Create `portals/runtime/src/test/kotlin/io/mazewall/portal/PortalReplacementMachineTest.kt`.
- Extend `portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/ProcessBrokerIntegrationTest.kt`.

**Interfaces:** Immutable `PortalRestartBudget(maxRestarts: Int, window: Duration)`
selected explicitly in new compatible config overloads; absent means existing
behavior. Internal pure `evaluate(state, event)` uses supplied monotonic time and
returns admit/deny effects, never performs native work.

**Step-by-step execution:** [P09 work card](2026-11-03-portal-developer-experience-execution-guide.md#p09--replacement-budgets-without-application-retries).

- [ ] **1. Test the pure clocked budget.** Under maxRestarts=2/window=60 seconds,
  two attempts at times 0 and 1 admit, time 2 denies, and a new window removes
  expired entries. Cover zero budget, negative values, backwards supplied clock
  rejection, exact boundary and simultaneous requests serialized by the owner.
  Window expiry never revives an already-Failed group; exercise expired-entry
  removal on a still-running state, separately from terminal exhaustion.
- [ ] **2. Apply decisions to existing maintenance.** Retire the dead slot first,
  then consume one budget entry before spawn and required readiness. One failed
  replacement consumes its attempt; no retry loop. On exhaustion stop admission
  through the existing Failed lifecycle; retain active/idle/provisional owners for
  deterministic close. Do not alter the original invocation result.
- [ ] **3. Test lifecycle and compatibility.** Crash workers repeatedly; assert the
  spawn counter stops at the configured budget, no replay occurs, stateful workers
  never replenish, and old config retains existing semantics. Race replacement
  readiness with warm-up/close; assert no late publication or leaked child.
  Preserve worker-exit evidence without calling every exit an OOM. Use cgroup event
  evidence when available; missing samples remain unknown. Qualify a denied later
  spawn after successful initial warm-up and retain the original call failure.
- [ ] **4. Verify.** Run the pure machine tests and selected broker fresh-child
  integration cases. Update the guide with an opt-in 3-per-minute example, failure
  diagnostics and the existing terminal-state recovery procedure.

## P10 — Optimize measured encoding and IPC costs

**Files:**
- Modify `portals/runtime/src/main/kotlin/io/mazewall/portal/PortalCodec.kt`, `PortalRequestWriter.kt`, `PortalPayload.kt`, `PortalIo.kt`, `PortalChannel.kt` only where profiling points.
- Modify `portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalStubGenerator.kt` to reuse bounded single-pass encoding where beneficial.
- Extend `portals/runtime/src/test/kotlin/io/mazewall/portal/PortalCodecTest.kt`, `PortalFrameTest.kt`, `PortalConfiguredTransportBoundsTest.kt`, `PortalOwnershipDeadlineTest.kt` and affected native integration tests.
- Update the P01 comparison report and the process-portal design with actual changed mechanics.

**Interfaces:** Identical public copy semantics, frame bytes/method IDs and RPC
deadline/failure classifications. An internal ownership-transfer helper must not
expose mutable storage or cross thread-confined arena lifetimes.

**Step-by-step execution:** [P10 work card](2026-11-03-portal-developer-experience-execution-guide.md#p10--one-measured-optimization-at-a-time).

- [ ] **1. Profile the new API at the baseline matrix.** Separate encoding,
  allocation/copy, host dispatch, native syscalls and transport+worker aggregate.
  Select the first demonstrated dominant cost. Do not remove a correctness check
  because it looks expensive or infer guest time from the public aggregate clock.
- [ ] **2. Add a differential regression for that cost center.** Codec changes must
  produce `assertContentEquals(oldWireBytes, newWireBytes)` across nested values,
  nullability, sizes and malformed payloads. I/O changes must force short writes,
  EAGAIN, EINTR, partial EOF, timeout-before-header, timeout-after-progress and
  cancellation, asserting the same outcome and exact owner retirement.
- [ ] **3. Make one focused optimization.** Reuse CG06's bounded writer consumed by P03;
  then consider fewer internal copies or coalesced writes. Attempt I/O before
  poll only on nonblocking sockets, checking close/interruption/deadline before
  each attempt and polling on EAGAIN. Keep received-size validation before body
  allocation. Confined native arenas remain confined; no shared mutable buffer pool.
- [ ] **4. Repeat only the affected evidence.** Run focused regressions and the
  same P01 workload/fork/concurrency matrix. Retain a change only with reproducible
  benefit or a clearly documented nonperformance correctness reason; investigate
  tail/memory regressions. Each additional cost center is a separately reviewed
  commit within this issue, not a single speculative rewrite.

## P11 — Decide whether immutable bulk input is worth adding

**Files:**
- Extend the P01 comparison's large-input cases and README.
- Create `docs/internals/research/portal-bulk-input-transport.md` with evidence and a go/no-go decision.
- A production follow-up, if justified, updates the portal design and scopes native
  ownership tests before changing `portals/runtime/src/main/kotlin/io/mazewall/portal/PortalChannel.kt`
  or `portals/runtime/src/main/kotlin/io/mazewall/portal/Capability.kt`.

**Interfaces:** This issue produces measurements and an explicit decision. It
introduces no production API, shared heap or returned worker descriptor by default.

**Step-by-step execution:** [P11 work card](2026-11-03-portal-developer-experience-execution-guide.md#p11--bulk-input-go-or-no-go).

- [ ] **1. Measure both existing input paths.** Compare copied bytes and P08 grants
  at 64 KiB, 1 MiB and the configured message limit, plus files larger than that
  limit through the FD route. Include one-use and reused-input workloads, parsing
  cost, RSS, allocation and cleanup. Identify whether copying actually dominates.
- [ ] **2. Apply a decision rule.** If granted files already meet the application
  budget or copying is not dominant, record no-go and close with that evidence.
  Otherwise use an explicitly disposable broker-created immutable-input prototype
  and compare the same workloads. Do not treat fd duplication as immutable data.
- [ ] **3. Test any prototype's distinct hazards.** Concurrent mutation/truncation,
  stale/mismatched backing identity, oversized mapping, cancellation, child crash
  and cleanup failure must reject or remain correctly owned. No worker-to-host FD
  path, path reopening or unbounded mapping is permitted. Keep code clearly
  experimental until a production task is separately scoped through the backlog.
- [ ] **4. Publish the decision.** Require reproducible p95/throughput improvement
  for at least two relevant large-input sizes and no small-call/memory regression
  outside observed variation, plus a complete ownership design. A go decision
  creates a narrowly scoped production issue through adkw; a no-go is equally
  complete. Neither outcome invents an unconditional zero-copy performance claim.

## P12 — Report and validate aggregate capacity before startup

**Files:**
- Create `portals/runtime/src/main/kotlin/io/mazewall/portal/PortalCapacityPlan.kt`, `PortalCapacityBudget.kt`.
- Modify `portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRuntimeConfig.kt`, `PortalRuntime.kt`; reuse `PortalServiceRegistry.kt` validation and existing `ProcessBroker.kt` pool sizing.
- Create `portals/runtime/src/test/kotlin/io/mazewall/portal/PortalCapacityPlanTest.kt`.
- Extend `portals/runtime/src/test/kotlin/io/mazewall/portal/PortalRuntimeTest.kt`, `PortalWorkerProfileTest.kt` and Java/Kotlin cases under `tests/consumers/portal-plugin` using existing explicit registration/configuration.
- Update `docs/guides/portal-service-isolation.md` with per-runtime scope and configured-versus-observed memory.

**Interfaces:** `PortalRuntimeConfig.capacityPlan(): PortalCapacityPlan` and additive
Java-static `PortalRuntime.open(config: PortalRuntimeConfig, budget: PortalCapacityBudget)`.
`PortalCapacityBudget(maxWorkerProcesses: Int, maxConfiguredWorkerMemoryBytes: Long? = null)`;
immutable plan fields and closed `PortalWorkerMemoryTotal.Limited/Unbounded` follow
design section 11. Keep report construction restricted and copy owned collections.

**Step-by-step execution:** [P12 work card](2026-11-03-portal-developer-experience-execution-guide.md#p12--pure-aggregate-capacity-and-pre-launch-rejection)
includes complete metadata-only fixture builders, RED/GREEN test structure, checked
arithmetic steps, exact-budget/unknown/overflow decision table, Java constructor
interop and explicit-registration consumer sequencing independent of P02.

- [ ] **1. Prove group arithmetic and unknowns.** Build a fixture with two services
  in stateless group A, one service in singleton B, workers=2 and 128 MiB per-worker
  cgroup limits. Supply a non-null fixture cgroup-root path so missing-root validation
  cannot mask the budget rejection; this host test does not prove real delegation.
  It must count three workers, not five, and 384 MiB configured memory:
  ```kotlin
  val plan = config.capacityPlan()
  assertEquals(3L, plan.totalWorkerProcesses)
  assertEquals(384L * 1024 * 1024,
      assertIs<PortalWorkerMemoryTotal.Limited>(plan.configuredWorkerMemory).bytes)
  assertFailsWith<IllegalArgumentException> {
      PortalRuntime.open(config, PortalCapacityBudget(maxWorkerProcesses = 2))
  }
  ```
  Cover empty config, exact limits, one-over, missing profiles, conflicting groups,
  checked multiplication/addition overflow and mutation of original config inputs.
  An uncapped group produces Unbounded with its group identity, never zero bytes.
- [ ] **2. Derive one pure report from validated config.** Reuse group/profile
  validation, count all configured lazy groups, and sum configured memory limits
  with checked `Long` arithmetic. No artifact/guest initialization, `/proc` sampling,
  cgroup creation or launching occurs while producing the report.
- [ ] **3. Add the budgeted open overload.** Validate positive budgets, reject
  excessive process counts/ceilings and reject Unbounded when a finite byte budget
  is requested, before broker acquisition. Tests require zero launch/preparation
  events on rejection. Existing `open(config)` and default bridges remain compatible;
  both paths use the same runtime/ownership machinery after validation. A budget
  never rewrites group trust, profiles, capacity or operator launch arguments.
- [ ] **4. Qualify reporting and deployment limits.** Compile Java/Kotlin callers
  using the additive API and existing explicit registration/configuration, independent
  of P02's conventions or `loadBaseline()`. Demonstrate a process-only budget with
  an explicitly registered `PortalWorkerProfile()` and a finite configured-memory
  budget with paired profiles/delegated cgroups. A capacity report is not readiness,
  live RSS, a global multi-runtime reservation or an OS limit.
  Keep health sampling separate and leave host/sibling/swap headroom explicit.
- [ ] **5. Verify.** Run `:portals:runtime:test --tests '*PortalCapacityPlanTest'`,
  affected runtime/profile tests and those explicit-registration Java/Kotlin consumer
  compilations. P02 later reuses these cases; it is not a P12 prerequisite.
  At completion use the standard full build and distinct published-consumer gate.
  P13 qualifies actual child counts and required cgroup behavior; no fake filesystem
  proves delegation.

## P13 — Qualify safe process-boundary use in real applications

**Files:**
- Extend `tests/consumers/portal-plugin/build.gradle`, `README.md` and its `src` fixtures.
- Create `portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/PortalUsageSafeguardsIntegrationTest.kt` using the existing generated-worker fixture conventions.
- Extend `portals/codegen/src/test/kotlin/io/mazewall/portal/codegen/PortalCodegenFunctionalTest.kt` only for unsupported contract/diagnostic cases not already covered by CG issues.
- Update `docs/guides/portal-service-isolation.md` and link P01's qualified memory report.

**Interfaces:** Existing generated contracts and invocation evidence, P03 typed
submission, P05 warm-up, P06 bounded traversal, P08 grants and P12 budgeted open.
This task delivers examples/qualification; it adds no context propagation or lifecycle
framework. Any regression-demonstrated runtime correction stays with its existing owner.

**Step-by-step execution:** [P13 work card](2026-11-03-portal-developer-experience-execution-guide.md#p13--real-consumer-usage-and-abrupt-host-death-qualification)
defines controller/host/worker roles, test-local barrier events, each crash scenario,
acquired identity and rescue ownership, bounded observation, failure triage and the
evidence required before a cleanup claim can be made.

- [ ] **1. Make caller context and copying observable.** A fixture sets distinct
  host static/thread-local markers and sends an explicit correlation field. Assert
  the worker has a different PID and independently initialized markers while the
  explicit field round-trips. Mutation-after-submit remains isolated. Compile-time
  callback/live-resource rejection must precede launch. Context fields are data,
  not automatically propagated authorization or host transaction identity.
- [ ] **2. Demonstrate application lifetime and pooled state.** Open once, bind and
  warm repeatedly, execute many calls and assert process/constructor counts remain
  at configured capacity. Use held concurrent calls to exercise two stateless
  workers without assuming scheduling order; persistent per-worker state is not
  shared or reset each call. Keep request data local and cache size finite. Close
  joins actual children; repeated bind does not create independent owned sessions.
- [ ] **3. Exercise failure and shutdown examples.** Use existing fixture gates to
  distinguish queued NOT_STARTED, completed business exception and effect-before-
  response-loss UNKNOWN_COMPLETION. Assert no automatic replay, singleton terminal
  loss and distinct result/physical-end signals. Show finite cancellation/close and
  cleanup retry rather than discarding pending owners. Reuse P09's denied replacement
  and P08's closed/shared-offset file evidence instead of inventing duplicate owners.
- [ ] **4. Qualify abrupt host death separately from close.** A surviving test
  controller starts the application host as a subprocess and records each acquired
  worker's PID/start identity before killing that host with SIGKILL. Cover startup
  before readiness, idle workers, a held/non-returning guest call, a returned call
  leaving a non-daemon guest thread, and a worker stalled in a shutdown hook. Keep
  controller-owned deadlines and rescue cleanup for every acquired process. Require
  observed worker death within the declared bound; RPC EOF, main return or retained containment
  receipts are not physical-end evidence. Rescue kills fail qualification and never
  count as runtime cleanup. Name and separately test any deployment supervisor relied
  on; do not attribute its guarantee to the library. If a case fails, retain the
  reproduction and open a scoped corrective blocker against the existing launch/
  broker/worker owner before claiming orphan-free cleanup or closing that lane.
- [ ] **5. Exercise bounded usage and startup diagnosis.** Demonstrate P06 lazy
  consumption, finite request admission, dropping completed result references and
  P12 rejection before launch. Missing bundle/profile/delegation or unavailable
  containment retains actionable service/group/stage/cause. Run a representative
  allowed computation and intentional denied filesystem/network operation in real
  workers; preserve the denial rather than widening the policy. OOM qualification
  requires a delegated, finite child cgroup and parent test budget; otherwise mark
  that lane unqualified. An unexplained crash is not labelled an OOM.
- [ ] **6. Publish concise usage guidance and verify.** Cover every design section
  10 surprise, indicate what is compiler/runtime-enforced versus application-owned,
  and include the measured memory envelope with exact worker/workload identity.
  Run `:portals:runtime:integrationTest --tests '*PortalUsageSafeguardsIntegrationTest'`,
  relevant codegen cases and strict Java/Kotlin packaged-consumer gates, then the
  complete build. Actual kernel/delegation controls are prerequisites for closure,
  not replaced by passing host-only fixture tests.

## Final qualification and scope coverage

- [ ] Re-run P01 against the final packaged worker, including the existing slimmer
  packaging issue if delivered. Compare old synchronous and new generated paths,
  warm-up, batching, state locality, capabilities and replacement under equal work.
- [ ] Through the existing adoption issue, retain one independent Java application
  and one Kotlin application using published artifacts, documented source layouts,
  real worker identity, cancellation/overload and physical shutdown. Record integration
  effort and selected application latency/throughput/memory budgets.
- [ ] Require P12 aggregate budget and P13 usage qualification, plus P01 host-and-
  worker memory evidence. Include idle/peak/retained-result costs and parent deployment
  headroom; no thread-equivalent or HTTP-equivalent memory claim without measurements.
- [ ] Retain P13's abrupt-host-death results and exact cleanup owner. Successful
  graceful close alone does not qualify orphan-free crash recovery.
- [ ] Update the public guide with complete examples and precise startup/profile,
  unsupported-type and worker-failure diagnostics. Correct stale descriptions of
  embedded bundle resources and already-supported overloads/collections as the
  relevant tasks qualify them; do not broaden supported DTO shapes by documentation.
- [ ] Run the required full build for the final revision and all distinct selected
  published-consumer gates, link evidence to the owning issues, and resolve only
  completed items. Remote publication and production operator authority stay in
  their existing work items.

| Discussed idea | Planned owner |
|---|---|
| Thread-like typed submit/await and generated async bindings | P03, P04 |
| Smaller registration, packaging and plain-language examples | P02; existing packaging issue |
| Warm pools and initialization amortization | P05, P07 |
| Explicit shared trust/failure groups | P02, P05, P07 |
| Ordinary copied values and predictable mutation semantics | P03 |
| Batching and bounded pressure | P06 |
| Actor-style retained state / keeping computations local | P07 |
| Convenient large-file capabilities | P08 |
| Supervision with bounded replacement and no replay | P09 |
| Fair API comparison, profiling and measured optimization | P01, P10; existing adoption issue |
| Browser-inspired bulk buffers | P11 conditional evaluation |
| Useful diagnostics and packaged developer onboarding | Every delivering task; P02 and final qualification |
| Aggregate worker/process/configured-memory safeguards | P12; existing cgroup/launch owners |
| Copied values, explicit context, pooled state and unknown completion | P13 qualification; CG and P03–P09 implementations |
| Whole-deployment memory, decoded values and retained results | P01 measurement, P03/P06 bounds, P12/P13 guidance; existing packaging issue |

## Planning verification

Planning creates this document, the proposed design, thirteen scoped feature issues and
index links only. Documentation link/reference checks, `git diff --check` and
`./scripts/adkw check-backlog` are the required checks for this change. All
implementation checkboxes remain open; no benchmark result or build pass for the
proposed application changes is claimed by writing the plan.

The follow-up codegen review adds CG01–CG07 and the completion prerequisites above.
Its focused tests and defect probes validate review evidence, not implementation of
this plan or closure of those corrective issues.

The usage/memory revision adds P12/P13, strengthens existing task acceptance and
corrects the convenience profile. It is documentation/planning only; no new capacity
API, measured memory result or usage-regression pass is claimed before implementation.

The junior-execution revision adds the linked runbook and per-issue work-card links,
with detailed test mechanics and completion evidence. It changes no application
behavior, dependencies or issue status, and does not authorize native/lifecycle
mechanisms not already scoped in the plan.
