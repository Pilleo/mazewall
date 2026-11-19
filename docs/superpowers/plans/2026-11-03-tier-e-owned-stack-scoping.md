---
title: "Pre-call stack scoping for owned synchronous file opens"
document_type: execution_plan
status: "proposed"
base_revision: b2c9b335720fd0491a8d86917a3b0593b818a4ec
---

# Plan B — reuse Tier E capture in the existing supervisor

**Goal:** Deliver an opt-in, fail-closed stack restriction for read-only NIO file opens
on owned platform workers. The production path depends on `:enforcer`, `:platform`
and the existing native agent artifact, not on the `:profiler` JVM runtime or a BPF
collector. Preserve `StacktraceScopingPolicy` and existing supervisor ownership.

The [roadmap](2026-11-03-tier-e-improvement-roadmap.md) defines the proposed support
envelope, authority model, snapshot identity, resource restrictions and conditional
extensions. Those constraints are requirements for every task below. Read module
AGENTS files, the FFM safety checklist, containment design and notification-liveness
contract before implementation. This is not authorization to implement broader
syscall coverage or introduce a second enforcer.

## File and caller map

| Seam | Existing files / consumers |
|---|---|
| Pre-call capture | `profiler-native/src/agent.cpp` (`InvocationScope`, `capture_stack`, native proxies); `include/binding_manifest.hpp`, `invocation_registry.hpp`, `symbolization_generation.hpp` |
| Native build/test | `profiler-native/CMakeLists.txt`, `profiler-native/tests/`; existing profiler native build packaging |
| Typed bridge | `platform/src/main/kotlin/io/mazewall/platform/native/NativeEngine.kt`, `platform/src/main/kotlin/io/mazewall/ffi/internal/RealNativeEngine.kt`; native-engine test doubles |
| Public preparation | `enforcer/src/main/kotlin/io/mazewall/enforcer/api/StacktraceScopingPolicy.kt`, `api/execution/ManagedRuntime.kt`, `PreparedOperationResources.kt` |
| Worker/task ownership | `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/OwnedDomain.kt`, `RuntimeWorkerContext.kt`, `PreparedResourceSet.kt` |
| Static policy composition | `enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt` (`augmentForScoping`), `internal/seccomp/BpfFilter.kt` |
| Validation | `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorInstaller.kt` (`JVMValidationListener`), `JvmStackInspector.kt`, `SupervisorValidationDecoder.kt`, `JvmVerdictExchange.kt`, `SupervisorRouteContext.kt` |
| Private wire encoder | `enforcer/src/main/kotlin/io/mazewall/ffi/supervisor/JvmVerdictRequestEncoder.kt` and connected layout/decoder tests |
| Broker effects | Same supervisor directory: `SupervisedOpen.kt`, `SupervisorOpenPlan.kt`, `OpenRouteExecutor.kt`, `PoolOpenRouteExecutor.kt`, `TraceeAuthorityProtocol.kt`, `SupervisorRoutePreparation.kt`, `SupervisorFastPath.kt` |
| Compatibility/gates | Module `api/` dumps, existing architecture tests, supervisor/owned-runtime tests, `OwnedScopingNativeTest.kt`, `PermanentSupervisorRegistrationIntegrationTest.kt`, `SupervisorProxyIntegrationTest.kt` |

Run `./scripts/adkw blast-radius <SymbolName>` before changing these cross-module
or core symbols, then inspect exact callers. New file names below are proposed;
declare them in each issue's scope. Prefer small private/internal types. Any new
cross-module protocol surface uses the repository's internal-API convention.

## B1. Freeze the supported contract and adversarial matrix

**Files:** The relevant sections of
`docs/internals/designs/enforcer/containment-design.md`,
`docs/internals/designs/profiler/tier-e-design.md`,
`docs/internals/designs/core/notification-liveness-contract.md`;
the [live-context issue](../../internals/backlog/issue-20261103-085251-359-provide-live-pre-call-stack-context-before-user-no.md).

- [ ] Record the initial JDK/architecture/binding/syscall/flag matrix and the
  absolute read-only file scope. Add explicit unsupported results for other
  deployment envelopes; capability readiness must precede permanent installation.
- [ ] Specify the versioned live snapshot ABI and sealed read outcomes:
  available, absent, incomplete, busy, stale and unsupported. Define maximum frames,
  worker slots and invocation depth; exhaustion fails closed, never wraps IDs.
- [ ] Specify how process/TID identity, registered worker slot, owned-task lease,
  code generation and supervisor notification identity join. A numeric TID or
  profiler context ID alone is insufficient.
