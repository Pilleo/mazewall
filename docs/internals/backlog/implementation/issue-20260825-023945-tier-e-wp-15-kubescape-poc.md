---
title: "Tier E WP-15: Kubescape node-agent integration PoC"
severity: "ENHANCEMENT"
status: "open"
priority: low
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "docs/internals/designs/profiler/tier-e-design.md"
effort: "large"
autonomy: "supervised"
open_questions: true
dependencies:
  - "issue-20260825-023944-tier-e-wp-14-ffm-migration.md"
paperclip_issue_id: 668b5140-46a5-472d-b5a8-f57aeac878f4
---

# 🟢 [Severity: ENHANCEMENT]: WP-15 — Kubescape Integration PoC

**Context:** The end-state value proposition: Kubescape events gain an optional Java semantic
context. Integration must be tiny, optional, and non-invasive — if no Mazewall-aware JVM is
attached, behavior is exactly today's.

Design reference: [tier-e-design.md §1, §5, Appendix A.3](../../designs/profiler/tier-e-design.md).

**Needed:**

1. Enrichment contract on the node side:

   ```text
   event.context_id = mazewall_lookup_context(current_tid_or_task)
   absent / not attached ⇒ context_id = 0 (UNKNOWN), behavior unchanged
   ```

   Implementation shape follows from how the node-agent consumes kernel events; the lookup is
   a read of the same task-storage map (or its exported equivalent) — never a new enforcement
   path (invariant 1).

2. Output example:

   ```json
   {
     "container": "payment-service",
     "syscall": "connect",
     "javaContext": "STRIPE_CLIENT"
   }
   ```

3. Container/workload metadata association from WP-07.
4. Documentation: Tier E threat model restated in Kubescape-facing terms — enrichment data is
   tracee-controlled and forgeable; detection hints only.
5. Confirm deployment-target kernel matrix against our 5.15 floor (design doc §12.2) and
   record the outcome there.

### Acceptance

```text
demo container workload shows javaContext on attributed events
node-agent without Mazewall present behaves byte-identically to baseline
threat-model section reviewed and accepted by operator
```

## ❓ Open Questions

1. Upstream acceptance path: prototype branch vs vendor patch vs design proposal to
   Kubescape maintainers.
