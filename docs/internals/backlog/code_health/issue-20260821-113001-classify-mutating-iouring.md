---
title: "Classify mutating io_uring operations as writes"
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
related_thread: 3797199301
---

# 🟡 [Severity: MEDIUM]: Classify mutating io_uring operations as writes

**Context:** Every eBPF `IoUring` observation places its paths in `opens`, regardless of opcode. For recorded events such as `IORING_OP_WRITE`, `IORING_OP_UNLINKAT`, or a write-mode `IORING_OP_OPENAT`, the resulting Bill of Behavior therefore grants only `allowFsRead`; a policy accepted from otherwise complete coverage will deny the observed mutation when enforced.

**Problem:**
- All IoUring ops go to opens
- Mutating ops not classified as writes
- Bill of Behavior grants only allowFsRead
- Observed mutations denied

**Impact:**
- Mutations denied by policy
- Incomplete Bill of Behavior

**Needed:**
1. Route mutating opcodes to fsWritePaths
2. Retain flag information for classification
3. Route known mutating opcodes correctly

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3797199301
