# Security Suppression Reduction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce the 105 production Kotlin `@Suppress` sites (135 repository-wide) to a small, auditable set of explicitly justified safety-boundary exceptions, without weakening fail-closed behavior.

**Architecture:** Treat a suppression as a design debt signal. First make unsafe control flow and casts explicit in types and result values; then decompose complexity and name ABI constants; finally enforce a source-level suppression allowlist. A retained suppression is permitted only where Kotlin/Detekt cannot express a required kernel/FFM or cleanup invariant, and its adjacent comment must name that invariant, the failure behavior, and why a narrower construct cannot replace it.

**Tech Stack:** Kotlin/JDK 25, Detekt, Gradle, JUnit 5, ArchUnit, Seccomp-BPF, Landlock, FFM.

**Spec:** This plan; baseline inventory from `rg -n '@Suppress\\(' --glob '*.kt' --glob '!**/build/**'` on 2026-09-07.

## Baseline and delivery gates

- Baseline: 135 Kotlin annotation sites: 105 production, 25 test source sets, and 5 demo/tool sources. The largest production clusters are profiler triage/listener (11), platform FD/context/native code (15), supervisor paths (20+), and policy/BPF compilation (12+).
- Full build is currently **red** and must be repaired before this work is claimed complete: `:demos:cli-demo:test` has three outdated exception-contract assertions; `:portal-codegen:test` timed out connecting to its child JVM; `:enforcer:detektMain` reports one compiler-analysis error. Reproduce and resolve these independently; no suppression cleanup may hide them.
- Preserve fail-closed `EPERM`/`EACCES`; never catch `Throwable` or `Error` merely to continue execution. Landlock remains before Seccomp; never install Seccomp on a virtual thread; never combine TSYNC with NEW_LISTENER.
- Do not modify `tools/orchestrator`, `.worktrees`, `.taskplane-kb`, generated diagrams, or other unrelated user-owned files.
- Production acceptance target: no `@Suppress("TooGenericExceptionCaught")`, `@Suppress("SwallowedException")`, or `@Suppress("UNCHECKED_CAST")` except entries in the reviewed allowlist below; all retained production suppressions have an adjacent `// SUPPRESSION-RATIONALE:` comment. Test-only exceptions are moved to narrowly scoped test helpers or test-source Detekt configuration, not copied into production.

## Retention allowlist (maximum twelve production sites)

Each retained site must be reviewed individually in the implementation PR. The expected categories—not blanket approvals—are:

1. Fail-closed USER_NOTIF reply boundary: a final defensive `Throwable` conversion that replies `EPERM`/abort and rethrows or terminates; never returns success.
2. Cleanup that preserves an already-primary failure: close/rollback failure is attached as suppressed, and the original containment failure remains observable.
3. `InterruptedException` restoration at daemon/thread shutdown: restores the interrupt flag and returns a typed shutdown result.
4. ABI-only numeric constants directly coupled to Linux struct/syscall layouts, when extracting a named constant would obscure the layout. The rationale names the ABI field and width.
5. A type-erased JVM/FFM bridge cast, after a runtime type check and inside one private adapter.

Any other retained suppression requires an explicit written security review finding. `TooManyFunctions`, complexity, return-count, long-parameter-list, and generic-catch suppressions are not retainable categories.

---

### Task 1: Establish a deterministic inventory and suppression policy

**Files:**
- Modify: `config/detekt/detekt.yml`
- Create: `scripts/check_suppressions.sh` (or equivalent Gradle verification task)
- Create: `config/detekt/approved-suppressions.txt`
- Test: `scripts/check_suppressions_test.sh` or Gradle task test

- [ ] Add a checked-in inventory generator that records file, line, rule ID, source set, and whether the immediately preceding rationale marker exists.
- [ ] Enable Detekt `ForbiddenSuppress` for all non-allowlisted rule IDs and prohibit file/class-level suppressions in production.
- [ ] Implement a CI/local gate that rejects a new production suppression unless its exact `file:line:rule` is listed in `approved-suppressions.txt` and it has an adjacent `SUPPRESSION-RATIONALE` comment.
- [ ] Start with an empty allowlist; add entries only after the corresponding refactor proves no safer replacement exists.

**Verification:**

```bash
./scripts/check_suppressions.sh
./gradlew detekt
```

### Task 2: Remove generic catches and swallowed failures from containment paths

**Files:**
- Modify: `enforcer/.../internal/ContainedExecutorWrapper.kt`
- Modify: `enforcer/.../supervisor/{BypassPaths,SupervisorDaemon,SupervisorDaemonManager,SupervisorFastPath,SupervisorInstaller,SupervisorSessionHandler}.kt`
- Modify: `enforcer/.../ffi/networking/{SupervisorSeccompNotifInstaller,SupervisorSocketInputStream}.kt`
- Modify: `platform/.../seccomp/daemon/{SeccompDaemonEngine,SeccompSessionHandler}.kt`
- Test: affected supervisor, contained-executor, and daemon tests

- [ ] For each catch, enumerate the actual exceptions from the operation and catch those types only.
- [ ] Replace “log and continue” branches with typed `InstallResult`/`SessionOutcome` values that preserve errno/cause and force an exhaustive caller decision.
- [ ] At the notification edge, factor one private fail-closed reply helper; if a final defensive catch remains, make it one of the allowlist candidates with a rationale that states the reply is denial/abort.
- [ ] Split installer orchestration into preload, kernel-install, descriptor handoff, and registry-publication steps so complexity suppressions disappear instead of moving.

