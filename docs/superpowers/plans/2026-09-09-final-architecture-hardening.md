---
document_type: execution_plan
title: "Final Architecture Hardening Implementation Plan"
base_revision: f3fadff5
---

# Final Architecture Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the seven remaining architecture improvements and reduce `SupervisorSessionHandler` to a genuinely thin, exhaustively tested coordinator.

**Architecture:** Security-relevant boundaries return sealed values; session owners interpret effects and own resources. Mutable startup builders publish immutable runtime snapshots, while compatibility facades delegate without becoming alternate authorities. The supervisor decomposition is completed by moving all route and reply I/O out of the handler, not by adding wrappers around it.

**Tech Stack:** Kotlin 2.4.20-Beta1, JVM bytecode target 22 on JDK 25, Gradle, JUnit 5,
ArchUnit, Linux Seccomp USER_NOTIF, Landlock, and FFM.

**Spec:** `docs/superpowers/specs/2026-09-09-final-architecture-hardening-design.md`

## Global Constraints

- Exclude `tools/orchestrator` from edits and verification.
- Preserve public constructors, native signatures/layouts, policy APIs, portal method IDs, and frame bytes.
- Preserve Landlock-before-Seccomp, virtual-thread rejection, TSYNC/NEW_LISTENER exclusion, and fail-closed `EPERM`/`EACCES` behavior.
- Never close an invented or unowned descriptor.
- Add no dependency or generic state-machine framework.
- Use TDD; commit every task only after its focused module tests pass.
- Preserve all unrelated user-owned worktree changes and stage only task files.

---

### Task 1: Preserve kernel capability outcomes end to end

**Files:**
- Create: `enforcer/src/main/kotlin/io/mazewall/KernelCapability.kt`
- Create: `enforcer/src/main/kotlin/io/mazewall/KernelFeatureSnapshot.kt`
- Modify: `enforcer/src/main/kotlin/io/mazewall/PlatformProvider.kt`
- Modify: `enforcer/src/main/kotlin/io/mazewall/RealPlatformProvider.kt`
- Modify: `enforcer/src/main/kotlin/io/mazewall/Platform.kt`
- Modify: `enforcer/src/main/kotlin/io/mazewall/KernelFeatureMatrix.kt`
- Modify: `enforcer/src/main/kotlin/io/mazewall/InstallationAssessment.kt`
- Modify: `enforcer/src/main/kotlin/io/mazewall/landlock/LandlockCapabilityProbe.kt`
- Test: `enforcer/src/test/kotlin/io/mazewall/KernelCapabilityTest.kt`
- Test: `enforcer/src/test/kotlin/io/mazewall/PlatformProviderTest.kt`

**Interfaces:**
- Produces: `public sealed interface KernelCapability<out T> { Available<T>; Unsupported; Rejected; ProbeFailed }`.
- Produces: default `PlatformProvider.getLandlockCapability(): KernelCapability<Int>` derived from
  the legacy ABI method, so existing third-party implementations remain source-compatible.
- Produces: internal `KernelFeatureSnapshot(matrix, landlockCapability)` used by assessment.
- Compatibility: `getLandlockAbiVersion()`, the `KernelFeatureMatrix` primary constructor, and
  `KernelFeatureMatrix.landlockAbiVersion` remain unchanged. `RealPlatformProvider` overrides the
  typed method; its legacy method projects `Available(abi)` to `abi` and all other outcomes to `0`.

- [ ] **Step 1: Add red classification and propagation tests**

```kotlin
assertEquals(KernelCapability.Unsupported, classifyLandlock(error(ENOSYS)))
assertEquals(KernelCapability.Rejected(EPERM), classifyLandlock(error(EPERM)))
assertIs<InstallationBlocker.CapabilityRejected>(assess(landlock = KernelCapability.Rejected(EPERM)))
```

- [ ] **Step 2: Run the focused tests and confirm the current `IllegalStateException -> 0` collapse fails**

Run: `./gradlew --no-daemon :enforcer:test --tests '*KernelCapabilityTest' --tests '*PlatformProviderTest'`

- [ ] **Step 3: Implement the sealed capability flow**

Remove `IllegalStateException -> 0` from the typed path in `RealPlatformProvider`. Map only `ENOSYS`
and `EOPNOTSUPP` to `Unsupported`; retain errno/cause for every other outcome. Cache an internal
`KernelFeatureSnapshot` in `Platform`, expose its unchanged public matrix projection, and make
assessment messages state `unsupported`, `rejected with errno`, or `probe failed` explicitly.

