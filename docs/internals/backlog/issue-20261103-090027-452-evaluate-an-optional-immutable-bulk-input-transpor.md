---
title: "Evaluate an optional immutable bulk-input transport for Portals"
severity: "MEDIUM"
status: "open"
priority: low
dependencies:
  - "issue-20261103-085927-123-compare-warm-portal-calls-with-executor-and-persis"
  - "issue-20261103-090026-755-make-granted-portal-file-inputs-convenient-and-own"
  - "issue-20261103-090027-201-reduce-measured-portal-codec-and-transport-overhea"
component: "benchmarks"
target_modules: []
target_files:
  - "benchmarks/portal-comparison"
  - "docs/internals/research/portal-bulk-input-transport.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Evaluate an optional immutable bulk-input transport for Portals

**Context:**
Portal developer-experience plan P11: evaluate the browser shared-buffer pattern only after binary IPC and granted-file measurements establish a relevant crossover; the deliverable is evidence and a bounded design decision, not unconditional shared memory.

**Needed:**
1. Compare copied bytes and P08's granted regular files on representative large inputs and retained/reused input workloads after P10.
2. Record a no-go if files already meet application budgets or copying does not dominate. Otherwise evaluate a clearly experimental broker-created immutable-input prototype under the same bounds.
3. Publish a go/no-go report with ownership/immutability evidence. A justified production implementation becomes a separately scoped backlog issue.

**Plan and dependencies:**
- [Implementation plan P11](../../superpowers/plans/2026-11-03-portal-developer-experience.md) and [bulk-input contract](../designs/portal/portal-developer-experience.md#7-file-capabilities-and-possible-bulk-buffers).
- Requires P01, P08 and P10 evidence. Conditional research is the deliverable; `open_questions: false` does not predetermine its measured outcome.

**Acceptance:**
- Retain 64 KiB, 1 MiB and near-message-limit cases, plus larger files through FD grants, including parse cost, RSS, allocation and cleanup.
- Any prototype has bounded mapping sizes, verifiable backing identity and immutability against other writers/truncation; descriptor duplication alone is insufficient.
- Exercise stale identity, oversized mapping, cancellation, worker crash and failed cleanup. Preserve broker-to-worker direction; no worker-returned FD, path-reopening shortcut or shared mutable heap.
- A go decision requires reproducible p95/throughput benefit at two relevant large sizes without unexplained small-call/memory regression and a complete ownership design. No-go with adequate measurements is a complete outcome.
- This issue introduces no default production transport/API; prototype source stays in the standalone comparison scope.

**Verification:**
- [Junior implementation walkthrough: P11](../../superpowers/plans/2026-11-03-portal-developer-experience-execution-guide.md#p11--bulk-input-go-or-no-go) explains existing-path measurement, bounded prototype hazards and a complete no-go outcome.
- Run comparison self-checks, measured byte/file cases and assert-based hazard controls for any prototype. Retain exact artifacts/environment/raw observations and link the report.
- For executable prototype changes run the root full build plus the distinct standalone checks; a report-only outcome needs reference checks, `./scripts/adkw check-backlog` and `git diff --check`.

---
<!-- id: issue-20261103-090027-452-evaluate-an-optional-immutable-bulk-input-transpor  file: issue-20261103-090027-452-evaluate-an-optional-immutable-bulk-input-transpor.md -->
