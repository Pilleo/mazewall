---
title: "Reject unenforceable io_uring opcodes before compiling"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/compiler/BobCompiler.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/BillOfBehavior.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/ProfilingCoverage.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3825912190
---

# 🟡 [Severity: MEDIUM]: Reject unenforceable io_uring opcodes before compiling

**Review (2026-08-21):** Still present. **Do not** add `OPENAT` to `isUringMutation` (`113002-treat-iouring-open-modes`). Unknown-mode OPENAT is a coverage warning + stay in `opens`. This issue is opcodes Landlock **cannot** express.

**Current tree:** `compileObservations` records every IoUring opcode in `ioUringOps`. Mutation opcodes go to `fsWritePaths`, others to `opens`. `toPolicy()` fail-closes on `connects`/`execs` and incomplete coverage, **not** on `IORING_OP_CONNECT` / `IORING_OP_SENDMSG` / `IORING_OP_RECVMSG`. Coverage `ioUring=OBSERVED` looks complete. The compiled policy either blocks those ops at seccomp (too tight) or never saw a connect endpoint (too loose). Landlock paths are the wrong control.

**Do not:**
- Map `IORING_OP_CONNECT` path strings into `opens`.
- Treat “opcode recorded” as enforceable.
- Grant `allowIncomplete` write/read paths as a substitute for network enforcement.

**Do:**
1. Classify opcodes that are not filesystem opens/mutations (CONNECT, SEND\*, RECV\*, ACCEPT, …) as **unenforceable**.
2. If any such opcode is present, `coverage.complete=false` and `toPolicy()`/`toDsl()` throw unless `allowIncomplete=true`.
3. Keep mutating fs opcodes on `fsWritePaths` (WRITE, UNLINK, RENAME, MKDIR, RMDIR, TRUNCATE, LINKAT).

**Tests:** `IORING_OP_CONNECT` + drain complete → `toPolicy()` throws; path not in `opens`. `IORING_OP_WRITE` with a path still goes to `fsWritePaths`. `IORING_OP_OPENAT` still not a write.

**Codex:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3825912190
