---
title: "Remove outline caches after confirmed retirement on parser failure"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "integrations"
target_modules: []
target_files:
  - "demos/koog-document-agent/src/main/kotlin/demo/koog/OutlineFileApplication.kt"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Remove outline caches after confirmed retirement on parser failure

**Context:**
PR562 review finding3:129headings reject after worker launch and canonical close, but leave17.6MB private cache despite a dead child. Never delete possibly-live resources or hide the original failure.

**Needed:**
1. Add packaged invalid-input native test proving child retirement and cache cleanup RED; establish retirement on success and failure, retain uncertain resources and original/suppressed errors; full native suite.

## Outcome

After canonical close, both success and failure paths check the captured child,
CLOSED/retirementComplete diagnostics and zero live workers before removing cache.
Uncertain retirement retains resources and fails; the original parser failure
keeps retirement/deletion failures suppressed. No success artifact is displayed.

The real129-heading failure/cleanup case and all four file-mode native tests pass;
[current qualification](issue-20261029-112338-058-qualify-copied-pitch-consumers-measurements-and-ho.md).
The former confirmed-dead cache-retention minor is fixed. Open pending managed landing.

---
## Repository-review sibling-path expansion — 2026-11-04

[KD-E1](../reviews/2026-11-04-repository-review-findings.md#kd-e1) preserves the corrected
actual-file OutlineFileApplication behavior. PortalExample, PortalDocumentAgent, DocumentAgent,
ThreadReadExample and their two presentation fixtures still delete synthetic roots in outer
finally after incomplete/exceptional close, skipping retirement checks and losing retry access.
Reuse the existing retirement-gated cleanup pattern with a retained owner and primary/suppressed
failures. Test actual app cleanup against held/failed physical retirement, retain root/no success
marker, then release/retry/delete. Logical-owner sequencing was reproduced; no operator-file
loss or live-worker JAR failure is proved. Do not reopen core retained-owner fixes as absent.

---
<!-- id: issue-20261030-144908-235-remove-outline-caches-after-confirmed-retirement-o  file: issue-20261030-144908-235-remove-outline-caches-after-confirmed-retirement-o.md -->
