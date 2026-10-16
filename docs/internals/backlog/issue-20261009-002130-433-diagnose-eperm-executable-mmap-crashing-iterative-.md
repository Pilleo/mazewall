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

**Source diagnosis and reviewed fixture correction:**
Retained native/Java frames establish synchronous late JNI size0 native-nmethod
generation through frequency-counter overflow on contained task73. Non-profiled
CodeHeap expansion by65536 executable bytes failed with EPERM. Relative-path NIO
fixture chose NATIVE_IMAGE on a live HotSpot/JVMCI substrate; this fixture asserted
only absolute read-path membership, not executable-mapping denial. Explicit
HOTSPOT_JIT is the supported substrate choice for this filesystem convergence case.
Private293fb55b03c4e069647f9620467129005879de93, integrated92ecdeb391464a6650d2ead45bf5ee672117f5c8,
independent PASS/ACCEPT with no Critical/Important findings. Meaningful RED failed
wrong-profile expectation before containment; fresh21 tests0skip GREEN, actual
content/no-write/mapping preservation added, both profiles and configured exec
denials verified across injected policy growth. Unchanged ratchet/hook GREEN.
No production policy/executor/BPF/permission fallback or unproven warm-up change.
Guidance explicitly records late thread-local JNI allocation limitations. Passing
checks do not reproduce the original fatal JVMCI cache context or make strict
NATIVE_IMAGE-on-HotSpot universally safe. Final repository acceptance remains open.

---
<!-- id: issue-20261009-002130-433-diagnose-eperm-executable-mmap-crashing-iterative-  file: issue-20261009-002130-433-diagnose-eperm-executable-mmap-crashing-iterative-.md -->
