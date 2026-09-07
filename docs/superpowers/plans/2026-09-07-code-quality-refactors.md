# Remaining Code-Quality Refactors Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the approved non-orchestrator code-quality backlog without weakening containment semantics.

**Architecture:** Work in dependency order: type-level ownership before consumers; deterministic evaluators before native interpreters; session reducers before I/O loop extraction. Each reducer is internal, sealed, pure, and exhaustively tested; all kernel I/O remains in interpreters.

**Tech Stack:** Kotlin/JDK 25, Gradle, JUnit 5, ArchUnit, Foreign Function & Memory API, Seccomp-BPF, Landlock, Unix-domain sockets.

**Spec:** `docs/superpowers/specs/2026-09-07-code-quality-refactors-design.md`

## Global Constraints

- Preserve fail-closed `EPERM`/`EACCES` behavior; never turn a containment failure into a warning-only path.
- Landlock installation precedes Seccomp installation.
- Never install Seccomp on a virtual thread; never combine TSYNC and NEW_LISTENER.
- Preserve existing FFM layouts and C field widths; use confined arenas and capture errno immediately after every downcall.
- Only `FdOwnership.Owned` descriptors may be closed; no closeable token may be created from an arbitrary integer.
- Every USER_NOTIF request receives CONTINUE, KILL_THREAD, or ABORT exactly once.
- Portal framing remains byte-compatible; numeric wire IDs are parsed at the boundary into sealed Kotlin types.
- Do not modify excluded profiler-daemon, public-API-versioning, Tier-E daemon, Codanna, CI, or orchestrator work.

---

### Task 1: Complete FD ownership and typed descriptor propagation

**Files:**
- Modify: `platform/src/main/kotlin/io/mazewall/core/FileDescriptor.kt`
- Modify: `platform/src/main/kotlin/io/mazewall/platform/seccomp/SupervisedKind.kt`
- Modify: `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisedOpen.kt`
- Modify: `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorFastPath.kt`
- Modify: `enforcer/src/main/kotlin/io/mazewall/landlock/Landlock.kt`
- Test: `platform/src/test/kotlin/io/mazewall/platform/seccomp/SupervisedKindTest.kt`
- Test: `enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisedOpenTest.kt`

**Interfaces:**
- Consumes: `FileDescriptor<R, FdState.Open, FdOwnership.Owned>`, `SyscallNumber`.
- Produces: typed supervisor `dirfd`/Landlock open results and an exhaustive `SupervisedKind.classify(SyscallNumber)` edge.

- [ ] **Step 1: Write failing ownership and classifier tests**

```kotlin
@Test fun `unowned descriptor cannot be passed to a close-owning result`() {
    val unowned = FileDescriptor.generic(42)
    assertIs<FdOwnership.Unowned>(unowned.ownership)
}

@Test fun `classify accepts typed syscall number`() {
    assertEquals(SupervisedKind.Open, SupervisedKind.classify(SyscallNumber.openAt))
}
```

- [ ] **Step 2: Run the focused tests and observe the type/API failure**

Run: `./gradlew :platform:test --tests io.mazewall.platform.seccomp.SupervisedKindTest`

- [ ] **Step 3: Make descriptor ownership explicit at all affected boundaries**

```kotlin
internal fun resolveAbsolutePath(
    dirfd: FileDescriptor<*, FdState.Open, *>,
    path: String,
): String?

fun classify(number: SyscallNumber): SupervisedKind = when (number) {
    // every known supervised number is named here
}
```

Keep raw `Int` only in syscall ABI argument storage. Use an owned token for an
FD that the local process closes and an unowned token for a tracee-owned FD.

- [ ] **Step 4: Run focused platform and supervisor tests**

Run: `./gradlew :platform:test --tests io.mazewall.platform.seccomp.SupervisedKindTest`

Run: `./gradlew :enforcer:test --tests io.mazewall.enforcer.supervisor.SupervisedOpenTest`

### Task 2: Finish pure planner and resolver extraction

**Files:**
- Modify: `enforcer/src/main/kotlin/io/mazewall/seccomp/InstallSelfVerifier.kt`
- Modify: `enforcer/src/main/kotlin/io/mazewall/seccomp/SelfVerificationPlan.kt`
- Modify: `platform/src/main/kotlin/io/mazewall/core/Syscall.kt`
- Modify: `platform/src/main/kotlin/io/mazewall/core/SyscallNumberResolver.kt`
- Test: `enforcer/src/test/kotlin/io/mazewall/seccomp/SelfVerificationPlanTest.kt`
- Test: `platform/src/test/kotlin/io/mazewall/core/SyscallNumberResolverTest.kt`

