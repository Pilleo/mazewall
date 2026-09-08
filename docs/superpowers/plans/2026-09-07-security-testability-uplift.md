# Security Testability Uplift Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Raise meaningful host-unit coverage and confidence by moving coverage to its owning module and extracting pure security decisions from native side effects; do not add assertion-free or kernel-dependent unit tests merely to improve a metric.

**Architecture:** Keep syscalls, FFM allocation, sockets, and process containment in thin executors. Move deterministic selection, validation, and request-construction logic into internal, immutable collaborators that receive values and return data/effects. Test those collaborators as readable decision tables; retain a small number of executor wiring tests plus the existing kernel integration gate.

**Tech Stack:** Kotlin/JDK 22+, JUnit 5, Kotest property tests where they express an invariant better than a table, JaCoCo, Gradle `unitCheck`, and the existing integration-test tasks.

**Spec:** [Testing Strategy](../../internals/designs/core/testing-strategy.md)

## Global Constraints

- Preserve fail-closed containment semantics; an execution failure must never become a warning or an allow decision.
- Keep FFM layouts and syscall invocation behind the native boundary. Pure planners/encoders must not call `LinuxNative` or allocate a native resource unless their explicit contract is encoding into a caller-owned arena.
- Host-unit tests must run without seccomp, Landlock, USER_NOTIF, root, a particular kernel, or timing assumptions. Kernel behavior remains under `integrationTest`/`integrationTestFreshJvm`.
- Do not alter public APIs or add dependencies. Use internal types and existing constructor seams.
- A coverage threshold rises only after its report is attributable to tests owned by that module and the new tests assert observable contracts.

---

## Baseline and success measures

The current host-unit report is approximately 82% (`:enforcer`), 53% (`:platform`), and 70% (`:profiler`) instruction coverage. The platform figure is understated because tests for platform production classes currently execute in `:enforcer`, whose JaCoCo execution data does not count toward `:platform`'s report.

Success is measured by:

1. platform-owned logic being covered by `:platform:test` and visible in its report;
2. exhaustive unit matrices for the extracted pure contracts, including invalid input and fail-closed branches;
3. unchanged kernel-test ownership and no host-unit dependency on a real Linux containment feature;
4. a justified, incremental increase to the affected module floor after the report has stabilized across clean runs.

## Task 1: Correct module-level coverage attribution

**Files:**
- Move `enforcer/src/test/kotlin/io/mazewall/SyscallResultTest.kt` to `platform/src/test/kotlin/io/mazewall/core/ErrnoMappingTest.kt`
- Move `enforcer/src/test/kotlin/io/mazewall/ffi/networking/SupervisorSocketUtilsTest.kt` to `platform/src/test/kotlin/io/mazewall/ffi/networking/SupervisorSocketUtilsTest.kt`
- Move `enforcer/src/test/kotlin/io/mazewall/platform/seccomp/daemon/SeccompDaemonEngineTest.kt` to `platform/src/test/kotlin/io/mazewall/platform/seccomp/daemon/SeccompDaemonEngineTest.kt`
- Update only the affected package imports and module test dependencies.

- [x] Establish a red proof that each selected test can compile and run from `:platform` without an `:enforcer` production dependency.
- [x] Move tests with their exact behavioral assertions; do not weaken them while relocating.
- [ ] Add the missing platform-only fixtures through `sharedTest` only if two or more modules genuinely use the same fixture contract. Otherwise keep fixtures private to `:platform`.
- [x] Run `./gradlew :platform:test --tests <moved test class>` and verify that the platform JaCoCo XML records the affected production class as covered.
- [x] Run `./gradlew unitCheck --no-daemon --no-configuration-cache --max-workers=1 --console=plain`.

## Task 2: Extract the install self-verification probe plan

**Files:**
- Modify `enforcer/src/main/kotlin/io/mazewall/seccomp/InstallSelfVerifier.kt`
- Add `enforcer/src/main/kotlin/io/mazewall/seccomp/SelfVerificationPlan.kt`
- Add `enforcer/src/test/kotlin/io/mazewall/seccomp/SelfVerificationPlanTest.kt`
- Retain or narrowly extend `InstallSelfVerifierTest.kt` for executor wiring.