- [ ] Correct historical documentation claims that executable-memory restrictions
  make stack identity unforgeable. State the trusted-JVM and shared-FD boundary.
  Mark the new path proposed until B7 passes.
- [ ] Freeze regression cases from the matrix below before implementation.
  Record the source-pinned seccomp-before-tracepoint ordering prerequisite.

**Exit:** Reviewable identity/authority contract with no ring/dictionary dependency
and no ambiguity about the first supported operation. This task is documentation;
use reference/backlog checks, not implementation-mirroring tests.

## B2. Publish bounded pre-call snapshots in the native agent

**Modify:** `agent.cpp`, the relevant existing invocation/generation headers and
`CMakeLists.txt`. **New:** `include/live_stack_context.hpp`,
`tests/live_stack_context_test.cpp` and a small Java fixture for the real agent.

- [ ] Add failing native tests for clear → capture → publish → invalidate,
  slot contention, truncation, capture failure, ID/slot exhaustion, stale worker
  and task generation, exception exit, shutdown and callback/nesting suppression.
- [ ] Factor the existing bounded `GetStackTrace` acquisition into one result
  consumable by diagnostic intern/cache logic and live snapshots. Capture once
  when both consumers need the same boundary. Keep diagnostic wire formats and
  canonical-cache semantics intact.
- [ ] Add startup-owned worker slots and enrollment of prepared code identities.
  Pin required classes; map captured methods to enrolled tokens. Reuse existing
  symbolization-generation invalidation, but live rules remain invalid after a
  generation change until explicitly reprepared. Unknown identity never becomes
  a trusted frame by matching display text.
- [ ] Publish before calling the original native function. Clear live context
  before agent work/capture, on scope exit and before callbacks. First-release
  nested/callback invocations are unavailable rather than inheriting/restoring
  parent authority. Preserve pending JNI/application exceptions.
- [ ] Separate live-capture requirements from diagnostic profile selection:
  diagnostic exclusion, ring overflow or collector absence cannot disable the
  mandatory open-boundary capture. Live-only startup needs no BPF/USDT attachment,
  dictionary file or terminal publisher. Unsupported JDK binding admission fails
  preparation instead of appearing ready with no capture.
- [ ] Export a small versioned bounded copy interface and registration lifecycle.
  A reader uses a nonblocking slot acquisition and copies owned values; no JVMTI,
  JNI lookup, allocation, file I/O or blocking lock on that read path. Registration
  and class pinning happen at trusted setup, outside the notification deadline.
- [ ] Prove that a notification can inspect a snapshot while its writer is stopped
  inside the original native call; verify no writer lock is held across that call.
  A mismatched/corrupt ABI request rejects without reading arbitrary addresses.

**Native gate (JDK/JNI development headers required):**

```bash
cmake -S profiler-native -B /tmp/opencode/tier-e-live-native -DCMAKE_BUILD_TYPE=Debug
cmake --build /tmp/opencode/tier-e-live-native
ctest --test-dir /tmp/opencode/tier-e-live-native --output-on-failure
```

Use the configured native/container build when the host cannot provide the
qualified JDK. Confirm the new tests are registered and executed. Include existing
binding, invocation, redefinition, generation and marker compatibility cases.

## B3. Add the typed platform bridge and ownership tests

**Modify:** `NativeEngine.kt`, `RealNativeEngine.kt`, existing native-engine test
fixtures and architecture/API checks. **New:** a minimal
`platform/native/NativeStackContexts.kt` contract and `ffi/internal` adapter/tests.

- [ ] Define typed immutable snapshot/read outcomes without exposing raw addresses,
  borrowed segments or JVMTI handles to `:enforcer`. Reuse PID/TID and lifecycle
  types. Register worker/task leases and retire them through the same owner.
- [ ] Expose availability through a source/ABI-compatible native-engine capability
  with unsupported as the default for engines without the agent. Do not add an
  abstract requirement that breaks external engine implementations. Verify the
  selected Kotlin/JVM interface/default-method strategy with the existing API and
  consumer checks rather than assuming source compatibility implies binary safety.
- [ ] Confine all FFM layouts, lookup and downcalls to `io.mazewall.ffi`; assert
  ABI version, field widths, counts, alignment and result bounds. Copy values
  before the confined arena closes and capture errno immediately where applicable.
- [ ] Load/resolve the already-started agent's versioned exports once at bootstrap.
  Missing agent, incompatible ABI/build, unsupported runtime or missing required
  binding is a typed readiness failure. A late library load is not equivalent to
  JVMTI agent initialization.
