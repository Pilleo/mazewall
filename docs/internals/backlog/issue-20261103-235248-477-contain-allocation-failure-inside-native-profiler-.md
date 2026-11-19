---
title: "Contain allocation failure inside native profiler capture boundaries"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [":profiler"]
target_files:
  - "profiler-native/src/agent.cpp"
target_symbols:
  - "capture_stack"
open_questions: false
---

# 🟡 [Severity: HIGH]: Contain allocation failure inside native profiler capture boundaries

**Context:**
Repository-wide review, 2026-11-04: [TE-02](../reviews/2026-11-04-repository-review-findings.md#te-02). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P1 · confirmed source defect · high confidence on C++ failure semantics · release boundary: optional native agent under allocation failure. NEW after backlog/review search.**

- **Trigger/evidence:** a C++ allocation fails during a cold capture or new execution identity. `InvocationScope` (`profiler-native/src/agent.cpp:904`), `execution_context` (`754`), and `capture_stack` (`1109`) are `noexcept`. They perform uncaught allocating operations: execution map/vector insertion (`788–789`), frame-vector reservation/growth (`1185–1201`), stack allocation/index insertion (`725–739`), and invocation-vector growth (`743–748`). There is no enclosing catch inside these `noexcept` boundaries.
- **Consequence:** `std::bad_alloc` invokes termination rather than returning a failed capture and allowing the original native operation to run. The explicit retained-byte reservation only refuses budget admission; it does not make subsequent STL allocations infallible. Even a catch in a proxy would be too late while nested helpers remain uncaught `noexcept` functions.
- **Smallest complete correction:** establish an exception-safe capture admission boundary, with rollback ownership for JNI refs and retained-byte reservations, and ensure every allocating path can reach it without crossing an uncaught `noexcept`. Convert recoverable profiler allocation failure to explicit capture loss/inactive state and invoke the original operation exactly once.
- **Regression:** inject allocation failure at frame reserve, execution insertion and invocation/stack publication; verify no target termination, no stale marker, original success/failure preserved, and no leaked reservations/references. `agent_native_retention_exhaustion` in `profiler-native/CMakeLists.txt:289–292` only sets a one-byte accounting budget and checks an exhaustion counter; it is not allocator-failure coverage.
- **Mapping/dependencies:** no matching native allocation-failure item found; the profiler-listener startup allocation issue concerns a different owner. Coordinate with TE-01 and Plan A/Plan B B2 before reusing native capture as a synchronous authorization prerequisite. Actual allocator-fault behavior was not executed here.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235248-477-contain-allocation-failure-inside-native-profiler-  file: issue-20261103-235248-477-contain-allocation-failure-inside-native-profiler-.md -->
