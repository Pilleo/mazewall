# Security-State Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the seven remaining supervisor, Landlock, lifecycle, runtime-state, and verification gaps.

**Architecture:** Pure resolvers classify untrusted/security-relevant input into sealed outcomes. Stateful I/O adapters own resources and interpret those outcomes once. Runtime configuration is frozen before use and verification compares individual findings rather than aggregate counts.

**Tech Stack:** Kotlin/JDK 25, Gradle, JUnit 5, Detekt.

**Spec:** `docs/superpowers/specs/2026-09-09-security-state-closure-design.md`

## Global Constraints

- Exclude `tools/orchestrator`.
- Preserve public policy APIs, portal wire format, native layouts, and broker/worker authority.
- Fail closed for unknown syscall, malformed protocol, `EPERM`, and `EACCES`.
- Preserve Landlock-before-Seccomp and never close an unowned descriptor.
- Add a focused failing test before each production implementation and commit each task independently.

### Task 1: Resolve unknown supervisor syscalls fail closed

**Files:** `SupervisorInstaller.kt`, new resolver test.

- [ ] Add a failing test proving an unmapped number is not authorized as `OPEN`.
- [ ] Add an internal sealed resolver and emit an `EPERM` verdict for `Unknown`.
- [ ] Run focused enforcer tests and commit.

### Task 2: Scope supervisor thread lookup to a session

**Files:** `SupervisorInstaller.kt`, `SupervisorSession.kt`, new runtime-context test.

- [ ] Add a failing isolation/close-unregister test.
- [ ] Introduce `SupervisorRuntimeContext` and thread it into the listener/session.
- [ ] Run focused enforcer tests and commit.

### Task 3: Centralize supervisor reply interpretation

**Files:** `SupervisorSessionHandler.kt`, new internal reply-decision type and matrix test.

- [ ] Add a failing test covering decision-to-reply mapping and terminal reply failure.
- [ ] Convert route helpers to return sealed decisions and retain one reply interpreter.
- [ ] Run focused enforcer tests and commit.

### Task 4: Preserve Landlock capability failures

**Files:** `Landlock.kt`, new `LandlockCapabilityProbe.kt`, probe tests.

- [ ] Add failing tests for supported, unsupported, and permission-denied probe results.
- [ ] Isolate pure classification and prevent fallback on permission/error results.
- [ ] Run focused enforcer tests and commit.

### Task 5: Make seccomp connection cleanup observable

**Files:** `SeccompConnection.kt`, `SeccompConnectionTest.kt`.

- [ ] Add failing tests for close failure visibility and retry.
- [ ] Add typed close outcomes and immutable socket-manager injection.
- [ ] Run focused platform tests and commit.

### Task 6: Freeze portal and Tier-E runtime stores

**Files:** `PortalDispatcherRegistry.kt`, worker tests, Tier-E collector and tests.

- [ ] Add failing duplicate-ID and concurrent-drain/drop tests.
- [ ] Freeze portal dispatch maps and make collector drain snapshots atomic.
- [ ] Run portal-worker and profiler tests and commit.

### Task 7: Fingerprint quality and management evidence

**Files:** root `build.gradle.kts`, backlog validation/index support, focused Gradle tests or scripts.

- [ ] Add a failing fixture where an equal-count replacement warning is rejected.
- [ ] Replace count-only budgets with finding fingerprints and validate a single backlog total.
- [ ] Run relevant checks, the in-scope module build gate, and commit.
