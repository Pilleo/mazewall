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

## Execution

Base `747b36e2e897462909076cf5b70d913482041b96`; user explicitly asks to fix the
minor clone-evidence gap and review the WHOLE PR against its actual master base,
not only CI changes. No worktree, separate plan/journal, dependencies or budgets.
- [x] Failed real git clone and successful checkout receipt paths observed RED.
- [x] Optional caller-owned evidence routes clone logs/receipt and error location.
- [x] Focused full host harness verification.
- [ ] Full build and fresh independent WHOLE-PR review against master.
- [ ] Push/update existing PR; no merge/package publication.

checkout(root,evidence=null) preserves existing default behavior for callers that
do not own an evidence directory. The optional gate supplies its already acquired
unique durable directory. The failure regression clones an owned non-repository,
requires stdout/stderr files, non-success JSON exit receipt, reaped/not-timed-out
ownership and the correct error evidence path. The existing actual-edit/mode/
deletion/alias clone test now also requires a successful durable clone receipt.
RED log `/tmp/opencode/ci-initial-clone-red.log`.
Full consumerHarnessTest now passes, including12ConsumerFixture and4pipeline cases;
log `/tmp/opencode/ci-initial-clone-green.log`. The new failed-clone test and the
existing successful source/mode fidelity test both require the caller-owned receipt.

---
<!-- id: issue-20261030-220307-069-keep-initial-integration-clone-diagnostics-in-dura  file: issue-20261030-220307-069-keep-initial-integration-clone-diagnostics-in-dura.md -->