**Verification:**

```bash
./gradlew :enforcer:test --tests '*Supervisor*'
./gradlew :platform:test --tests '*Seccomp*Daemon*'
./gradlew :enforcer:integrationTestFreshJvm
```

### Task 3: Replace unchecked casts with typed ownership and protocol boundaries

**Files:**
- Modify: `platform/.../core/FileDescriptor.kt`
- Modify: `platform/.../LinuxNative.kt`, `platform/.../ffi/LayoutValidator.kt`
- Modify: `enforcer/.../{Policy,PolicyLists,PolicyDefinition,PolicyCompilationCache,JavaPolicyBuilder}.kt`
- Modify: `portal/.../Portal.kt`, `portal-worker/.../PortalBuiltinDispatch.kt`
- Modify: profiler transport/handshake/ledger files currently suppressing casts
- Test: FD ownership, policy decode, portal dispatch, and profiler protocol tests

- [ ] Replace erased `as` operations with sealed protocol messages, generic factories with reified validation, or an internal checked-cast adapter returning a typed failure.
- [ ] Make FD ownership/state part of the type at every handoff; keep raw `Int` only at syscall ABI boundaries.
- [ ] Decode numeric portal/profiler IDs once at the boundary into sealed types and make dispatch exhaustive.
- [ ] Where an FFM bridge cast is irreducible, isolate it in a private adapter with a runtime check and propose it for the narrow allowlist.

**Verification:**

```bash
./gradlew :platform:test :portal:test :portal-worker:test :profiler:test
rg -n '@Suppress\\("UNCHECKED_CAST"' --glob '*/src/main/**/*.kt'
```

### Task 4: Eliminate structural-rule suppressions by extracting named units

**Files:**
- Modify: `platform/.../{LinuxNative,RealNativeEngine,ContextId,PrctlCommand,valueClasses}.kt`
- Modify: `enforcer/.../{PureJavaBpfEngine,FilterInstallationPlanner,LandlockState}.kt`
- Modify: profiler `Profiler`, `IterativeProfiler`, daemon/transport/triage/listener files
- Modify: portal worker main and broker code
- Test: unit tests colocated with each extraction

- [ ] Replace `MagicNumber` suppressions with named constants that identify Linux ABI fields, bit widths, protocol limits, or domain defaults.
- [ ] Split long/complex methods into pure parsing/planning functions and side-effect interpreters; use sealed outcomes rather than return-count suppressions.
- [ ] Split oversized native and policy classes by responsibility without widening public APIs.
- [ ] Reject `TooManyFunctions`, `CyclomaticComplexMethod`, `LongMethod`, `ReturnCount`, `ThrowsCount`, and `LongParameterList` suppressions rather than raising limits.

**Verification:**

```bash
./gradlew :platform:test :enforcer:test :profiler:test :portal:test
./gradlew detekt
```

### Task 5: Clean test/demo suppressions without weakening production rules

**Files:**
- Modify: Kotlin tests and demo sources identified by the inventory
- Modify: `config/detekt/detekt.yml` test-source exclusions only where tests deliberately exercise invalid input
- Create: shared test fixtures for intentional unchecked generic test data and expected failures

- [ ] Replace repeated test casts/catches with typed fixtures and assertion helpers.
- [ ] Keep only intentional-invalid-input test suppressions, each next to a comment naming the test condition; do not exempt an entire production rule globally.
- [ ] Fix the three CLI demo exception assertions against the intentional `ContainmentViolationException` API contract, including cause/evidence assertions.
- [ ] Reproduce the portal-codegen child-JVM timeout in isolation, determine whether it is resource contention or a crash, and fix it without suppressing the test.
- [ ] Run Detekt with debug to identify and fix its compiler-analysis error; do not downgrade it or suppress the reporting task.

**Verification:**

```bash
./gradlew :demos:cli-demo:test :portal-codegen:test :enforcer:detektMain --no-configuration-cache
./scripts/check_suppressions.sh
```

### Task 6: Audit retained exceptions and run delivery verification

**Files:**
- Modify: `config/detekt/approved-suppressions.txt`
- Modify: each retained source site only to add the required rationale
- Test: suppression-gate test and full build

- [ ] Review every remaining production suppression against the five retention categories; remove it if the rationale says only “Detekt false positive,” “legacy,” or “cleanup.”
- [ ] Confirm the final allowlist has at most twelve entries and no generic catch/swallowed/cast category beyond the explicitly reviewed boundary cases.
- [ ] Verify no suppression sits on a public class, file, or broad function scope when a single expression/helper can hold it.
- [ ] Run the full build from a clean Gradle daemon state and attach the exact output/status to the delivery record.

**Verification:**

```bash
./scripts/check_suppressions.sh
./gradlew build --console=plain --no-configuration-cache
git diff --check
git status --short
```

## Definition of done

- Production suppressions are reduced from 105 to at most 12, all in the checked-in allowlist with adjacent invariant-specific rationale comments.
- No production generic catch, swallowed failure, unchecked cast, complexity, count, or broad class/file suppression remains without an approved boundary justification.
- The three current full-build failures are fixed as behaviorally correct defects, not skipped, retried, or suppressed.
- Focused security/kernel tests and `./gradlew build --console=plain --no-configuration-cache` pass.
