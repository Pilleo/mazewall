---
title: "Encapsulate Landlock and Seccomp C-String & Struct Allocations"
severity: "HIGH"
status: "resolved"
priority: 10
dependencies: ["issue-209", "issue-210"]
component: "landlock"
effort: "medium"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
github_issue: 137
---

# 🔴 [Severity: HIGH]: Encapsulate Landlock and Seccomp C-String & Struct Allocations

**Context:**
`Landlock.kt` and `BpfFilter.kt` perform inline C-string path allocations (`arena.allocateFrom(path)`), ruleset struct layout slicing (`StructLayout.byteSize()`), and raw `MemorySegment` handling directly within business logic. Furthermore, previous attempts to isolate FFM accidentally stripped critical KDoc documentation explaining ClassLoader safepoint deadlocks and widened inner type visibility unnecessarily.

**Needed:**
1. Provide C-string path opening and BPF program allocation helper methods inside `io.mazewall.ffi.memory` and `io.mazewall.landlock`.
2. Refactor `Landlock.kt` and `BpfFilter.kt` to use `NativeArena` and `ManagedSegment` wrappers exclusively.
3. Preserve all existing KDoc safety documentation (specifically deadlock hazard warnings in `SupervisorSessionHandler` and `Landlock`).
4. Ensure internal types (`AddRuleResult`, `OpenResult`, `handleInitialOpenFailure`) retain `private` visibility instead of exposing `internal`.

## Solution Options

### Option A — Encapsulation via NativeArena and FFI Segment Wrappers
Pass `NativeArena` context to `Landlock` methods and use `ConfinedSegment(arena.arena.allocateFrom(path))` internally within `Landlock` helper methods.
**Pros:** Clean boundary for `Landlock` and `BpfFilter` without leaking `java.lang.foreign` to callers.
**Cons:** Small internal refactoring across `Landlock.kt`.
**Risk:** LOW
**Effort:** medium

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ] `Landlock.kt` and `BpfFilter.kt` contain zero `java.lang.foreign.*` imports.
- [ ] All safety-critical KDocs regarding ClassLoader safepoint deadlocks are fully intact.
- [ ] `:enforcer:check` passes with Jacoco coverage requirements met.
