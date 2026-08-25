---
title: "Tier E WP-05: Concurrency stress suite — zero wrong pairings (Gate G2)"
severity: "ENHANCEMENT"
status: "open"
priority: high
component: "ebpf-prototype"
target_modules:
  - "ebpf-prototype"
target_files:
  - "ebpf-prototype/tests/"
effort: "large"
autonomy: "supervised"
open_questions: false
dependencies:
  - "issue-20260825-023934-tier-e-wp-04-lifecycle-trust.md"
---

# 🟢 [Severity: ENHANCEMENT]: WP-05 — Concurrency Stress Suite (G2)

**Context:** The gate that unlocks Gradle wiring. Quality bar:

> **Incorrect attribution = 0. UNKNOWN > 0 is acceptable data. Wrong attribution is corruption.**

Design reference: [tier-e-design.md §8, §11](../../designs/profiler/tier-e-design.md).

**Needed:** stress harness driving the WP-03 primitive + WP-04 lifecycle concurrently, checking
every emitted event against ground truth recorded by the driver:

1. **Thread churn:** create/destroy ≥100k platform threads (or max CI host tolerates). Every new
   thread starts at `UNKNOWN`; no thread ever observes another's context.
2. **Nesting:** HTTP_REQUEST → PDF_PARSE → restore chains across many threads simultaneously;
   innermost scope wins per event.
3. **Executor reuse:** tasks through fixed pools — each task's events carry that task's context,
   never the previous task's.
4. **Window edges (deliberate):**
   * scopes entered **before attach** ⇒ UNKNOWN until next transition;
   * events after **mid-scope detach** ⇒ last declared context only until next transition/task
     exit (documented residue); nothing after session DEAD.
5. **Exception paths:** exceptions unwinding through nested scopes keep correct attribution for
   syscalls made during unwinding.
6. Report separately: total / attributed-correct / UNKNOWN / dropped / **incorrect**.

### Acceptance

```text
incorrect == 0 across all scenarios, repeatedly
UNKNOWN and dropped reported independently
any incorrect pairing fails the run loudly with reproducing seed
```

**PR is done when:** G2 passes in CI-style rootful container runs, results archived, and the
`ebpf-prototype/` directory is declared eligible for Gradle wiring (WP-09).

## Guardrails

* If any wrong pairing appears: halt, capture kernel version + CPU arch + repro, file a backlog
  issue immediately. Do not patch around it.
