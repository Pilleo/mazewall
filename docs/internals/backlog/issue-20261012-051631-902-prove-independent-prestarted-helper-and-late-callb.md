---
title: "Prove independent prestarted helper and late callback outside owned worker Landlock"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "enforcer"
target_modules: ["enforcer"]
target_files:
  - "enforcer/src/integrationTest/kotlin/io/mazewall/enforcer/api/execution/OwnedIndependentHelperNativeTest.kt"
target_symbols:
  - "OwnedIndependentHelperNativeTest"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Prove independent prestarted helper and late callback outside owned worker Landlock

**Context:**
Original R1 and A12 require actual same-file offload and callback placement with separate physical retirement; preserve the process Tier1 floor and inferred NIO provenance.

**Needed:**
1. Execute the disposable native same-file denial/helper positive and held callback drain proof, retain receipts, and update boundary guidance.

**Local implementation evidence (Task5b):**
- A genuinely acquired single-thread JDK executor executes a bootstrap task before
  worker preparation/body entry, retains the process Tier 1 floor and successfully
  reads the exact file that the installed owned body cannot read.
- Default owned classification retains the genuine `AccessDeniedException` cause
  with inferred permission provenance; delivery and `ExecutionEnd` retain identity.
- A callback scheduled explicitly after physical `ExecutionEnd` is held on that
  helper while owned drain joins the worker. The callback then reads the same file;
  its separate owner terminates and joins the actual helper thread before child reap.
- Focused native, lifecycle/classification host, integration Detekt, ABI, formatting
  and unchanged production warning-ratchet checks are retained in the Task5b report.
- The issue remains open for parent scoped acceptance. This is boundary evidence,
  not a complete ACE boundary or a claim of unrestricted/protected helper execution.
- Q1 follow-up: immediate nested file-owner cleanup scopes preserve the primary/cause
  and independently attempt data cleanup after evidence acquisition/deletion failure.
  Two narrow fault controls and the native fixture cover this path. Package-aligned
  source placement, camelCase entry, unchanged named deadlines/caps and a private
  callback proof split remove the fixture's ten avoidable integration warnings.

---
<!-- id: issue-20261012-051631-902-prove-independent-prestarted-helper-and-late-callb  file: issue-20261012-051631-902-prove-independent-prestarted-helper-and-late-callb.md -->
