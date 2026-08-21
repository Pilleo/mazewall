---
title: "Treat io_uring open modes as unresolved"
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
related_thread: 3819590960
---

# 🟡 [Severity: MEDIUM]: Treat io_uring open modes as unresolved

**Context:** Fresh evidence after the opcode-classification reply is that `ProfileObservation.IoUring` and `EbpfEventParser` still carry no open flags, so every `IORING_OP_OPENAT` reaches this non-mutation branch. A write-mode async open is consequently recorded as read-only, yet coverage can remain complete and produce a policy that denies the observed write.

**Problem:**
- IoUring and EbpfEventParser carry no open flags
- IORING_OP_OPENAT reaches non-mutation branch
- Write-mode async open recorded as read-only
- Policy denies observed write

**Impact:**
- Write operations denied by policy
- Coverage marked complete but incomplete

**Needed:**
1. Preserve open flags in IoUring/EbpfEventParser
2. Or mark io_uring open observations incomplete when access mode unknown

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3819590960
