---
title: "Exclude descriptor-only calls from path completeness"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/ProfilingCoverage.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: PRRT_kwDOScnnEM6a-1F2
---

# 🟡 [Severity: MEDIUM]: Exclude descriptor-only calls from path completeness

**Context:** When a strace profile observes `fstat`, `fchmod`, or `fchown`, these entries classify the event as path-bearing even though those syscalls accept only a file descriptor and therefore legitimately contain no pathname. `inferPaths()` consequently counts the event as failed and marks an otherwise complete descendant-strace profile incomplete; remove these descriptor-only calls from `pathBearingNames` rather than requiring a nonexistent operand.

**Problem:**
- `ProfilingCoverage.kt:232` - FCHMOD, FCHOWN, FSTAT are in pathBearingNames
- These syscalls take file descriptors, not paths
- inferPaths() counts them as failed when no path is found
- Complete profiles marked as incomplete

**Impact:**
- False incomplete profile reports
- Operators cannot generate policies from valid strace runs
- Unnecessary friction in profiling workflow

**Needed:**
1. Remove FCHMOD, FCHOWN, FSTAT from pathBearingNames set
2. Consider if other fd-only syscalls need removal (fchmodat, fchownat, etc. may be okay as they can take path)

**Notes:**
- fstat/fchmod/fchown take only an fd argument, no path
- fchmodat/fchownat can take either fd+path or just fd
- Need to verify which syscalls should be in pathBearingNames