- [ ] Test malformed lengths/statuses, mismatched epochs, busy/stale responses,
  physical-retirement ordering and cleanup retry with a mock native implementation.
  Add a real-agent JVM smoke showing live snapshot availability with no collector.
- [ ] Verify module dependencies: `:enforcer` has no production dependency on
  profiler classes/tooling, and the existing agent binary can be supplied as an
  explicit startup artifact without installing the profiler distribution. Reuse
  its CMake output; avoid a new Gradle module solely to move files.

**Gate:** `./gradlew :platform:test`, native/JVM bridge smoke, architecture and API
compatibility checks. B2–B3 constitute the foundation issue; they grant no new
application access until B4–B7 complete.

## B4. Establish broker-side filesystem authority

**Modify:** `PreparedResourceSet.kt`, `PreparedOperationResources.kt`,
`SupervisedOpen.kt`, `SupervisorOpenPlan.kt`, helper setup/protocol and open-route
executors. Read the helper lifecycle before selecting exact additional files.
**New only if needed:** a small immutable prepared read-capability plan.

- [ ] First reproduce a stack-allowed request outside the base filesystem grants
  and make the new mode reject it. Add static-deny and helper-more-privileged
  cases: callback approval cannot widen either boundary.
- [ ] Bind genuine pinned read roots and runtime-support resources to a resource
  generation. Transfer owned descriptors with existing SCM_RIGHTS/FD ownership
  machinery. Reject mismatched domain, generation, credentials or namespace.
- [ ] Prepare the helper's corresponding confinement before application open
  effects; Landlock precedes helper seccomp. Scope restricted helper capacity to
  the domain and retain it until reaped. Verify supervisor/bootstrap provisioning
  works with the process-wide no-exec baseline; use the existing provisioned
  sidecar/pool rather than attempting a forbidden post-baseline process spawn.
- [ ] Use a copied, bounded absolute path, match it to a pinned capability and
  constrain `openat2` beneath that FD with no symlinks/magic links/mount crossings.
  Explicitly reject mutation/creation/unknown flags before any open. First acquire
  an internal `O_PATH` owner and check that it is regular, then perform a qualified
  descriptor-based reopen of that exact pinned object under helper confinement.
  Verify object identity before injection; never reopen the original tracee path.
  Do not open devices/FIFOs for reading before discovering their type. Preserve
  requested close-on-exec semantics and deny unsupported semantics. The trusted
  helper-owned descriptor reopen is separate from tracee-supplied magic links.
- [ ] Apply the same object-bound checks to explicit runtime-support reads.
  Eliminate suffix/realpath fast-path bypasses for the new mode; the application's
  root and runtime-support roots must not be silently interchangeable.
- [ ] Test symlink and rename races, `..`, renamed pinned roots, bind/mount escapes,
  wrong dirfd, nonregular files, hard-link alias semantics, policy-generation
  mismatch and late notification cancellation. Define directory grants as subtree
  access, including existing hard links; deployments requiring stronger alias
  exclusion need exact-object capabilities or must reject that setup.
- [ ] Demonstrate inherited LSM/credential equivalence in the qualified deployment.
  Unknown extra worker-only restrictions make this mode unsupported; never claim
  to reconstruct arbitrary Landlock state from paths. If this gate cannot be met,
  stop enforcement promotion and retain the diagnostic/live-capture deliverables.

**Gate:** Pure resource-plan tests, helper/FD fault tests and fresh-JVM kernel
positive/negative controls. Independently verify no forbidden object was opened or
injected; an IOException alone does not identify which policy denied it.

## B5. Carry a frozen live-scoping plan through owned preparation

**Modify:** `ManagedRuntime.kt`, `OwnedDomain.kt`, `RuntimeWorkerContext.kt`,
`SupervisorInstaller.kt`, `ContainedExecutors.kt`, owned-runtime tests and API
dumps. **Proposed additive API:** `PreparedStackScoping` and
`ManagedRuntime.prepare(policy, limits, scoping: PreparedStackScoping)`.

- [ ] Build the prepared object at trusted bootstrap from actual code identities,
  ordered call corridors, the base resource policy and optional existing
  `StacktraceScopingPolicy` handlers. It owns enrollment/resources until the
  prepared domain claims them; failed preparation retains/retires that owner.
  Reuse the existing prepared-resource ownership model.
- [ ] Introduce one internal frozen scoping value carrying mode, copied handlers,
  capture requirement, code generation and resource owner. Current preparation
  copies only `handlers` into an anonymous policy; do not hide new authority in a
  subtype that that copy discards. Callback closures remain application code and
  are not made immutable by copying the map.
