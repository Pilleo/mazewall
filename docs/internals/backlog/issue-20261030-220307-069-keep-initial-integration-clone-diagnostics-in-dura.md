---
title: "Keep initial integration clone diagnostics in durable evidence"
severity: "LOW"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "src/consumerVerification/kotlin/io/mazewall/verification/ConsumerFixture.kt"
  - "src/consumerVerification/kotlin/io/mazewall/verification/ConsumerFixtureTest.kt"
  - "src/consumerVerification/kotlin/io/mazewall/verification/OptionalIntegrationAcceptanceTest.kt"
  - "docs/internals/designs/core/testing-strategy.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: LOW]: Keep initial integration clone diagnostics in durable evidence

**Context:**
Independent PR562 CI review found initial git-clone stdout/stderr and exit receipt remain under a temporary parent even though caller-owned durable evidence already exists. User asks to fix it and review the entire PR against master. Preserve checkout isolation, source snapshots, existing callers and ownership/timeout behavior; no new dependencies or test-selection changes.

**Needed:**
1. Add real failed-clone receipt retention regression RED; route optional integration checkout acquisition to caller evidence while preserving default behavior for other callers; verify successful checkout still captures exact source/modes, focused/full checks, then one fresh independent whole-PR review from actual base. Push scoped fix, no merge/publication.

## Outcome

`checkout(root,evidence=null)` sends the initial clone's stdout/stderr and ownership/
exit receipt to the optional caller's durable directory; other callers keep their
default behavior. Failed acquisition reports that same evidence location.

Real failed/successful clone tests check receipts and preserve source additions,
deletions and modes. [Hosted run41137639513](https://github.com/Pilleo/mazewall/actions/runs/41137639513) passed; its downloaded artifact contains
both clone logs and an exit0/reaped/not-timed-out receipt. Whole-PR review completed;
later benchmark removal and remaining bounds are tracked in subsequent records.
Open pending managed landing; checkout isolation and timeouts are unchanged.

---
<!-- id: issue-20261030-220307-069-keep-initial-integration-clone-diagnostics-in-dura  file: issue-20261030-220307-069-keep-initial-integration-clone-diagnostics-in-dura.md -->