**Interfaces:**
- Consumes: compiled BPF program, architecture, optional container state.
- Produces: immutable `SelfVerificationPlan` and pure `SyscallNumberResolver.numberFor` results.

- [ ] **Step 1: Write failing table-driven plan and resolver tests**

```kotlin
@Test fun `plan derives errno probe without invoking the executor`() {
    val plan = SelfVerificationPlan.evaluate(program, Arch.X86_64, null)
    assertEquals(ExpectedOutcome.Errno(Errno.EPERM), plan.probes.single().expected)
}

@Test fun `resolver preserves unsupported sentinel`() {
    assertEquals(SyscallNumber.UNSUPPORTED, SyscallNumberResolver(Arch.X86_64).numberFor(Syscall.Unknown))
}
```

- [ ] **Step 2: Run tests and observe the missing pure evaluation behavior**

Run: `./gradlew :enforcer:test --tests io.mazewall.seccomp.SelfVerificationPlanTest`

Run: `./gradlew :platform:test --tests io.mazewall.core.SyscallNumberResolverTest`

- [ ] **Step 3: Keep planning pure and retain native work in executors**

```kotlin
internal object SelfVerificationPlan {
    fun evaluate(program: BpfProgram, arch: Arch, state: ContainerState?): Plan = /* pure table evaluation */
}

internal class SyscallNumberResolver(private val arch: Arch) {
    fun numberFor(syscall: Syscall): SyscallNumber = /* immutable mapping */
}
```

`InstallSelfVerifier` runs probes, captures errno, publishes diagnostics, and
memoizes only after all plan probes pass. `Syscall` delegates mapping only; it
does not duplicate architecture tables.

- [ ] **Step 4: Run focused tests and affected module tests**

Run: `./gradlew :enforcer:test --tests io.mazewall.seccomp.InstallSelfVerifierTest`

Run: `./gradlew :platform:test`

### Task 3: Finish the Tier-E BPF load request encoder

**Files:**
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/BpfProgLoadRequestEncoder.kt`
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEbpfEngine.kt`
- Test: `profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/BpfProgLoadRequestEncoderTest.kt`

**Interfaces:**
- Consumes: validated BPF instructions, license/log options, pseudo-map substitution.
- Produces: caller-arena-owned encoded `bpf_attr`; `TierEbpfEngine` owns the downcall and errno translation.

- [ ] **Step 1: Write failing encoder-layout tests**

```kotlin
@Test fun `encoder substitutes pseudo map fd before writing instructions`() = arenaScope {
    val encoded = BpfProgLoadRequestEncoder.encode(requestWithPseudoFd(7), arena)
    assertEquals(7, encoded.instructions().pseudoMapFdAt(0))
}

@Test fun `encoder rejects an overlong program name`() {
    assertFailsWith<IllegalArgumentException> { BpfProgLoadRequestEncoder.validateName("x".repeat(16)) }
}
```

- [ ] **Step 2: Run the encoder test and observe the expected failure**

Run: `./gradlew :profiler:test --tests io.mazewall.profiler.tierE.engine.BpfProgLoadRequestEncoderTest`

- [ ] **Step 3: Implement pure validation/encoding and preserve the FFM boundary**

```kotlin
context(arena: NativeArena)
internal fun encode(request: BpfProgLoadRequest): EncodedBpfProgLoad =
    EncodedBpfProgLoad(attr = encodeAttr(validateAndSubstitute(request), arena))
```

The encoder performs no syscall and does not retain `MemorySegment` outside the
caller arena. `TierEbpfEngine` invokes `bpf`, reads errno immediately, and maps
the native result exactly as before.

- [ ] **Step 4: Run profiler encoder and module tests**

Run: `./gradlew :profiler:test --tests io.mazewall.profiler.tierE.engine.BpfProgLoadRequestEncoderTest`

Run: `./gradlew :profiler:test`

### Task 4: Make Landlock and registry installation effect-driven

