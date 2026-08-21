---
title: "Extract only syscall pathname operands from strace"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/compiler/StraceLogParser.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3819982846
---

# 🟡 [Severity: MEDIUM]: Extract only syscall pathname operands from strace

**Context:** Fresh evidence after the multi-path parsing reply is that this regex now treats every quoted argument as a pathname rather than selecting syscall-specific operands. For example, `readlink("/tmp/link", "/secret", ...)` records the returned link text `/secret` as another read path, and `execve` records quoted argv entries as executable paths; `BobCompiler` can consequently widen the Bill of Behavior with paths that were never accessed by the syscall.

**Problem:**
- Regex treats every quoted argument as pathname
- readlink returns link text as path
- execve argv entries treated as executable paths
- Bill of Behavior widened with non-accessed paths

**Impact:**
- Bill of Behavior contains paths not accessed by syscalls
- Policy may be more permissive than needed

**Needed:**
1. Extract only syscall-specific pathname operands
2. Don't treat argv, link text, etc. as paths
3. Select operands based on syscall semantics

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3819982846
