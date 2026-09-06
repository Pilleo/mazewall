# Supervisor, Coverage, and ArgCheck Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Complete the supervisor route split, replace hollow coverage tests with behavioral tests, and add deterministic simulator-versus-kernel ArgCheck fuzzing.

**Architecture:** Keep supervisor route decisions pure and move execution behind route-context objects with exactly one seccomp response. Replace tests that only execute code with assertions over native delegation, BPF output, lifecycle state, and executor behavior. Add fuzz cases to the established worker-thread differential infrastructure with reproducible seeds.

**Tech Stack:** Kotlin, JUnit 5, Gradle, Seccomp-BPF, mocked `NativeEngine` and kernel integration tests.

**Spec:** Backlog issues `issue-20260826-102722`, `issue-20260826-180103`, and `issue-20260824-011658`.

## Global Constraints

- Preserve fail-closed behavior and exactly-one seccomp response per supervisor notification.
- Do not modify Tier E, iterative profiler, orchestrator, or capability token/read-write work.
- Do not alter the user-owned diagram changes.
- Use deterministic synchronization; never add sleeps or warmups.
- Run focused verification before each commit and the appropriate full suite before archival.

---

### Task 1: Complete supervisor route extraction

**Files:**
- Modify: `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt`
- Create: route-specific files in `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/`
- Test: `enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandlerTest.kt`

- [ ] Move InjectFd and SecureExec execution behind route-context handlers.
- [ ] Reduce the session handler to parse, classify, dispatch, and response framing.
- [ ] Extend route enumeration to verify one response or one route delegation for every route.
- [ ] Run `./gradlew :enforcer:test --tests io.mazewall.enforcer.supervisor.SupervisorSessionHandlerTest`.
- [ ] Run `./gradlew :enforcer:test` and commit the extraction.

### Task 2: Replace coverage theater

**Files:**
- Modify: `BpfBuilderCoverageTest.kt`, `LinuxNativeCoverageTest.kt`, `LandlockCoverageTest.kt`, `SandboxDispatcherCoverageTest.kt`, and `ProfilerCoverageTest.kt`
- Test: their corresponding focused Gradle tasks

- [x] Replace BPF label, native delegation, profiler wrapper, Landlock TSYNC, and dispatcher shutdown probes with behavioral assertions.
- [ ] Inspect remaining filler tests named by the issue, delete only tests whose behavior is covered, and rename surviving coverage tests to domain names.
- [ ] Run each focused test class, then `./scripts/check_coverage.sh`.
- [ ] Run `./gradlew :enforcer:test :profiler:test`, kernel tests if required, and commit/archive the issue.

### Task 3: Add deterministic ArgCheck differential fuzzing

**Files:**
- Modify: existing `SeccompDifferentialVerdictTest` infrastructure under `enforcer/src/integrationTest/kotlin/io/mazewall/seccomp`
- Test: `./gradlew integrationTest`

- [ ] Confirm the resolved forked-child probe infrastructure remains usable for worker-thread verdict testing.
- [ ] Add fixed seeds and boundary vectors for zero, all-ones, sign-extended negatives, `INT_MIN`, `INT_MAX`, and high-word garbage.
- [ ] Generate `EqualsAny32`, `MaskEquals`, and `EqualsAny` programs and assert simulator/kernel parity while skipping `KILL_*` verdicts.
- [ ] Include seed, program, and arguments in mismatch failures.
- [ ] Run focused differential tests, `./gradlew integrationTest`, then commit/archive the issue.
