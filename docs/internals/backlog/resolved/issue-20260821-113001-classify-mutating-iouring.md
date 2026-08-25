---
title: "Classify mutating io_uring operations as writes"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/compiler/BobCompiler.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/ProfilerSessionApiTest.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3797199301
---

# 🟡 [Severity: MEDIUM]: Classify mutating io_uring operations as writes

**Context:** IoUring observations must go to `fsWritePaths` when the opcode is a mutation regardless of flags (`WRITE`, `UNLINK`, `RENAME`, `MKDIR`, `RMDIR`, `TRUNCATE`, `LINKAT`, `SYMLINKAT`, `FSYNC` / `SYNC`, `CLOSE`+`DIRECT`). Otherwise a compiled policy grants only `allowFsRead` and denies the observed mutation.

**Do not** put `IORING_OP_OPENAT` / `OPENAT2` in `isUringMutation`. Those opcodes are not writes unless open flags say so, and `ProfileObservation.IoUring` currently carries no flags. Treating OPENAT as a mutation over-grants Landlock write. Unknown-mode OPENAT is a **coverage** warning (`issue-20260821-113002-treat-iouring-open-modes`), not a write grant.

`opcode.contains("LINKAT")` covers both `IORING_OP_LINKAT` and `IORING_OP_SYMLINKAT`.

**Tests:** `IORING_OP_LINKAT` / `IORING_OP_SYMLINKAT` paths land in `fsWritePaths`. `IORING_OP_OPENAT` without flags stays in `opens`, not `fsWritePaths`.

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3797199301
