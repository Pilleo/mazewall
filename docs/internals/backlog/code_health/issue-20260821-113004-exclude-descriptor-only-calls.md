---
title: "Exclude descriptor-only calls from path completeness"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/ProfilingCoverage.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3825912167
---

# 🟡 [Severity: MEDIUM]: Exclude descriptor-only calls from path completeness

**Context:** When a strace profile observes `fstat`, `fchmod`, or `fchown`, these entries classify the event as path-bearing even though those syscalls accept only a file descriptor and therefore legitimately contain no pathname. `inferPaths()` consequently counts the event as failed and marks an otherwise complete descendant-strace profile incomplete.

**Problem:**
- fstat/fchmod/fchown classified as path-bearing
- But they accept only file descriptors
- inferPaths() counts them as failed
- Complete profiles marked incomplete

**Impact:**
- Complete profiles marked incomplete
- False incomplete coverage

**Needed:**
1. Classify descriptor-only syscalls separately
2. Don't count them as path-bearing
3. Or mark them as non-path-bearing explicitly

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3825912167
