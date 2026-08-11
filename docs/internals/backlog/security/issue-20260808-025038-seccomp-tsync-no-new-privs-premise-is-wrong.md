---
title: "Seccomp TSYNC Documentation Uses an Incorrect no_new_privs Premise"
severity: "HIGH"
status: "open"
priority: 1
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
  - ":profiler"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt"
  - "docs/presentation/article3-enforcement.md"
  - "docs/internals/designs/profiler/profiler-design.md"
  - "docs/internals/backlog/resolved/issue-106-installonprocess-process-wide-seccomp-synchronization-tsync-.md"
effort: "medium"
autonomy: "supervised"
---

# 🔴 [Severity: HIGH]: Seccomp TSYNC Documentation Uses an Incorrect no_new_privs Premise

**Context:** Multiple documents, resolved issue 106, and the `PureJavaBpfEngine` EACCES diagnostic state that `SECCOMP_FILTER_FLAG_TSYNC` requires `no_new_privs` on every sibling thread and therefore fails on an ordinary JVM. Linux `seccomp(2)` requires `CAP_SYS_ADMIN` or `no_new_privs` on the calling thread. TSYNC copies the caller's filter tree to siblings and fails when a sibling is in strict mode or has a divergent filter tree; sibling `no_new_privs` is not documented as the asserted prerequisite. The current explanation can incorrectly tell operators that in-process process-wide Seccomp is unavailable and can conceal the real cause of an installation failure.

**Needed:** Reproduce `installOnProcess` on a normal multithreaded JVM across supported kernels and containers. Correct the EACCES diagnostic, presentation, profiler design, and resolved-issue record to match `seccomp(2)`. Decode TSYNC's positive offending-TID return separately from ordinary `-1/errno` failures, and add tests for caller NNP, divergent sibling filter trees, strict-mode siblings where feasible, and outer-container Seccomp denial.
