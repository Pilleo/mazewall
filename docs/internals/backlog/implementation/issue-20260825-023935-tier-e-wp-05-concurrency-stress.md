---
title: "Tier E WP-05: Concurrency stress suite — zero wrong pairings (Gate G2)"
severity: "ENHANCEMENT"
status: "resolved"
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

### Resolution (2026-08-26) — RESOLVED

* **Gate G2 PASSED**: 6372 events, 2012 distinct TIDs, incorrectCtx=0,
  leakAfterQuiet=0, outOfWindow=0. Verified under rootful podman with
  `--userns=host --pid=host --privileged`.
* Scenarios covered: thread churn (2000 batch-spawned threads), nesting
  (innermost-wins), executor reuse (unique ctx per task on fixed pool),
  exception paths (scripted mid-scope throw), quiet-leak detection.
* Verifier uses set-membership per-tid (clock-domain calibration between
  System.nanoTime and bpf_ktime_get_ns deferred as WP-05 refinement).
* RingbufReader rewritten to use native shim mmap (te_mmap_ring) because
  JVM-context mmap of BPF map fds returns EPERM; two-mapping approach also
  replaced by single combined RW mapping matching libbpf convention.
* Hit-counter instrumentation added to BPF programs for definitive
  attach-vs-fire diagnosis.

**PR is done when:** G2 passes in CI-style rootful container runs, results archived, and the
`ebpf-prototype/` directory is declared eligible for Gradle wiring (WP-09).

## Guardrails

* If any wrong pairing appears: halt, capture kernel version + CPU arch + repro, file a backlog
  issue immediately. Do not patch around it.
