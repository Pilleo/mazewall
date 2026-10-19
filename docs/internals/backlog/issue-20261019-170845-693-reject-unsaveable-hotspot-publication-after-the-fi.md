---
title: "Reject unsaveable HotSpot publication after the final profile snapshot"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "docs/plans/2026-10-19-hotspot-companion-local-evidence.md"
target_symbols:
  - "PublishHotSpot"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Reject unsaveable HotSpot publication after the final profile snapshot

**Context:**
C1 actual real-container max-time lifecycle and disk queue reproduced successful late publication after final save but before ack/removal; actual storage delivery has zero stacks. Normal save/queue control passes. Proof is C1-review/save-repro.log.

**Needed:**
1. Linearize publication admission with final snapshot using actual lifecycle state or a narrow closed flag; test both interleavings and preserve the monitored Completed host exception.

**Local evidence / remaining gate:**
Local node `2fd19c15` closes publication and takes the final snapshot under the
existing entry lock, rather than waiting for removal/ack. Actual max-time and
termination barrier regressions go RED→GREEN: before-final data reaches the real
disk queue/storage interface; after-final publication rejects without content/size
mutation. The monitored Completed-host exception still publishes/saves. Queue
acceptance is not server acknowledgment. Whole-feature review accepted I3; its
source/tests are unchanged in `fd108c3b410dbc06e53984e311b6abc9b9a0fe29`, and fresh
529connected race parent/subtest PASS records reselect those terminal/host controls,
0failures/0skips. Original failed/invalid fixture attempts remain history, not
success. See [current node evidence](../../plans/2026-10-19-hotspot-companion-local-evidence.md#subsequent-whole-feature-review)
for exact local graph/approval/receipts. Issue stays open: locally addressed only,
not published/merged; release pins, workspace-off/full privileged CI/image/native
and actual API-fetched persisted-profile acceptance remain unproved.

---
<!-- id: issue-20261019-170845-693-reject-unsaveable-hotspot-publication-after-the-fi  file: issue-20261019-170845-693-reject-unsaveable-hotspot-publication-after-the-fi.md -->
