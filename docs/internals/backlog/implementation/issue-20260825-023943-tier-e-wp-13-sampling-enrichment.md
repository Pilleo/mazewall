---
title: "Tier E WP-13: Sampling enrichment policy (never sample context propagation)"
severity: "ENHANCEMENT"
status: "open"
priority: medium
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/"
effort: "medium"
autonomy: "supervised"
open_questions: true
dependencies:
  - "issue-20260825-023936-tier-e-wp-06-noise-budget.md"
  - "issue-20260825-023940-tier-e-wp-10-oracle-comparison.md"
---

# 🟢 [Severity: ENHANCEMENT]: WP-13 — Sampling Enrichment Policy

**Context:** Sampling enriches an already-correct coarse attribution; it must never determine
whether coarse attribution is correct. The cardinal anti-pattern:

```text
WRONG: enter PDF_PARSE → sampling says skip → openat() → kernel still sees old scope
```

would generate a confident lie. Context propagation itself is therefore **always 100%,
unsampled**.

Design reference: [tier-e-design.md §12.3](../../designs/profiler/tier-e-design.md).

**Needed:**

1. Define what MAY be sampled:
   * deep JVM stack collection on interesting events (e.g. 1-in-N attributed syscalls);
   * separate diagnostic runs with the USER_NOTIF oracle for exact stacks;
   * optional UNKNOWN event emission (1-in-N) — only if WP-06 numbers justify ring-buffer cost.
2. Define what may NEVER be sampled:
   * marker/context_switch execution;
   * task-storage writes;
   * drop accounting (drops are always counted, never estimated).
3. Implement configuration surface for sample rates; document defaults.
4. Cross-reference WP-06 outcome for the UNKNOWN-sampling decision and record it in design
   doc §12.3 resolution.

### Acceptance

```text
documented policy table: sampled vs never-sampled
tests proving attribution correctness with sampling enabled AND disabled
no code path where a sampling decision can suppress a context_switch
```

## ❓ Open Questions

1. Default stack-sampling rate for CI vs interactive profiling sessions.
