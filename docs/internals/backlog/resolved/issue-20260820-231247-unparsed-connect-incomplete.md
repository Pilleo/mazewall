---
title: "Count each unparsed connect as incomplete"
severity: "MEDIUM"
status: "resolved"
priority: medium
component: "profiler"
dependencies: []
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/ProfilingCoverage.kt"
effort: "small"
autonomy: "autonomous"
---

# Count Each Unparsed Connect as Incomplete

**Source:** Codex PR review comment (P2)
**Created:** 2026-08-20

## Problem

Fresh evidence after the IPv6 parsing fix is that this predicate is global rather than per event: if a strace run contains one parsed INET connect and one unparsed connect such as AF_UNIX, the generic `Syscall("CONNECT")` makes the first condition true while the parsed `Connect` makes `none` false. Coverage can consequently remain complete despite losing one destination.

## Impact

- Incomplete coverage marked as complete
- Missing network endpoint information
- Policies generated with incomplete connect destinations

## Solution

Mark coverage incomplete whenever any generic CONNECT observation remains. Track per-event parsing status rather than global predicate.

## Related Files

- `profiler/src/main/kotlin/io/mazewall/profiler/ProfilingCoverage.kt` - Line 172