- [ ] **Step 4: Run enforcer tests and commit**

Run: `./gradlew --no-daemon :enforcer:test`

Commit: `fix: preserve kernel capability failures`

### Task 2: Publish an immutable portal dispatcher table

**Files:**
- Create: `portal-worker/src/main/kotlin/io/mazewall/portal/worker/PortalDispatcherTable.kt`
- Modify: `portal-worker/src/main/kotlin/io/mazewall/portal/worker/PortalDispatcherRegistry.kt`
- Modify: `portal-worker/src/main/kotlin/io/mazewall/portal/worker/PortalWorkerMain.kt`
- Test: `portal-worker/src/test/kotlin/io/mazewall/portal/worker/PortalDispatcherTableTest.kt`
- Test: `portal-worker/src/test/kotlin/io/mazewall/portal/worker/PortalDispatcherRegistryTest.kt`

**Interfaces:**
- Produces: `PortalDispatcherTable.Builder.register(ids, handler)` and `build(): PortalDispatcherTable`.
- Produces: `PortalDispatcherTable.dispatchOrNull(method, payload, granted)`.
- Compatibility: legacy registry methods delegate to a startup builder only before publication.

- [ ] **Step 1: Add red tests for duplicate IDs, malformed entries, and mutation after publication**

```kotlin
val builder = PortalDispatcherTable.Builder()
val table = builder.register(intArrayOf(1000), first).build()
assertFailsWith<IllegalStateException> { builder.register(intArrayOf(1001), second) }
assertFailsWith<IllegalArgumentException> { strictBootstrap("malformed") }
assertSame(firstResult, table.dispatchOrNull(Generated(1000), payload, emptyList()))
```

- [ ] **Step 2: Prove the current compatibility bootstrap can mutate after `freeze()`**

Run: `./gradlew --no-daemon :portal-worker:test --tests '*PortalDispatcherTableTest'`

- [ ] **Step 3: Implement build-then-publish semantics**

Use a private mutable `LinkedHashMap` inside `Builder`; `build()` copies it with `Map.copyOf` and
invalidates the builder. `PortalWorkerMain` receives the table as a local immutable value. Remove
the live global `ConcurrentHashMap`; compatibility bootstrap must fail after publication.

- [ ] **Step 4: Run portal tests and byte-compatibility fixtures, then commit**

Run: `./gradlew --no-daemon :portal-worker:test :portal:test`

Commit: `refactor: publish immutable portal dispatch table`

### Task 3: Make spawn authorization session-owned and consume-once

**Files:**
- Create: `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SpawnAuthorizationStore.kt`
- Modify: `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorRuntimeContext.kt`
- Modify: `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorInstaller.kt`
- Delete after migration: `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/PendingSpawnRegistry.kt`
- Test: `enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SpawnAuthorizationStoreTest.kt`

**Interfaces:**
- Produces: `ProcessIdentity(pid: Pid, startTimeTicks: Long)`.
- Produces: `register(identity, stack, nowNanos)` and `consume(identity, nowNanos): SpawnAuthorizationResult`.
- Consumes: a `/proc/<pid>/stat` identity resolver injected through `SupervisorRuntimeContext`.

- [ ] **Step 1: Add red state tests**

Cover `Registered -> Consumed`, second consume rejection, expiry, session close, and identical PID
with different start time. Defensively copy stack frames at registration and result construction.

- [ ] **Step 2: Run the focused test and verify global/replay behavior fails**

Run: `./gradlew --no-daemon :enforcer:test --tests '*SpawnAuthorizationStoreTest'`

- [ ] **Step 3: Thread the store through the supervisor session**

Store it on `SupervisorRuntimeContext`; register after a validated spawn route; consume on child
exec; clear on session close. Never fall back to a global store when a session exists.

- [ ] **Step 4: Run supervisor tests and commit**

Run: `./gradlew --no-daemon :enforcer:test --tests 'io.mazewall.enforcer.supervisor.*'`

Commit: `fix: scope spawn authorization to supervisor sessions`

### Task 4: Interpret every cleanup outcome