- [ ] Preserve the current interface/signatures. Legacy mode remains explicitly
  legacy/experimental; the new live mode never falls back to `Thread.getStackTrace`
  or empty-stack approval when capture is unavailable.
- [ ] Register a worker before installation, bind a fresh task lease before each
  task body, and clear it in physical completion/retirement. Preserve permanent
  installation identity: a changed code/resource policy cannot reuse an already
  supervised worker by swapping a handler map.
- [ ] Check capability/binding/resource readiness before the irreversible install.
  Reject virtual-thread installation and unsupported runtime profiles. Keep Tier 1,
  Landlock-before-seccomp, failure receipts and owned-worker drainage intact.
- [ ] Compose the new notification selection only with base-permitted operations.
  `augmentForScoping` currently assigns `ACT_NOTIFY` to selected entries; retain
  the original static permission and reject/keep denied conflicts. Preserve
  argument-sensitive JVM-floor checks and the linear BPF design.
- [ ] Test caller mutation after prepare, legacy API compatibility, lost subtype
  metadata, denied base action, task reuse, policy replacement, failed preparation,
  required-readiness refusal and no execution after invalidation.

**Gate:** Owned-runtime/supervisor-registration/static-policy tests, API checks,
`./gradlew :enforcer:compileKotlin`. No public API removal or dependency addition.

## B6. Integrate snapshot authorization with the existing reply pipeline

**Modify:** `JVMValidationListener` in `SupervisorInstaller.kt`,
`JvmStackInspector.kt`, `SupervisorValidationDecoder.kt`, `JvmVerdictExchange.kt`,
`SupervisorRouteContext.kt`, `JvmVerdictRequestEncoder.kt`, helper
conversation/routing, associated FFM layouts and connected tests.
**New:** A small pure live-stack authorization function and typed decision reasons.

- [ ] Extend the private negotiated validation envelope to bind listener/session
  identity, notification ID, operation/request identity and prepared generation.
  Freeze the copied arguments across JVM decision and helper execution. Reject
  mixed/unsupported live protocol versions; retain the legacy protocol path.
- [ ] On a live request, read the registered worker's native snapshot through the
  platform bridge. Check task/installation/generation, completeness, binding-to-
  syscall compatibility and exact ordered corridor. Every unavailable/unknown
  status denies the selected application operation.
- [ ] Project approved tokens to `StackTraceElement` only for an optional legacy
  `ScopingHandler`. Its false/exception result can only deny. Require handlers to
  be short/nonblocking; do not execute user callbacks inside a native slot lock.
- [ ] Revalidate snapshot identity/generation before releasing the verdict and
  use existing ID_VALID checks before effects and terminal completion. Invalidated
  code generation rejects new tasks. Late/replayed decisions must not attach to
  a later task or syscall retry. Each retry has fresh notification authorization.
- [ ] Keep the reactor's existing monotonic deadline and single reply lease.
  Argument/resource work stays in terminable helpers. JVM listener failure marks
  the installation unusable; callback stalls cannot turn into unbounded reactor
  waits or spare worker replacement. Dispose late descriptors and preserve the
  existing notification-gone classification.
- [ ] Emit bounded reason/correlation diagnostics for static denial, missing or
  stale context, call-path mismatch, resource denial and supervisor failure. Avoid
  raw stack/path logging by default. Metrics are observability, not authority.

**Gate:** Pure decision matrix, malformed/replayed private-frame tests, deadline/
reply-lease/helper-cleanup tests, plus existing scoping callback compatibility.

## B7. Prove the first operation and failure envelope

**Extend:** `enforcer/src/integrationTest/kotlin/io/mazewall/OwnedScopingNativeTest.kt`
and the existing fresh-JVM supervisor fixtures. Add a focused live-context fixture
instead of placing another application in production sources.

| Required case | Expected evidence |
|---|---|
| Approved corridor + allowed read-only file | Exact bytes read; live snapshot and resource decision correlated to one notification |
| Same file from wrong caller/corridor | Stack denial; positive baseline control succeeds |
| Approved corridor + forbidden resource/static syscall | Denial; no FD injection or forbidden open effect |
| Identical class/method names from another loader | Identity mismatch, never name-based allowance |
| Missing agent/binding, unsupported JDK/namespace | Preparation refusal before application task admission |
| Failed/truncated capture, busy slot, unknown frame | Selected application open denied; no stack-walk fallback |
| Ring/dictionary absent or profiling disabled | Live authorization still works; no collector required |
| Redefinition/retransformation | Prepared generation invalid; no later authorization after observed invalidation; already committed effects are not claimed revoked |
| Nested proxy / Java callback / task transition | Outer context suppressed; no cross-task or parent authority leak |
| Signal cancellation and syscall retry | Distinct notification ownership; late result disposed; legitimate retry revalidated |
| Listener/helper death or stalled callback | Existing deadline/terminal behavior; worker unusable; bounded retained owners |
| Cleanup fails once, then retries | No lost owner, double-close, slot reuse or duplicate kernel reply |
| Cold class loading + GC/safepoint pressure | Explicit runtime-read route works; no stopped-thread stack inspection or JVM coordination denial |
| Symlink/rename/mount/flag mutation negatives | Resource gate holds against the copied request; no CONTINUE race |