**Files:**
- Modify: `enforcer/src/main/kotlin/io/mazewall/landlock/LandlockState.kt`
- Modify: `enforcer/src/main/kotlin/io/mazewall/enforcer/state/ContainmentRegistryEffect.kt`
- Modify: `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/ContainedExecutorWrapper.kt`
- Test: `enforcer/src/test/kotlin/io/mazewall/landlock/LandlockApplyResultTest.kt`
- Test: `enforcer/src/test/kotlin/io/mazewall/enforcer/state/ContainmentRegistryEffectTest.kt`

**Interfaces:**
- Consumes: install events and native results.
- Produces: `Transition<LandlockState, LandlockEffect>` and registry effects applied only by an interpreter.

- [ ] **Step 1: Write failing reducer tests for order and failure**

```kotlin
@Test fun `ruleset creation effect precedes restrict self`() {
    val transition = LandlockMachine.evaluate(Initial, InstallRequested(rules))
    assertEquals(listOf(CreateRuleset(rules)), transition.effects)
}

@Test fun `registry is not updated before successful seccomp event`() {
    assertTrue(ContainmentRegistryEffect.evaluate(Pending, SeccompFailed(Errno.EPERM)).effects.none { it is MarkSeccompInstalled })
}
```

- [ ] **Step 2: Run tests and observe the absent transition/effect behavior**

Run: `./gradlew :enforcer:test --tests io.mazewall.landlock.LandlockApplyResultTest`

- [ ] **Step 3: Introduce sealed events/effects and a pure reducer**

```kotlin
internal fun evaluate(state: LandlockState, event: LandlockEvent): Transition<LandlockState, LandlockEffect> =
    when (state) { /* exhaustive state/event transitions */ }
```

Interpret `CreateRuleset`, `AddRule`, `RestrictSelf`, `CloseFd`, and registry
publication outside the reducer. Feed every result back as an event. Preserve
Landlock-before-Seccomp in the state transitions, not comments.

- [ ] **Step 4: Run focused and kernel-sensitive verification**

Run: `./gradlew :enforcer:test --tests io.mazewall.landlock.LandlockApplyResultTest`

Run: `./gradlew integrationTestFreshJvm`

### Task 5: Split supervisor routes and complete USER_NOTIF session effects

**Files:**
- Modify: `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt`
- Modify: `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionMachine.kt`
- Create: `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/JvmVerdictRequest.kt`
- Create: route-specific executor files under `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/`
- Test: `enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionMachineTest.kt`
- Test: `enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandlerTest.kt`

**Interfaces:**
- Consumes: `SupervisorNotificationMachine` route decision plus parsed request context.
- Produces: session-machine effects for poll/read/send/inject/ACK and exactly one terminal reply.

- [ ] **Step 1: Write failing exact-one-reply transition tests**

```kotlin
@Test fun `every terminal session path emits exactly one reply`() {
    SupervisorSessionMachine.terminalPaths().forEach { path ->
        assertEquals(1, path.flatMap { it.effects }.count { it is SendAck })
    }
}

@Test fun `jvm verdict request keeps header fields named`() {
    val request = JvmVerdictRequest(id, header, resolvedPath = null, sockaddrBytes = null)
    assertEquals(id, request.id)
}
```

- [ ] **Step 2: Run the supervisor tests and observe the missing behavior**

Run: `./gradlew :enforcer:test --tests io.mazewall.enforcer.supervisor.SupervisorSessionMachineTest`

- [ ] **Step 3: Extract route executors and make the handler an interpreter**

```kotlin
internal data class JvmVerdictRequest(
    val id: Long,
    val header: NotifHeader,
    val resolvedPath: String?,
    val sockaddrBytes: ByteArray?,
)

internal fun evaluate(state: SessionState, event: SessionEvent): Transition<SessionState, SessionEffect>
```

The handler performs native read/poll/ioctl only while interpreting effects;
route-specific executors accept context objects rather than positional primitive
lists. On every interpreter error, feed a failure event and execute the one
required terminal ACK effect.

- [ ] **Step 4: Run focused and USER_NOTIF verification**

Run: `./gradlew :enforcer:test --tests io.mazewall.enforcer.supervisor.SupervisorSessionHandlerTest`

Run: `./gradlew integrationTestFreshJvm`

### Task 6: Complete profiler protocol typing and API isolation

