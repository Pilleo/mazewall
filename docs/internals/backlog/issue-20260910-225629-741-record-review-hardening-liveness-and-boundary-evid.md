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
---

# 🟡 [Severity: MEDIUM]: Record review hardening liveness and boundary evidence

**Context:**
The completed review-hardening packages need a canonical finding-to-commit-to-test map, current diagrams and final gate evidence.

**Needed:**
1. Map each accepted finding to committed implementation and executable verification; regenerate diagrams and run final gates.

**Progress:** Documentation, 30-row RH evidence mapping, seven state/sequence SVGs,
both generated class diagrams, local-link/commit/test-name validation and backlog
validation are complete. RefactorFirst remains absent from the ordinary build graph.

**Remaining integration evidence:** The final build fails its unchanged Detekt
warning ratchet (131 added findings) and a fresh-JVM spawn-stack test; `unitCheck`
also fails the enforcer 82% coverage floor at 75%. The container gate cannot start
without Podman. Deadline-metrics evidence remains a separate integration follow-up.
Keep this item open until the [final verification record](../designs/core/review-hardening-verification.md)
records resolution. No threshold was lowered and no failed gate is represented as passing.

---
<!-- id: issue-20260910-225629-741-record-review-hardening-liveness-and-boundary-evid  file: issue-20260910-225629-741-record-review-hardening-liveness-and-boundary-evid.md -->