- [ ] Use unrestricted/static-only positive controls and an authenticated internal
  decision receipt to distinguish stack/resource refusal from unrelated EPERM,
  missing files, setup failure or timeout. Assert the application side effect.
- [ ] Run high concurrency, worker reuse and GC pressure with bounded completion;
  capture inspector call counts showing zero target `Thread.getStackTrace` calls
  in live mode. Test one slow/failing session does not consume unbounded capacity.
- [ ] Package a minimal example using the new prepared API and NIO read. Show the
  allowed call, wrong caller, wrong resource, unsupported setup and complete owner
  drainage, using the same installed artifacts an application would consume.
- [ ] Update the containment/Tier E/liveness docs and `enforcer/README.md` with the
  exact qualified envelope and ordinary failure behavior. Keep profiling evidence
  policy-ineligible and legacy scoping guarantees distinct.

**Focused commands:**

```bash
./gradlew :platform:test
./gradlew :enforcer:test
./gradlew :enforcer:integrationTest --tests '*OwnedScopingNativeTest'
./gradlew :profiler:test --tests '*TierE*'
```

Use the owning `integrationTestFreshJvm`/configured container lane if required by
the fixture; do not run irreversible seccomp tests in the agent/Gradle host JVM.
Record skips as unqualified. Extend the profiler regression selection if shared
native changes affect its existing attribution, callback or terminal contracts.

## B8. Measure, qualify and stop at the useful boundary

- [ ] Before benchmarking, freeze one application-shaped workload, event rate,
  concurrency, file sizes, stack-depth distribution, duration and acceptable
  throughput/p95/p99/CPU/memory budgets. Budgets are required inputs to release
  qualification, not thresholds adjusted after seeing the result.
- [ ] Measure matched static-containment baseline, live-capture-only, brokered
  resource enforcement, and broker + live-stack enforcement arms. The broker-only
  measurement is a fixture control, not a selectable production bypass. Measure
  cold and warm code identities, concurrent notifications and overload separately.
- [ ] Attribute costs to JVMTI capture, snapshot copying, IPC, resource open and
  FD injection. Record denial/unknown rates, queueing, timeouts, peak slots/helper
  owners and actual cleanup. Do not compare weaker resource semantics as a faster
  equivalent. Freeze fingerprints and confidence intervals with the measurements.
- [ ] Optimize only a demonstrated bottleneck while rerunning its correctness
  matrix. Cache immutable code metadata, never a previous task's stack verdict.
  Every sensitive operation still needs current context and resource validation.
- [ ] Publish an opt-in support-matrix row only where B7 and the frozen SLO pass.
  If stack capture or safe brokering is too costly, retain the diagnostic backend
  and report the scoped enforcement result honestly; do not weaken unknown denial.
- [ ] Choose conditional C1/C2/C3 work from the roadmap only from these results.

## TDD cases

- B2–B3: bounded snapshot lifecycle, native-copy ABI rejection and no-collector capture.
- B4: resource/static-policy intersection, race-safe regular-file acquisition and FD ownership.
- B5–B6: frozen preparation, task/code identity, malformed/replayed frames and single terminal reply.
- B7: the full positive/negative/liveness matrix above, with controls proving the decision source.
- B8: complete correctness evidence is required before a benchmark arm can pass its frozen budget.

Implement behavior regressions before each change; validate documentation and
packaging with focused checks rather than tests that duplicate their text.

## Completion gate for each scoped implementation delivery

Run the affected native/host/fresh-JVM checks first, then `./gradlew build` once at
the completion checkpoint. Keep all project formatting/static-analysis/coverage
requirements. Update API dumps through the existing module workflow when changed
and inspect the diff. Run `git diff --check` and `./scripts/adkw check-backlog`;
retain evidence before archiving the delivery's issue. A build pass without the
required environment-specific tests does not qualify a new kernel/JDK envelope.
