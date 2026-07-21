---
title: "Decouple NativeEngine Interface and LinuxNative Entry Point from Raw FFM Types"
severity: "HIGH"
status: "open"
priority: 10
dependencies: ["issue-209"]
component: "enforcer"
effort: "medium"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
---

# 🔴 [Severity: HIGH]: Decouple NativeEngine Interface and LinuxNative Entry Point from Raw FFM Types

**Context:**
The `NativeEngine` interface and `LinuxNative` entry point in package `io.mazewall` leak raw `java.lang.foreign.MemorySegment` types in their method signatures (e.g. `raw.ioctl`, `raw.poll`, `fileSystem.open`, `networking.bind`). This forces mock objects (`MockNativeEngine`) and domain callers (`SupervisorSessionHandler`, `Landlock`, profiler) to depend directly on `java.lang.foreign`, breaking the `io.mazewall.ffi` isolation boundary at the core API layer.

**Needed:**
1. Update `NativeEngine` and `RawSyscallOperations` method signatures to accept `ManagedSegment` instead of raw `MemorySegment`.
2. Extract the `RealNativeEngine` implementation out of `LinuxNative.kt` into `io.mazewall.ffi.internal.RealNativeEngine`.
3. Update the `rawSyscallOperationsMustOnlyBeUsedByAllowedPackages` ArchUnit rule in `ArchitectureTest.kt` to allow `io.mazewall.ffi.internal`.
4. Update `MockNativeEngine` to implement the updated `NativeEngine` interface using `ManagedSegment`.

## Solution Options

### Option A — Interface Refactoring & RealNativeEngine Extraction
Update `NativeEngine.kt` to accept `ManagedSegment` parameters exclusively. Move `RealNativeEngine` to `io.mazewall.ffi.internal.RealNativeEngine`.
**Pros:** Removes raw FFM dependencies from public API contracts and reduces `LinuxNative.kt` from 1000+ lines down to ~240 lines.
**Cons:** Requires updating `MockNativeEngine` and all test callsites that pass mock segments.
**Risk:** MEDIUM
**Effort:** medium

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ] `NativeEngine.kt` contains zero `java.lang.foreign.*` imports.
- [ ] `RealNativeEngine` resides in `io.mazewall.ffi.internal`.
- [ ] `ArchitectureTest.kt` passes `rawSyscallOperationsMustOnlyBeUsedByAllowedPackages`.
- [ ] `:enforcer:compileKotlin` and `:profiler:compileKotlin` build successfully.

**Implementation Hints:**
- Refer to `NativeEngine.kt` diff in branch `issue-177-ffm-isolation-8465021830414465854`.
