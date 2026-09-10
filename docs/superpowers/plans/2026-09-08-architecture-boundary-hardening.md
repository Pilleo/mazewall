---
document_type: execution_plan
title: "Architecture Boundary Hardening Implementation Plan"
base_revision: 35a93262
---

# Architecture Boundary Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enforce profiler native boundaries, explicit protocol branches,
typed interpreter outcomes, reliable containment diagnostics, ABI checks, and
accurate architectural backlog records.

**Architecture:** Keep reducers pure and domain-specific. Put native memory
and syscalls behind profiler/platform FFI adapters; represent protocol and
diagnostic facts with sealed value types; prove boundaries with focused unit
and architecture tests.

**Tech Stack:** Kotlin/JDK FFM, Gradle, JUnit 5, ArchUnit, existing native
test fixtures.

**Spec:** `docs/superpowers/specs/2026-09-08-architecture-boundary-hardening-design.md`

## Global Constraints

- Preserve source/binary/native/wire compatibility and fail-closed behavior.
- Add no dependencies or generic state-machine framework.
- Keep raw FFM inside FFI packages using confined arenas and immediate errno capture.
- Keep Landlock-before-Seccomp and exactly-one USER_NOTIF terminal reply.

---

### Task 1: Profiler native-boundary isolation

**Files:** profiler FFI adapters, profiler engine/ring-buffer callers, and
`ProfilerArchitectureTest`.

- [ ] Write failing architecture tests that reject `java.lang.foreign` outside profiler FFI packages.
- [ ] Run the focused architecture test and observe the current violations.
- [ ] Move raw-memory helpers behind FFI APIs returning heap-backed values; keep arena ownership local.
- [ ] Run profiler focused tests and `:profiler:test`.
- [ ] Commit the isolated boundary.

### Task 2: Profiler protocol branches and raw verdict parsing

**Files:** profiler session machine/handler/tests; enforcer notification
machine, channel, architecture test, and tests.

- [ ] Write failing tests for ACK-required published events, ACK-free suppressed noise, and unknown verdict rejection.
- [ ] Run each focused test and observe the intended failure.
- [ ] Add explicit typed protocol outcomes and fail-closed unknown parsing.
- [ ] Run profiler/enforcer focused tests and module gates.
- [ ] Commit the protocol changes.

### Task 3: Typed interpreter commands

**Files:** portal worker machine/main/tests and supervisor machine/handler/tests.

- [ ] Write failing tests that exercise only legal command variants at each interpreter boundary.
- [ ] Run the focused tests and observe runtime-guard/cast behavior.
- [ ] Replace broad effect interpretation with sealed, phase-specific commands.
- [ ] Run portal-worker and enforcer module tests.
- [ ] Commit the typed transition contracts.

### Task 4: Diagnostic provenance

**Files:** containment exception API/detector/wrapper/tests and public API docs.

- [ ] Write failing tests for nested structured evidence, false-positive heuristics, suppressed/cyclic causes, and metadata preservation.
- [ ] Run focused detector/wrapper tests and observe the failure.
- [ ] Implement provenance, graph-safe extraction, and compatible exception normalization.
- [ ] Run enforcer tests and API checks.
- [ ] Commit diagnostics and documentation.

### Task 5: ABI conformance and backlog reconciliation

**Files:** platform ABI fixtures/tests and relevant backlog records.

- [ ] Write failing ABI checks for selected native layout sizes/offsets and a source-level regression for shared descriptor streams.
- [ ] Run focused platform tests and observe failures where coverage is absent.
- [ ] Add the smallest portable probe/fixture coverage; close only demonstrated-stale backlog records and correct ACK wording.
- [ ] Run platform tests, backlog validation, and docs checks.
- [ ] Commit ABI evidence and backlog reconciliation.

### Task 6: Delivery verification

**Files:** only files changed by Tasks 1-5.

- [ ] Run focused module gates, fresh-JVM USER_NOTIF tests where applicable, and architecture/Detekt checks.
- [ ] Run `./gradlew --no-daemon --console=plain --quiet build`.
- [ ] Inspect `git diff --check` and the scoped diff.
- [ ] Commit any final verification/documentation fixes.