**Files:**
- Create: `platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompCleanupInterpreter.kt`
- Modify: `platform/src/main/kotlin/io/mazewall/ffi/networking/SeccompConnection.kt`
- Modify: `platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompDaemonEngine.kt`
- Modify: `platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompSessionHandler.kt`
- Test: `platform/src/test/kotlin/io/mazewall/platform/seccomp/daemon/SeccompCleanupInterpreterTest.kt`
- Test: `platform/src/test/kotlin/io/mazewall/ffi/networking/SeccompConnectionTest.kt`

**Interfaces:**
- Produces: `SeccompCleanupOutcome.Complete | Incomplete(failures, retryableOwnership)`.
- Produces: `SeccompCleanupInterpreter.close(connection): SeccompCleanupOutcome`.

- [ ] **Step 1: Add red tests proving owner-visible failure and retry**

Assert that socket failure does not prevent listener close, failed ownership remains retryable, a
second attempt closes only remaining descriptors, and the daemon records an incomplete shutdown.

- [ ] **Step 2: Run focused tests and confirm `connection.close()` discards failure**

Run: `./gradlew --no-daemon :platform:test --tests '*SeccompCleanup*' --tests '*SeccompConnectionTest'`

- [ ] **Step 3: Implement cleanup effects and remove silent catches**

Have `SeccompDaemonEngine` consume `closeWithResult()`. Aggregate segment-pool and arena failures;
do not catch `Throwable`; preserve interruption. `AutoCloseable.close()` may delegate but the daemon
owner must use the typed result.

- [ ] **Step 4: Run platform and profiler daemon tests, then commit**

Run: `./gradlew --no-daemon :platform:test`

Run: `./gradlew --no-daemon :profiler:test --tests '*ProfilerDaemonTest'`

Commit: `fix: make seccomp cleanup failures observable`

### Task 5: Replace profiler singleton state with service/session ownership

**Files:**
- Create: `profiler/src/main/kotlin/io/mazewall/profiler/ProfilerService.kt`
- Create: `profiler/src/main/kotlin/io/mazewall/profiler/ProfilerSession.kt`
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/Profiler.kt`
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/ProfilerRuntime.kt`
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerTraceListener.kt`
- Test: `profiler/src/test/kotlin/io/mazewall/profiler/ProfilerServiceTest.kt`
- Test: `profiler/src/test/kotlin/io/mazewall/profiler/ProfilerRuntimeTest.kt`

**Interfaces:**
- Produces: `ProfilerService(runtime: ProfilerRuntime)`.
- Produces: `ProfilerSession` owning listener, `ProfilerSessionState`, event buffers, and close result.
- Compatibility: static `Profiler.profile/wrap/shutdown` delegate to one default service.

- [ ] **Step 1: Add red isolation tests**

Run two services concurrently with distinct fake runtimes and identical TIDs. Assert no listener,
thread, provider, event, or shutdown cross-talk. Assert session close unregisters only itself.

- [ ] **Step 2: Run focused tests and confirm global providers leak authority**

Run: `./gradlew --no-daemon :profiler:test --tests '*ProfilerServiceTest' --tests '*ProfilerRuntimeTest'`

- [ ] **Step 3: Move state into service/session objects**

Delete production reads of `Profiler.threadRegistry`, mutable provider properties, and listener
globals. Replace test mutation with constructor injection. Keep the object facade stateless except
for an atomic default-service reference used only by compatibility APIs.

- [ ] **Step 4: Run profiler tests and commit**

Run: `./gradlew --no-daemon :profiler:test`

Commit: `refactor: scope profiler runtime to services`

### Task 6: Propagate typed path-resolution evidence

**Files:**
- Create: `profiler/src/main/kotlin/io/mazewall/profiler/engine/PathResolution.kt`
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/engine/SyscallPathResolver.kt`
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/engine/SyscallEvent.kt`
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/ProfileObservation.kt`
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/ProfilingCoverage.kt`
- Test: `profiler/src/test/kotlin/io/mazewall/profiler/engine/SyscallPathResolverTest.kt`
- Test: `profiler/src/test/kotlin/io/mazewall/profiler/ProfilerSessionApiTest.kt`

**Interfaces:**
- Produces: `PathResolution.NotApplicable | Resolved(paths) | Partial(paths, failures) | Failed(failures)`.
- Produces: `PathResolutionFailure` with operand index and reason (`VmReadDenied`, `Missing`, `DirFdUnresolved`, `Truncated`).

- [ ] **Step 1: Add red exhaustive tests for every resolution outcome**

Cover one- and two-path syscalls, relative paths, unresolved dirfd, Yama denial, truncation, unknown
syscall, and partial `rename/link` resolution. Verify failure reasons survive into coverage.

- [ ] **Step 2: Run focused tests and prove empty-list ambiguity**

Run: `./gradlew --no-daemon :profiler:test --tests '*SyscallPathResolverTest' --tests '*ProfilerSessionApiTest'`

- [ ] **Step 3: Replace heuristic reconstruction**

Carry `PathResolution` through the heap-only event and observation. `ProfilingCoverage.infer` folds
the typed results exhaustively; remove path-count inference and all `else` branches over the sealed
result.

- [ ] **Step 4: Run profiler tests and commit**

Run: `./gradlew --no-daemon :profiler:test`

Commit: `refactor: type profiler path evidence`

### Task 7: Make architecture and tests deterministic for maintainers

**Files:**
- Create: `src/sharedTest/kotlin/io/mazewall/testing/Eventually.kt`
- Modify: timing-based tests reported by `rg 'Thread.sleep' */src/test`
- Modify: `enforcer/src/test/kotlin/io/mazewall/ArchitectureTest.kt`
- Modify: `build.gradle.kts`
- Test: `enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonEngineTest.kt`

**Interfaces:**
- Produces: bounded `awaitCondition(timeout, description, predicate)` and latch/future helpers.
- Produces: architecture rules for reply ownership, mutable singleton seams, and handler size.

- [ ] **Step 1: Reproduce the EINTR ACK test flake under repetition**

Run the focused test 100 times in fresh test workers:

```bash
for run in {1..100}; do
  ./gradlew --no-daemon :enforcer:test \
    --tests '*SupervisorDaemonEngineTest.processConnectionStep retries on EINTR during ACK write' \
    --rerun-tasks || exit 1