- [x] Write a failing table-driven unit test for a compiled program with ALLOW, errno-deny, KILL, and argument-inspected cases. Assert which probes are planned, which are excluded, oracle expectations, and stable diagnostic labels.
- [x] Introduce an internal immutable plan/result model that derives the bounded non-stacked probe list from program and architecture; it has no syscall, cache mutation, or event publication. Union-aware planning remains in the follow-up issue because it requires an explicit merged-state contract.
- [x] Reduce the non-stacked `InstallSelfVerifier` path to: construct plan, execute raw probes, compare actual versus planned outcome, publish the existing diagnostic, and memoize **only after all probes pass**.
- [ ] Add focused executor tests proving a mismatch fails closed and a failed run is not memoized. Do not simulate a kernel in the pure-plan tests.
- [ ] Run focused `:enforcer:test`, then `unitCheck`; run the existing self-verification integration coverage only if the executor contract changes.

## Task 3: Extract the Tier-E BPF program-load request encoder

**Files:**
- Modify `profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEbpfEngine.kt`
- Add `profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/BpfProgLoadRequestEncoder.kt`
- Add `profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/BpfProgLoadRequestEncoderTest.kt`
- Keep `BpfProgLoadLayoutTest.kt` as ABI-layout coverage, not a duplicate encoder test.

- [x] Write failing tests that inspect an encoded `bpf_attr` in a caller-owned confined arena: instruction count, expected program type, pseudo-map-FD replacement, and program name.
- [ ] Add invalid-input cases for overlong names, missing pseudo-map FD, and malformed instruction payload. Assert a deterministic exception before a syscall is possible.
- [x] Extract the encoder as an internal collaborator; `TierEbpfEngine` owns arena lifecycle, calls the encoder, performs `bpf`, captures errno immediately, and translates the kernel result.
- [ ] Preserve every layout width and pointer field exactly; apply the FFM-safety checklist during implementation.
- [x] Run focused `:profiler:test`, the layout test, and `unitCheck`. Run Tier-E/kernel verification separately where the environment supports it.

## Task 4: Make syscall-number resolution immutable and independently testable

**Files:**
- Modify `platform/src/main/kotlin/io/mazewall/core/Syscall.kt`
- Add `platform/src/main/kotlin/io/mazewall/core/SyscallNumberResolver.kt`
- Add `platform/src/test/kotlin/io/mazewall/core/SyscallNumberResolverTest.kt`

- [x] Characterize current mapping results across the supported architectures before moving logic.
- [x] Confirm `SyscallMapper` already is the internal immutable architecture-to-number resolver; move its exhaustive specification into `:platform` rather than introducing a redundant wrapper.
- [ ] Test the cross-product of supported architecture families and representative filesystem, network, process, and unsupported syscalls as a named table/spec.
- [ ] Add a property/invariant test only if it captures a real rule (for example, aliases resolve identically); do not enumerate maps solely for line coverage.
- [x] Run focused `:platform:test`, inspect JaCoCo coverage for the resolver, then `unitCheck`.

## Task 5: Raise gates only with evidence and document residual native coverage

**Files:**
- Modify `build.gradle.kts` only if a new floor is demonstrated stable
- Update `docs/internals/designs/core/testing-strategy.md`

- [x] Run host reports from a clean execution-data state and record per-module instruction coverage before/after the changes.
- [x] Leave floors unchanged pending a repeatable lower-bound measurement; do not use broad exclusions to obtain the result.
- [ ] Document remaining uncovered native transports/executables, their kernel-gate owner, and the specific seam required before they become unit-testable.
- [x] Verify `./gradlew unitCheck --no-daemon --no-configuration-cache --max-workers=1 --console=plain`; run `integrationTest --dry-run` to preserve the CI split.

## Review checklist

- [ ] Inspect each test name: it states a security/business rule rather than an implementation detail.
- [ ] Inspect every error path: invalid encoding/planning input and runtime mismatch remain fail-closed.
- [ ] Confirm no pure unit test loads a native library, installs seccomp/Landlock, opens a real UNIX socket, or relies on host kernel capabilities.
- [ ] Confirm moved tests are counted in the owning module report and no test code was duplicated across modules.
- [ ] Run `git diff --check` and scan the diff for test-only production bypasses, silent fallbacks, and broad coverage exclusions.
