---
title: "Retain validated live worker and task receipts before wrapping"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "platform/src/main/kotlin/io/mazewall/ffi/internal/RealNativeStackContexts.kt"
target_symbols:
  - "registerCurrent"
open_questions: false
---

# 🟡 [Severity: HIGH]: Retain validated live worker and task receipts before wrapping

**Context:**
Register and Begin native operations can commit before fallible receipt decoding and typed wrapper allocation; an Error can leave the exact operation disposition unknown to the caller.

**Needed:**
1. Design and verify preattached bounded receipt ownership with preserved validation and conservative Unknown outcomes; retain old callables and do not change native ABI or activate Live.

## Bounded delivery status

The controller accepted the four-file attached-receipt prerequisite **with explicit limits** after independent review and the 084 full build; see the [controller decision](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b5-platform-receipt-full-build084/controller-decision.md) and [execution report](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b5-platform-receipt-full-build084/report.md). The issue remains open for the qualifications below; this acceptance does not activate Live or complete D4.

A `Refused` result may represent PREPARING, ATTEMPTING or UNVERIFIED uncertainty and does not prove no effect or no owner; JNI worker preparation may already have partial JVMTI effects. A terminated thread observation does not prove actual join or original-domain gate publication. Later D4 must establish those facts and serialize independently created carriers through its existing owner gate. Task recovery and End remain on the original worker; a lost committed task remains Unknown. No universal VM/OOME recovery, global duplicate-ID proof, actual native success or clean retirement is established.

Individual daemon descriptor cleanup remains Unknown under [issue 869](issue-20261108-113441-869-investigate-supervisor-daemon-ebadf-during-session.md); profiler/listener retirement under issue 752, actual dynamic Live worker-to-installer proof under issue 856, D2 clean-CLOSED-first under issue 013, and Legacy denial issue 509 remain open. The 45 skipped full-build cases are not execution qualification. T4 distribution and cold-file closure, B4/B6, helper NoWriters authority and broader native/JFR/workload qualifications remain open as stated in the decision.

---
<!-- id: issue-20261108-114247-892-retain-validated-live-worker-and-task-receipts-bef  file: issue-20261108-114247-892-retain-validated-live-worker-and-task-receipts-bef.md -->
