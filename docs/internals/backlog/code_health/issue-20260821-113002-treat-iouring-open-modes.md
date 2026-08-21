---
title: "Treat io_uring open modes as unresolved"
severity: "MEDIUM"
status: "resolved"
priority: medium
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
related_pr: 512
related_thread: 3819590960
---

# 🟡 [Severity: MEDIUM]: Treat io_uring open modes as unresolved

**Context:** `ProfileObservation.IoUring` has no open flags. Every `IORING_OP_OPENAT*` is an unknown access mode.

**Required behavior:**
1. Mark coverage **incomplete** and warn `"io_uring open access mode was not observed"` when any IoUring opcode contains `OPEN` (that already includes OPENAT / OPENAT2).
2. Keep the path in `opens` (read), **not** `fsWritePaths`.
3. Operators who pass `allowIncomplete=true` must still not receive `allowFsWrite` for that path.

**Wrong:** Adding `OPENAT` to `BobCompiler.isUringMutation`. That compiles a read-only async open as a Landlock write. Coverage incompleteness is a warning, not a write grant.

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3819590960