**Files:**
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/engine/HandshakeSession.kt`
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerSessionMachine.kt`
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerTraceListener.kt`
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/ControlProtocol.kt`
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/engine/TraceEvent.kt`
- Modify: `profiler/api/profiler.api`
- Test: `profiler/src/test/kotlin/io/mazewall/profiler/engine/HandshakeSessionTest.kt`
- Test: `profiler/src/test/kotlin/io/mazewall/profiler/engine/ProfilerSessionMachineTest.kt`
- Test: `profiler/src/test/kotlin/io/mazewall/profiler/ProfilerApiDumpTest.kt`

**Interfaces:**
- Consumes: handshake I/O outcomes, trace-listener events, control-wire tokens.
- Produces: internal handshake types, pure lifecycle transitions, sealed `ControlCommand`, named mmap bit types.

- [ ] **Step 1: Write failing reducer/API/control tests**

```kotlin
@Test fun `unknown control token is an explicit command variant`() {
    assertEquals(ControlCommand.Unknown("bad"), parseControlCommand("bad"))
}

@Test fun `handshake I O failure transitions through evaluate`() {
    val transition = ProfilerSessionMachine.evaluate(AwaitingHandshake, HandshakeIoFailed(IOException("boom")))
    assertEquals(Disconnected, transition.state)
}

@Test fun `profiler api contains no memory segment signature`() =
    assertFalse(apiText.contains("MemorySegment"))
```

- [ ] **Step 2: Run focused profiler tests and observe expected failures**

Run: `./gradlew :profiler:test --tests io.mazewall.profiler.engine.ProfilerSessionMachineTest`

- [ ] **Step 3: Make lifecycle transitions and control parsing exhaustive**

```kotlin
internal sealed interface ControlCommand {
    data object Start : ControlCommand
    data object Stop : ControlCommand
    data class Unknown(val token: String) : ControlCommand
}

internal fun evaluate(state: ProfilerSessionState, event: ProfilerSessionEvent): Transition<ProfilerSessionState, ProfilerEffect>
```

`HandshakeSession`, native-I/O helpers, and segment conversion are `internal`;
the listener and handler interpret machine effects. Unknown control commands
reply with the existing usage error. Mmap prot/flags are named value types at
the Kotlin boundary while retaining Linux integer encoding in FFI.

- [ ] **Step 4: Run profiler API and module tests**

Run: `./gradlew :profiler:test --tests io.mazewall.profiler.ProfilerApiDumpTest`

Run: `./gradlew :profiler:test`

### Task 7: Make portal protocol dispatch sealed, reviewable, and effect-driven

**Files:**
- Modify: `portal/src/main/kotlin/io/mazewall/portal/PortalFrame.kt`
- Modify: `portal/src/main/kotlin/io/mazewall/portal/ProcessBroker.kt`
- Modify: `portal/src/main/kotlin/io/mazewall/portal/PortalBrokerCallMachine.kt`
- Modify: `portal-worker/src/main/kotlin/io/mazewall/portal/worker/PortalBuiltinDispatch.kt`
- Modify: `portal-worker/src/main/kotlin/io/mazewall/portal/worker/PortalWorkerMain.kt`
- Modify: `portal-worker/src/main/kotlin/io/mazewall/portal/worker/PortalWorkerMachine.kt`
- Modify: `portal-codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalStubGenerator.kt`
- Test: `portal/src/test/kotlin/io/mazewall/portal/PortalFrameTest.kt`
- Test: `portal/src/test/kotlin/io/mazewall/portal/PortalBrokerCallMachineTest.kt`
- Test: `portal-worker/src/test/kotlin/io/mazewall/portal/worker/PortalWorkerMachineTest.kt`
- Test: `portal-codegen/src/test/kotlin/io/mazewall/portal/codegen/PortalStubGeneratorTest.kt`

**Interfaces:**
- Consumes: numeric on-wire method/kind IDs and RPC transport outcomes.
- Produces: parsed `PortalMethod`, pure broker/worker transitions, reviewed generated dispatcher source.

- [ ] **Step 1: Write failing protocol and code-generation tests**

```kotlin
@Test fun `frame parses wire method once`() {
    assertEquals(PortalMethod.Checksum, PortalFrame.decode(bytes).method)
}

@Test fun `broker reducer emits send then receive effects`() {
    assertEquals(listOf(SendFrame(request)), PortalBrokerCallMachine.evaluate(Idle, CallRequested(request)).effects)
}

