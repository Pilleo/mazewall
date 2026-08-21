---
title: "Unknown io_uring OPENAT modes are compiled as Landlock writes"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/compiler/BobCompiler.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/ProfilingCoverage.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/ProfilerSessionApiTest.kt"
effort: "small"
autonomy: "autonomous"
---

# 🟡 [Severity: MEDIUM]: Unknown io_uring OPENAT modes are compiled as Landlock writes

**Review (2026-08-21):** DUPLICATE of issue-20260821-113002-treat-iouring-open-modes (OPENAT is not a mutation).

**Context:** Follow-on to `issue-20260821-113002-treat-iouring-open-modes`. A mistaken fix added `OPENAT` to `isUringMutation`, which over-grants write.

**Fix:** `BobCompiler.isUringMutation` does **not** match OPENAT. Coverage still warns and sets `complete = false` for IoUring opcodes containing `OPEN`. Mutating opcodes including `LINKAT` (covers SYMLINKAT) go to `fsWritePaths`. Test: `IORING_OP_OPENAT` path is in `opens` and not `fsWritePaths`.
