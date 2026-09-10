---
document_type: execution_plan
title: "Security-State Closure Implementation Plan"
base_revision: 457ac244
---

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

- [x] Add a failing test proving an unmapped number is not authorized as `OPEN`.
- [x] Add an internal sealed resolver and emit an `EPERM` verdict for `Unknown`.
- [x] Run focused enforcer tests and commit.

### Task 2: Scope supervisor thread lookup to a session

**Files:** `SupervisorInstaller.kt`, `SupervisorSession.kt`, new runtime-context test.

- [x] Add a failing isolation/close-unregister test.
- [x] Introduce `SupervisorRuntimeContext` and thread it into the listener/session.
- [x] Run focused enforcer tests and commit.

### Task 3: Centralize supervisor reply interpretation

**Files:** `SupervisorSessionHandler.kt`, new internal reply-decision type and matrix test.

- [x] Add a failing test covering decision-to-reply mapping and terminal reply failure.
- [x] Convert terminal route helpers to sealed decisions and retain one reply interpreter.
- [x] Run focused enforcer tests and commit.

### Task 4: Preserve Landlock capability failures

**Files:** `Landlock.kt`, new `LandlockCapabilityProbe.kt`, probe tests.

- [x] Add failing tests for supported, unsupported, and permission-denied probe results.
- [x] Isolate pure classification and prevent fallback on permission/error results.
- [x] Run focused enforcer tests and commit.

### Task 5: Make seccomp connection cleanup observable

**Files:** `SeccompConnection.kt`, `SeccompConnectionTest.kt`.

- [x] Add failing tests for close failure visibility and retry.
- [x] Add typed close outcomes, retry-safe descriptor ownership, and write-once socket-manager injection.
- [x] Run focused platform tests and commit.

### Task 6: Freeze portal and Tier-E runtime stores

**Files:** `PortalDispatcherRegistry.kt`, worker tests, Tier-E collector and tests.

- [x] Add snapshot coverage for event/drop completeness.
- [x] Freeze strict portal startup dispatch maps and make collector drain snapshots atomic.
- [x] Run portal-worker and profiler tests and commit.

### Task 7: Fingerprint quality and management evidence

**Files:** root `build.gradle.kts`, backlog validation/index support, focused Gradle tests or scripts.

- [x] Replace count-only budgets with per-finding fingerprints.
- [x] Validate a single backlog total source.
- [x] Run relevant checks and commit.
