---
title: "Pure FFI Wrapper Type System in io.mazewall.ffi.memory"
severity: "HIGH"
status: "resolved"
priority: 10
dependencies: []
component: "ffi"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "low"
reversible: true
github_issue: 133
---

# 🔴 [Severity: HIGH]: Pure FFI Wrapper Type System in io.mazewall.ffi.memory

**Context:**
To enforce FFM isolation (`java.lang.foreign` isolation to `io.mazewall.ffi`), domain logic across `:enforcer` and `:profiler` must interact with memory via pure, type-safe wrappers rather than exposing raw FFM abstractions like `Arena` and `MemorySegment`. Currently, `io.mazewall.ffi.memory` lacks standard opaque wrappers for arenas, explicit segment ownership interfaces (`ConfinedSegment`, `SharedSegment`), and encapsulated byte array copying.

**Needed:**
1. Implement `NativeArena` as an opaque wrapper around `java.lang.foreign.Arena` in `io.mazewall.ffi.memory`.
2. Implement the `ManagedSegment` interface along with `ConfinedSegment` and `SharedSegment` value classes in `io.mazewall.ffi.memory`.
3. Implement `ManagedSegment.copy` companion methods to encapsulate byte array copying without exposing `MemorySegment.copy`.
4. Add comprehensive unit tests in `enforcer/src/test/kotlin/io/mazewall/ffi/memory/` to verify all wrappers in isolation.

## Solution Options

### Option A — Value-Class Encapsulation
Define `ManagedSegment` as a sealed interface backed by `@JvmInline value class ConfinedSegment(override val native: MemorySegment)` and `SharedSegment(override val native: MemorySegment)`.
**Pros:** Zero-allocation runtime overhead in Kotlin while keeping FFM memory segments hidden behind `io.mazewall.ffi`.
**Cons:** Requires `native` property to be internal to `io.mazewall.ffi`.
**Risk:** LOW
**Effort:** small

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ] `NativeArena` and `ManagedSegment` compile cleanly in `:enforcer`.
- [ ] Unit tests in `ManagedSegmentTest` and `NativeArenaTest` pass.
- [ ] No changes to domain code outside `io.mazewall.ffi`.

**Implementation Hints:**
- Code can be extracted directly from the salvageable parts of branch `issue-177-ffm-isolation-8465021830414465854`.
