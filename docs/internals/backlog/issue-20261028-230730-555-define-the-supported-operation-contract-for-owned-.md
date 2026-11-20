---
title: "Define the supported operation contract for owned runtime integrations"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "integrations"
target_modules: []
target_files:
  - "integrations/spring/mazewall-spring-boot/README.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Define the supported operation contract for owned runtime integrations

**Context:**
Architecture assessment finding 3: owned workers correctly model irreversible restrictions, finite admission and physical completion, but moving work changes thread-local and transaction placement. Spring README:59-98 deliberately rejects unsupported advisor, asynchronous and return shapes. Acceptance: a canonical operation contract covers synchronous materialized input/output, context and transaction placement, independent helpers, nesting, policy-generation replacement, cancellation and incomplete drain; one representative application validates the contract without widening adapters speculatively. Reuse existing lifecycle and JDBC fixtures; this is not a rewrite of ManagedRuntime.

**Needed:**
1. Document the operation-level integration contract and walk a representative application through it; record concrete adoption blockers and preserve required installation, finite admission and retained retirement ownership.

---
## Repository-review qualification — 2026-11-04

[LIF-Q2](../reviews/2026-11-04-repository-review-findings.md#lif-q2) remains an unresolved
retirement boundary: worker death/core Complete drain can precede supervisor watcher/listener
physical cleanup. Either include these owners in the advertised completion or name a separate
required application owner with observable retirement. Hold actual watcher/listener cleanup
after worker death and inject success/ordinary/fatal outcomes; establish who retains retry
authority and reports incomplete/failure. Source confirms separate owners, not a native leak.
Also describe LIF-03's fully retired generation release and SPR-02's runtime-plus-dependency
retention after framework timeout. These are bounded contract additions, not a generic framework.

---
<!-- id: issue-20261028-230730-555-define-the-supported-operation-contract-for-owned-  file: issue-20261028-230730-555-define-the-supported-operation-contract-for-owned-.md -->
