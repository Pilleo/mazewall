---
title: "Count each unparsed connect as incomplete"
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
related_thread: 3823789286
---

# 🟡 [Severity: MEDIUM]: Count each unparsed connect as incomplete

**Context:** Fresh evidence after the IPv6 parsing fix is that this predicate is global rather than per event: if a strace run contains one parsed INET connect and one unparsed connect such as AF_UNIX, the generic `Syscall("CONNECT")` makes the first condition true while the parsed `Connect` makes `none` false. Coverage can consequently remain complete despite losing one destination path.

**Problem:**
- Predicate is global, not per event
- One parsed INET connect makes condition true
- One unparsed connect (AF_UNIX) makes none false
- Coverage remains complete despite missing path

**Impact:**
- Coverage complete despite unparsed connects
- Missing destination paths

**Needed:**
1. Make predicate per-event
2. Count each unparsed connect individually
3. Mark incomplete when any connect unparsed

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3823789286
