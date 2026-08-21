---
title: "Trace mutation syscalls before certifying USER_NOTIF coverage"
severity: "HIGH"
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
related_thread: 3823789292
---

# 🔴 [Severity: HIGH]: Trace mutation syscalls before certifying USER_NOTIF coverage

**Context:** With the default USER_NOTIF strategy, `Profiler.profile()` builds its filter from `PURE_COMPUTE_UNSAFE`, which leaves `CREAT`, `TRUNCATE`, and several other filesystem mutations at the default `ACT_ALLOW`; profiling mode converts only explicit errno actions to USER_NOTIF. A workload using native `creat()` or `truncate()` can therefore produce no observed events and still receive `coverage.complete=true`.

**Problem:**
- PURE_COMPUTE_UNSAFE leaves CREAT/TRUNCATE at ACT_ALLOW
- Only explicit errno actions converted to USER_NOTIF
- Native creat/truncate produce no observed events
- Coverage still marked complete

**Impact:**
- Security: mutations occur without observation
- Coverage incomplete but marked complete

**Needed:**
1. Include CREAT/TRUNCATE in profiled syscall set
2. Or mark coverage incomplete when mutation syscalls not traced
3. Ensure all filesystem mutations are observed

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3823789292
