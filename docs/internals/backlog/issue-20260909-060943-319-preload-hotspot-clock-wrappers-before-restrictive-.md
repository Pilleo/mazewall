---
title: "Preload HotSpot clock wrappers before restrictive iterative profiling"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [":profiler"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeTaskExecutor.kt"
target_symbols:
  - "RealIterativeTaskExecutor"
open_questions: false

paperclip_issue_id: "dc8702c6-355c-40c2-9667-14c2d76f2432"
paperclip_identifier: "MAZ-1369"
---

# 🟡 [Severity: HIGH]: Preload HotSpot clock wrappers before restrictive iterative profiling

**Context:**
A full profiler suite transient generated System.currentTimeMillis native wrapper on iterative-profiler-task after restrictive policy installation; CodeHeap mmap(PROT_EXEC) failed with EPERM. Existing resolved bootstrap pread64 and process-wide JIT issues have different triggers. Record and verify pre-containment clock-wrapper initialization without relaxing executable-mapping policy.

**Needed:**
1. Reproduce first-use clock-wrapper generation in a fresh JVM, preload before restrictive installation, and add an ordering regression.

**Task 7 evidence (2026-09-09):** The isolated clock workload passed without reproducing the
original full-suite crash. The iterative test class now primes both wall-clock and monotonic-clock
native wrappers in `@BeforeAll`; `IterativeClockPreloadTest` repeats that ordering in a fresh child
JVM and calls both clocks under the real restrictive iterative policy. This is a deterministic
test-setup mitigation, not a claim that arbitrary production first-use native wrappers are safe.
The production investigation remains open until the original CodeHeap failure has a stable
reproducer and the broader runtime limitation is addressed without relaxing containment.

---
<!-- id: issue-20260909-060943-319-preload-hotspot-clock-wrappers-before-restrictive-  file: issue-20260909-060943-319-preload-hotspot-clock-wrappers-before-restrictive-.md -->
