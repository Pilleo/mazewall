---
document_type: execution_plan
title: "Protocol, Runtime, and Quality Hardening Implementation Plan"
base_revision: 24ba3a0a
---

# Protocol, Runtime, and Quality Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fully cover the seven protocol, runtime, lifecycle, architecture, and quality-hardening improvements.

**Architecture:** Use bounded pure decoders and sealed outcomes at untrusted stream boundaries; preserve socket/native I/O as effect interpretation. Move mutable operation state into internal contexts, capture dependencies atomically, and make shutdown completion explicit. Ratchet analysis on new debt without rewriting unrelated baseline code.

**Tech Stack:** Kotlin/JDK 25, Gradle, JUnit 5, Detekt, SpotBugs, PlantUML.

**Spec:** `docs/superpowers/specs/2026-09-08-protocol-runtime-quality-hardening-design.md`

## Global Constraints

- Exclude `tools/orchestrator`.
- Preserve public APIs, native layouts, portal frames, and broker/worker authority.
- Keep Landlock-before-Seccomp, virtual-thread rejection, and fail-closed containment.
- Add no dependency or generic state-machine framework.
- Write a focused failing test before production changes and commit each independently verified work item.

---

### Task 1: Bound supervisor validation frames and reject invalid handshakes

**Files:**
- Modify: `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorInstaller.kt`
- Create: `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorValidationDecoder.kt`
- Test: `enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisorValidationDecoderTest.kt`

- [x] Add failing decoder tests for negative/oversize lengths and unknown argument tags.
- [x] Add a pure bounded decoder and terminal invalid-handshake behavior.
- [x] Run `./gradlew --no-daemon :enforcer:test --tests '*SupervisorValidation*'`.
- [x] Commit the focused change (`da4e0a9e`).

### Task 2: Bound profiler trace-event frames and typed command outcomes

**Files:**
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerTraceListener.kt`
- Create: `profiler/src/main/kotlin/io/mazewall/profiler/internal/TraceEventDecoder.kt`
- Test: `profiler/src/test/kotlin/io/mazewall/profiler/internal/TraceEventDecoderTest.kt`

- [x] Add failing malformed-frame and command-failure tests.
- [x] Add pure bounded decoder and sealed command-write outcome.
- [x] Run `./gradlew --no-daemon :profiler:test --tests '*Trace*'`.
- [x] Commit the focused change (`6e143248`).

### Task 3: Scope profiler and supervisor runtime state

**Files:**
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/Profiler.kt`
- Modify: `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorInstaller.kt`
- Test: module runtime/session tests

- [x] Add failing isolation tests for parallel or nested session state.
- [x] Introduce internal contexts while preserving singleton facade APIs.
- [x] Run focused module tests and commit (`c95f0d19`).

### Task 4: Capture native snapshots and generation-qualified FD audit identity

**Files:**
- Modify: `platform/src/main/kotlin/io/mazewall/LinuxNative.kt`
- Modify: `platform/src/main/kotlin/io/mazewall/core/FileDescriptor.kt`
- Test: `platform/src/test/kotlin/io/mazewall/core/FileDescriptorTest.kt`

- [x] Add failing engine-snapshot and FD-reuse audit tests.
- [x] Implement lexical snapshot access and generation-qualified audit entries.
- [x] Run focused platform tests and commit (`3c4f28a1`).

### Task 5: Make daemon/listener shutdown completion explicit

**Files:**
- Modify: `platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompDaemonEngine.kt`
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerTraceListener.kt`
- Test: daemon and listener lifecycle tests

- [x] Add failing tests for worker drain timeout and interrupt ordering.
- [x] Add typed completion outcomes and monotonic deadlines.
- [x] Run focused platform tests and commit (`94f1c2c0`).

### Task 6: Extract pure validation and worker-dispatch interpreters

**Files:**
- Modify: supervisor validation and portal worker adapters
- Create: focused pure evaluator/interpreter files as required
- Test: evaluator and worker-machine matrices

- [x] Add failing matrix tests for outcomes and effects.
- [x] Move decisions from I/O loops into pure sealed-outcome components.
- [x] Run focused portal-worker tests and commit (`cb280f0a`).

### Task 7: Add deterministic new-debt quality gates

**Files:**
- Modify: root Gradle quality configuration and diagram tasks
- Test: Gradle functional/configuration assertions where available

- [x] Add a failing configuration check that detects generated-file drift.
- [x] Implement a deterministic warning ratchet without failing existing reviewed warnings.
- [x] Run relevant checks and the module build gate (orchestrator excluded).
