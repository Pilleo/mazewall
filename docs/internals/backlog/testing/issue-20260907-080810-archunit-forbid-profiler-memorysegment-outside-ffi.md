---
title: "ArchUnit-forbid profiler MemorySegment outside ffi"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/test/kotlin/io/mazewall/profiler/ProfilerArchitectureTest.kt"
target_symbols:
  - "ProfilerArchitectureTest"
verify_cheap:
  - "./gradlew :profiler:test --tests io.mazewall.profiler.ProfilerArchitectureTest"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
has_side_effects: true

paperclip_issue_id: "d2b4de15-c3e6-4022-a064-995f7b033acb"
paperclip_identifier: "MAZ-1179"
---

# 🔴 [Severity: HIGH]: ArchUnit-forbid profiler MemorySegment outside ffi

**Context:**
Enforcer ArchitectureTest already forbids `java.lang.foreign` outside `io.mazewall.ffi`. Profiler production handshake and BPF ring-buffer mapping still use raw `MemorySegment`/`Arena` in `io.mazewall.profiler.engine` and `tierE.ringbuf`. `ProfilerArchitectureTest` only forbids FFM on `TraceEvent`/`SyscallEvent`. Intimate native types are therefore legal in profiler engine code, which is what a later security review has to walk by hand.

**Needed:**
1. Add an ArchUnit rule in `ProfilerArchitectureTest`: production classes outside an allowed ffi/native package must not depend on `java.lang.foreign..`.
2. Move handshake poll/read/write/recv/ioctl and ringbuf mapping behind `NativeEngine` / `io.mazewall.ffi` (or `io.mazewall.profiler.ffi` that wraps the platform engine). Do not leave `MemorySegment` parameters on engine types.
3. Keep the existing TraceEvent/SyscallEvent heap-only rule.
4. Run `./gradlew :profiler:test --tests io.mazewall.profiler.ProfilerArchitectureTest` and focused engine tests until the new rule is green.

## Side effects
- Profiler engine packages that touch Arena or MemorySegment must move behind io.mazewall.ffi

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260907-080810  file: issue-20260907-080810-archunit-forbid-profiler-memorysegment-outside-ffi.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