@Test fun `generated dispatcher contains named granted fd slot`() {
    assertTrue(generatedSource.contains("val inputFd = granted[0]"))
}
```

- [ ] **Step 2: Run focused portal tests and observe expected failures**

Run: `./gradlew :portal:test --tests io.mazewall.portal.PortalFrameTest`

- [ ] **Step 3: Parse once and interpret pure broker/worker effects**

```kotlin
internal sealed interface PortalMethod { val wireId: Int }
internal fun evaluate(state: PortalBrokerState, event: PortalBrokerEvent): Transition<PortalBrokerState, PortalBrokerEffect>
internal fun evaluate(state: PortalWorkerState, event: PortalWorkerEvent): Transition<PortalWorkerState, PortalWorkerEffect>
```

Frame parsing maps unknown IDs to an explicit protocol error. `when` branches
are exhaustive and have no `else`; SCM_RIGHTS and byte arrays remain in effect
interpreters. Generated source names every method and granted descriptor slot.

- [ ] **Step 4: Run portal module tests**

Run: `./gradlew :portal:test`

Run: `./gradlew :portal-worker:test`

Run: `./gradlew :portal-codegen:test`

### Task 8: Finish assertion-quality, readability, errno, arithmetic, and documentation work

**Files:**
- Modify: `enforcer/src/main/kotlin/io/mazewall/BpfFilter.kt`
- Modify: `platform/src/main/kotlin/io/mazewall/ffi/networking/SupervisorSocketUtils.kt`
- Modify: `platform/src/main/kotlin/io/mazewall/ffi/internal/SyscallInvoker.kt`
- Modify: coverage-only tests named in `issue-20260826-180103`
- Modify: `enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt`
- Modify: `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/ContainedExecutorWrapper.kt`
- Modify: `docs/internals/designs/enforcer/containment-design.md`
- Test: `enforcer/src/test/kotlin/io/mazewall/seccomp/BpfFilterTest.kt`
- Test: replacement behavioral tests for each retained coverage test

**Interfaces:**
- Consumes: existing BPF instruction semantics, socket ABI constants, native downcall handles.
- Produces: named/documented arithmetic, one immediate-errno helper, behavior-focused tests, accurate installation-order documentation.

- [ ] **Step 1: Replace one hollow test at a time with a failing behavioral assertion**

```kotlin
@Test fun `argument inspection emits high word load before low word comparison`() {
    val program = BpfFilter(policyWithArgumentInspection()).compile()
    assertEquals(expectedInspectionInstructions, program.instructionsFor(Syscall.OpenAt))
}
```

- [ ] **Step 2: Run each focused replacement test before deleting filler coverage**

Run: `./gradlew :enforcer:test --tests io.mazewall.seccomp.BpfFilterTest`

- [ ] **Step 3: Extract only behavior-neutral helpers and names**

```kotlin
private fun invokeWithErrno(handle: MethodHandle, vararg args: Any?): SyscallResult<Long> {
    val value = handle.invokeExact(*args) as Long
    return SyscallResult(value, getErrno())
}

private const val CMSGHDR_DATA_OFFSET_LP64 = 16L
private const val CMSG_RIGHTS_LEN_ONE_FD_LP64 = 20L
```

Keep errno capture adjacent to `invokeExact`; comments identify BPF load width,
comparison width, and `CMSG_ALIGN` assumptions. Documentation points to
`installInternal`, `applyLandlockIfNecessary`, then `installSeccompFilter`.

- [ ] **Step 4: Run quality gates and coverage check**

Run: `./gradlew :enforcer:test`

Run: `./gradlew :platform:test`

Run: `./scripts/check_coverage.sh`

### Task 9: Close the evidence loop

**Files:**
- Modify: only backlog records whose completion is demonstrated by Task 1–8 evidence.
- Review: `docs/internals/backlog/code_health/`

**Interfaces:**
- Consumes: focused test, kernel-test, API, and build outputs.
- Produces: accurate status notes; no code behavior.

- [ ] **Step 1: Run the full build after every focused module gate is green**

Run: `./gradlew build`

- [ ] **Step 2: Run the backlog checker**

Run: `./scripts/adkw check-backlog`

- [ ] **Step 3: Review scope and update only evidenced backlog records**

```text
For each included issue: record the focused command and result; retain open
status when any acceptance criterion remains. Do not edit excluded or
orchestrator records.
```

- [ ] **Step 4: Run whitespace and delivery guards on changed files**

Run: `git diff --check`

Run: `./scripts/adkw guard <each-changed-file> --stage delivery`
