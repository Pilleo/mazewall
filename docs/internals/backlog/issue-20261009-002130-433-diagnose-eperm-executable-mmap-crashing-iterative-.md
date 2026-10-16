---
title: "Diagnose EPERM executable mmap crashing iterative profiler task JVM"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [":profiler"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeTaskExecutor.kt"
target_symbols:
  - "IterativeTaskExecutor"
open_questions: false
---

# 🟡 [Severity: HIGH]: Diagnose EPERM executable mmap crashing iterative profiler task JVM

**Context:**
Full unchanged hook profiler:test worker crashed on iterative-profiler-task-73: os::commit_memory failed mapping65536 executable bytes with Operation not permitted errno1. The JVM labels it insufficient memory, but exact errno is EPERM, not an established host-RAM exhaustion cause. Retained log /tmp/maze-scanner-3-round2-hook-attempt1.log records pid3514709 and native frames. No kernel policy, assertions or skips were changed; an unchanged retry is not a fix. This security/liveness failure requires scoped diagnosis before final native acceptance.

**Needed:**
1. Reproduce the exact denied mapping and retained syscall/filter context in a disposable JVM; inspect iterative-task warmup and JVM floor constraints without swallowing EPERM or weakening required protection.

---
<!-- id: issue-20261009-002130-433-diagnose-eperm-executable-mmap-crashing-iterative-  file: issue-20261009-002130-433-diagnose-eperm-executable-mmap-crashing-iterative-.md -->
