---
title: "Capture every pathname operand from strace"
severity: "MEDIUM"
status: "resolved"
priority: medium
resolved_in_commit: c6923e5b
resolved_by: "already fixed by commit (multi-path extractQuotedPaths; 6b0dd1cf only gated first-quote extraction to path-bearing syscalls)"
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/compiler/StraceLogParser.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3819590949
---

# 🟡 [Severity: MEDIUM]: Capture every pathname operand from strace

**Context:** For multi-path syscalls such as `rename(old, new)`, `link(old, new)`, and `symlink(target, linkpath)`, this records only the first quoted argument. Coverage then treats the event as fully resolved, while `BobCompiler` omits the destination path; a policy compiled from that result lacks the Landlock permission needed for the operation that was actually observed.

**Problem:**
- Only first quoted argument recorded
- Coverage treats event as fully resolved
- BobCompiler omits destination path
- Policy lacks needed Landlock permission

**Impact:**
- Policy lacks permissions for observed operations
- Incomplete coverage

**Needed:**
1. Parse all syscall-specific pathname operands
2. Not just the first quote
3. Ensure all paths are captured

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3819590949
