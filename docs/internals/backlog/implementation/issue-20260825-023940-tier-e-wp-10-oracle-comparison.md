---
title: "Tier E WP-10: Oracle comparison suite (Gate G3)"
severity: "ENHANCEMENT"
status: "open"
priority: high
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/test/kotlin/io/mazewall/profiler/"
effort: "large"
autonomy: "supervised"
open_questions: false
dependencies:
  - "issue-20260825-023939-tier-e-wp-09-live-collector.md"
---

# 🟢 [Severity: ENHANCEMENT]: WP-10 — Oracle Comparison Suite (G3)

**Context:** The existing `USER_NOTIF` profiler deliberately suspends the tracee at the
notification while the JVM listener captures the exact stack — it is the correctness reference.
Run identical workloads through both backends and diff attribution.

Design reference: [tier-e-design.md §8](../../designs/profiler/tier-e-design.md).

**Needed:** workload matrix, each with expected context and syscall:

| Workload | Expected context | Syscall |
|---|---|---|
| PDF parser | PDF_PARSE | openat |
| Network client | STRIPE_CLIENT | connect |
| Nested parser | innermost parser scope | read |
| Exception path | correct active scope during unwinding | openat |
| Executor reuse | each task's own scope | connect |
| Unregistered thread | UNKNOWN | any |
| Thread after another exited | never the previous thread's context | any |

Measure per run: total events / attributed-correct / UNKNOWN / dropped / **incorrect**.

### Gate G3 acceptance

```text
incorrect == 0 across the full matrix
UNKNOWN and dropped reported independently
oracle and Tier E agree on syscall identity for every correlated event
```

**PR is done when:** G3 passes in rootful CI runs; results archived next to the design doc
addendum. Any incorrect pairing blocks release of Tier E beyond experimental status.
