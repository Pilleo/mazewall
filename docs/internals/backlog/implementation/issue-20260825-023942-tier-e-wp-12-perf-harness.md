---
title: "Tier E WP-12: Performance harness (Spring demo service, modes A-D)"
severity: "ENHANCEMENT"
status: "open"
priority: medium
component: "profiler"
target_modules:
  - ":demos"
target_files:
  - "demos/tier-e-perf-harness/"
effort: "large"
autonomy: "supervised"
open_questions: false
dependencies:
  - "issue-20260825-023941-tier-e-wp-11-java-agent.md"
---

# 🟢 [Severity: ENHANCEMENT]: WP-12 — Performance Harness

**Context:** Quantifies the cost of the semantic bridge before any public claim is made.
Internal engineering targets only — **do not publish numbers as promises**.

Design reference: [tier-e-design.md §8 G1, §11 risk 1](../../designs/profiler/tier-e-design.md).

**Needed:**

1. Tiny realistic service (Spring Boot candidate — **dependency requires operator approval in
   the PR**) living under `demos/tier-e-perf-harness/`, endpoints:

   ```text
   GET  /baseline      (no scope)
   POST /json          JSON_PARSE scope
   POST /yaml          YAML_PARSE scope
   POST /pdf           PDF_PARSE scope
   GET  /external      STRIPE_CLIENT scope (outbound connect)
   ```

2. Measurement modes:
   ```text
   A. no Mazewall            B. explicit MazewallContext scopes
   C. B + java agent         D. USER_NOTIF oracle profiler (reference)
   ```
3. Metrics per mode: requests/sec, p50/p95/p99, CPU, allocation rate, context
   transitions/sec, syscalls/sec.
4. Primary comparisons: A↔B isolates bridge cost; B↔C isolates agent cost; D documents the
   suspension-based alternative's overhead for contrast.
5. Internal targets (engineering guidance, not commitments): explicit bridge <2% throughput
   impact; limited agent <5%; no pathological p99 regression.
6. **Stop rule:** if mode B alone costs ≥10%, stop optimizing instrumentation — the
   architecture is the problem. Escalate with data instead of tuning.

### Acceptance

```text
repeatable harness scripts (warmup, fixed load profile, recorded environment)
results archived next to design doc addendum
mode D numbers included for honest comparison against Tier S
```
