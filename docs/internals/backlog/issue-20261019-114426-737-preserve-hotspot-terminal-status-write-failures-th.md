---
title: "Preserve HotSpot terminal status write failures through pending cleanup"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "docs/plans/2026-10-19-hotspot-companion-local-evidence.md"
target_symbols:
  - "Controller"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Preserve HotSpot terminal status write failures through pending cleanup

**Context:**
C5 scoped review and real held-reader regression found that Run correctly retained terminal-status rename EACCES, but later successful physical cleanup rebuilt its cause only from job.err and retirementCause, dropping that original persistence error. The actual RED is task5-status-cleanup-cause-red.log; the focused race GREEN and subsequent independent C5 gate pass after attaching status write failures to the actual pending owner. No pending native owner or failed capture is promoted to success.

**Needed:**
1. Retain the original terminal status persistence cause on the physical pending owner, verify actual held-pipe cleanup and non-pending Failed status, and record the exact committed/index receipts without bypassing release review

**Executed local remedy:** Node-agent commit72034696 retains the terminal status
write error in the actual pending job's retirement cause. The held real stdout
writer control first fails RED, then passes race checks after actual close/reaping:
cleanup remains Failed with original EACCES but no pending owner. Independently
exported tree d958b50de57b3550ef31163f81b5dbda80a802ca passes the connected race
suite with zero skips, guards and binary build; the final tree's only added
save/queue test is separately checked. Receipts live under
`build/kubescape-dev/node-companion/`; retain this item for control-plane review,
not automatic archive or a native-acceptance claim.

---
<!-- id: issue-20261019-114426-737-preserve-hotspot-terminal-status-write-failures-th  file: issue-20261019-114426-737-preserve-hotspot-terminal-status-write-failures-th.md -->
