---
title: "Record review hardening liveness and boundary evidence"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "docs/internals/designs/core/post-review-hardening-evidence.md"
target_symbols:
  - "ReviewHardeningEvidence"
open_questions: false

paperclip_issue_id: "883282b3-5470-443e-82dc-ea1a735d8efc"
paperclip_identifier: "MAZ-1395"
---

# 🟡 [Severity: MEDIUM]: Record review hardening liveness and boundary evidence

**Context:**
The completed review-hardening packages need a canonical finding-to-commit-to-test map, current diagrams and final gate evidence.

**Needed:**
1. Map each accepted finding to committed implementation and executable verification; regenerate diagrams and run final gates.

**Progress:** Documentation, 30-row RH evidence mapping, seven state/sequence SVGs,
both generated class diagrams, local-link/commit/test-name validation and backlog
validation are complete. RefactorFirst remains absent from the ordinary build graph.

**Remaining integration evidence:** The final build fails its unchanged Detekt warning
ratchet (131 added findings) and a fresh-JVM spawn-stack test; `unitCheck` also fails the
enforcer 82% coverage floor at 75%. The container gate cannot start without Podman.
Deadline-metrics evidence remains a separate integration follow-up. Keep this item open until
the [final verification record](../designs/core/review-hardening-verification.md) records
resolution. No threshold was lowered and no failed gate is represented as passing.

## Remaining follow-up integration evidence

Open and map the remaining Tier E correctness matrix to close this item:

- [issue-20260912-000001-close-remaining-tier-e-attribution-and-lifecycle-correctness-gaps.md](issue-20260912-000001-close-remaining-tier-e-attribution-and-lifecycle-correctness-gaps.md)
- Ensure each acceptance test from that issue is linked to a commit and a machine-readable evidence artifact (`PASS`, `INCOMPLETE`, or `FAIL`) before this item is considered finished.

### Required matrix linkage

| Test (follow-up issue §Required tests) | Commit linkage | Evidence artifact |
|---|---|---|
| 1 supported native → Java callback → unsupported native syscall | pending | pending |
| 2 active outer scope + inner capture failure | pending | pending |
| 3 mid-scope detach while session alive | pending | pending |
| 4 seccomp-denied attempt + active session | pending | pending |
| 5 PID reuse/exec transition stress | pending | pending |
| 6 method unload/redefine races | pending | pending |
| 7 first unique-edge emit fails then repeats | `AttributionCollectorTest.unique edge deduplication retries after failed emit and suppresses after acknowledgement` (focused PASS) | `./gradlew :profiler:test --tests io.mazewall.profiler.attribution.AttributionCollectorTest` (working-tree evidence; commit linkage pending) |
| 8 dictionary pressure with long-running workload | pending | pending |

The capture-domain contract is also implemented in the collector: `recordOutOfDomainAttempt`
produces machine-readable `outOfDomainAttempts` evidence and forces an incomplete guarantee.
This does not claim that the kernel can observe attempts denied before `sys_enter`; it records the
boundary limitation when an upstream component reports one.

---
<!-- id: issue-20260910-225629-741-record-review-hardening-liveness-and-boundary-evid  file: issue-20260910-225629-741-record-review-hardening-liveness-and-boundary-evid.md -->
