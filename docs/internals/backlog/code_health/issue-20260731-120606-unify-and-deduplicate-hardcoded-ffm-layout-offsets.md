---
title: "Unify and Deduplicate Hardcoded FFM Layout Offsets Across Profiler and Enforcer"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerConstants.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
effort: "medium"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: Unify and Deduplicate Hardcoded FFM Layout Offsets Across Profiler and Enforcer

**Context:**
The `:enforcer`'s `SupervisorSessionHandler.kt` and `:profiler`'s `ProfilerConstants.kt` both contain hardcoded byte offsets for struct fields of critical seccomp-related kernel structures:
- `NOTIF_ID_OFF = 0L`
- `NOTIF_PID_OFF = 8L`
- `NOTIF_NR_OFF = 16L`
- `NOTIF_ARGS_OFF = 32L`
- `RESP_ID_OFF = 0L`
- `RESP_VAL_OFF = 8L`
- `RESP_ERR_OFF = 16L`
- `RESP_FLAGS_OFF = 20L`

These offsets match the C structures `seccomp_notif` and `seccomp_notif_resp`. However, having hardcoded constants duplicated across modules violates the "DRY" principle and introduces severe ABI drift risk. If the central FFM layouts (e.g. `Layouts.SECCOMP_NOTIF` or `Layouts.SECCOMP_NOTIF_RESP` in `enforcer/src/main/kotlin/io/mazewall/ffi/Layouts.kt`) are updated, modified, or aligned differently for new kernel versions or foreign architectures (e.g., aarch64), these hardcoded long offsets will not be updated automatically, leading to silent memory corruption or incorrect parsing.

**Needed:**
1. Remove all hardcoded offsets (`NOTIF_ID_OFF`, `NOTIF_PID_OFF`, etc.) from both `SupervisorSessionHandler.kt` and `ProfilerConstants.kt`.
2. Retrieve the offsets dynamically at runtime by querying the central `Layouts` definitions in `io.mazewall.ffi.Layouts` using FFM's `.byteOffset()` API:
   - e.g., `val NOTIF_ID_OFF = Layouts.SECCOMP_NOTIF.byteOffset(MemoryLayout.PathElement.groupElement("id"))`
3. If necessary, expose these offsets cleanly from a centralized, shared metadata object within `io.mazewall.ffi` so that both the enforcer supervisor and the profiler daemon retrieve them from the exact same source of truth.

**Verification/Regression Tests:**
- Add an ArchUnit test or a static unit test that asserts that all dynamically queried offsets match the standard 64-bit Linux x86_64 and aarch64 seccomp ABI offsets exactly.
- Run `./gradlew :enforcer:test :profiler:test` to verify that all seccomp user-notifier handshakes compile and function correctly.
