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
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3825912190
---

# 🟡 [Severity: MEDIUM]: Reject unenforceable io_uring opcodes before compiling

**Context:** When a complete eBPF observation reports an operation such as `IORING_OP_CONNECT` or `IORING_OP_SENDMSG`, this branch records only the opcode and treats its optional paths as filesystem access. Coverage regards the io_uring stream as observed, but `BillOfBehavior.toPolicy()` rejects only `connects` and `execs`, so the generated default policy blocks or incorrectly permits network endpoints that `Landlock` cannot enforce.

**Problem:**
- IORING_OP_CONNECT/IORING_OP_SENDMSG recorded as opcode only
- Paths treated as filesystem access
- Coverage marks io_uring as observed
- toPolicy() can't enforce network endpoints with Landlock

**Impact:**
- Network endpoints not enforceable with Landlock
- Policy may block or permit incorrectly

**Needed:**
1. Reject io_uring opcodes that can't be enforced
2. Or handle network endpoints differently
3. Don't mark unenforceable operations as complete

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3825912190