done
```

Capture the failing interleaving and replace any global FD guard collision with per-test
engine/descriptor ownership.

- [ ] **Step 2: Replace fixed unit-test sleeps with observable barriers**

Use latches, futures, or bounded condition waits whose return value is asserted. Exempt only named
benchmark/stress workloads and document each exemption.

- [ ] **Step 3: Add structural verification**

ArchUnit must forbid `UserNotifReply.send` outside the reply interpreter, mutable provider seams on
production singleton objects, and FFM access outside approved packages. Add a Gradle check that
prints added/removed Detekt fingerprints rather than only a hash mismatch.

- [ ] **Step 4: Run repeated tests and commit**

Run: `./gradlew --no-daemon :enforcer:test --tests '*SupervisorDaemonEngineTest' --rerun-tasks`

Run: `./gradlew --no-daemon :enforcer:test :platform:test :profiler:test :portal:test :portal-worker:test`

Commit: `test: enforce deterministic architecture contracts`

### Task 8: Fully decompose `SupervisorSessionHandler`

**Files:**
- Create: `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/JvmVerdictExchange.kt`
- Create: `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorReplyEffect.kt`
- Create: `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/OpenRouteExecutor.kt`
- Create: `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SocketRouteExecutor.kt`
- Create: `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SecureExecRouteExecutor.kt`
- Create: `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/AsyncSocketRouteExecutor.kt`
- Modify: `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorReplyInterpreter.kt`
- Modify: `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorRouteExecutor.kt`
- Modify: `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorRouteDispatcher.kt`
- Rewrite: `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt`
- Split: `enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandlerTest.kt`
- Test: `enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/JvmVerdictExchangeTest.kt`
- Test: `enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisorReplyInterpreterMatrixTest.kt`
- Test: focused route-executor tests matching each new executor.

**Interfaces:**
- Produces: `sealed interface SupervisorReplyEffect { Continue; Error; AddFdAndReply; SecureExecAndReply; Recycle }`.
- Produces: `sealed interface JvmExchangeResult { Verdict; Timeout; Malformed; TransportFailed }`.
- Changes internal route executors to return effects; they cannot write a kernel reply.
- `SupervisorReplyInterpreter.execute(effect, context): ReplyExecutionResult` is the sole owner of reply syscalls.

- [ ] **Step 1: Characterize every current terminal path**

Create a table-driven fixture covering Continue, policy deny, timeout, malformed JVM response,
open/openat/openat2, connect, accept, FD injection, secure exec, async failure, notification ID
mismatch, reply failure, and protocol violation. For every row assert zero or one reply before
terminal state and exactly one reply on every successfully received live notification.

- [ ] **Step 2: Make the reply-effect matrix fail against direct handler writes**

Add an ArchUnit/structural assertion that the handler and route executors contain no calls to
`UserNotifReply.send`, `sendSeccompError`, or `sendSeccompContinue`. Confirm failure before moving
production code.

- [ ] **Step 3: Extract JVM request/reply exchange**

Move encode/write/poll/deadline/read/decode logic into `JvmVerdictExchange`. It returns one sealed
result and never replies to the kernel. Test EINTR, timeout, short frame, oversized/malformed frame,
ID mismatch, EOF, and socket-close behavior.

- [ ] **Step 4: Extract route executors mechanically**

Move open-family logic to `OpenRouteExecutor`, connect/accept to `SocketRouteExecutor`, exec rewrite
to `SecureExecRouteExecutor`, and asynchronous socket work to `AsyncSocketRouteExecutor`. Replace
positional primitive lists with `SupervisorRouteContext` and dedicated request value types. Each
executor returns `SupervisorReplyEffect` and closes only descriptors it owns.

- [ ] **Step 5: Make the reply interpreter authoritative**

Move CONTINUE, ERROR, ADDFD-with-SEND, and secure-exec terminal operations behind
`SupervisorReplyInterpreter`. Return `ReplySucceeded` only after the native operation succeeds;
reply failure returns terminal `ReplyFailed` and triggers cleanup without a second reply.

- [ ] **Step 6: Rewrite the handler as a thin reducer/effect loop**

The handler may read a notification, advance `SupervisorSessionMachine`, invoke one collaborator,
interpret its returned effect, and close the session. It must contain no syscall-specific route
implementation and no direct reply encoding/writing.

- [ ] **Step 7: Split the 2,395-line test fixture**

Create typed tests per collaborator plus one end-to-end session matrix. Delete reflection/string
coupling and shared global mutable fixtures. Keep byte-level and native mock assertions at the
boundary owning those bytes/syscalls.

- [ ] **Step 8: Enforce measurable structural gates**

```kotlin
assertTrue(handlerFile.readLines().size <= 300)
assertFalse(handlerSource.contains("sendSeccompError"))
assertFalse(handlerSource.contains("sendSeccompContinue"))
```

Also require exhaustive `when` expressions over session events/effects with no `else` branch.

- [ ] **Step 9: Run focused, module, and fresh-JVM tests**

Run: `./gradlew --no-daemon :enforcer:test --tests 'io.mazewall.enforcer.supervisor.*'`

Run: `./gradlew --no-daemon :enforcer:integrationTestFreshJvm`

Expected: all tests pass; handler is at most 300 lines; every received notification has exactly one
confirmed terminal reply or an explicit terminal reply failure.

- [ ] **Step 10: Regenerate diagrams and commit**

Before generation, run `git status --short docs/diagrams`. If either enforcer diagram is dirty,
generate in an isolated clean worktree and transplant only the attributable generated delta after
review; do not overwrite or stage the pre-existing user changes in the shared worktree.

Run in that clean worktree: `./gradlew --no-daemon :enforcer:generateClassDiagrams`

Commit: `refactor: decompose supervisor session handler`

### Task 9: Final compatibility and delivery gate

**Files:**
- Modify: `docs/superpowers/plans/2026-09-09-final-architecture-hardening.md` checkboxes only.
- Modify only if behavior changed: canonical design/KDoc and generated diagrams.

- [ ] **Step 1: Verify compatibility fixtures**

Run Java/Kotlin API compatibility tests, portal byte fixtures, native layout validation, and policy
compilation fixtures. Compare public signatures against the pre-plan commit.

- [ ] **Step 2: Run in-scope verification without the persistent daemon**

Run: `./gradlew --no-daemon :platform:build :enforcer:build :profiler:build :portal:build :portal-worker:build`

Run: `./gradlew --no-daemon detektWarningRatchet validateBacklogIndex`

- [ ] **Step 3: Audit invariants and residual complexity**

Verify Landlock precedes Seccomp, virtual threads are rejected, TSYNC is never combined with
NEW_LISTENER, no owned-FD token is created from an unverified integer, no closed security state has
a silent `else`, and `SupervisorSessionHandler.kt` is at most 300 lines.

- [ ] **Step 4: Commit final documentation/evidence**

Commit: `docs: complete final architecture hardening`
