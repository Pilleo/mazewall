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

---
<!-- id: issue-20261019-170845-693-reject-unsaveable-hotspot-publication-after-the-fi  file: issue-20261019-170845-693-reject-unsaveable-hotspot-publication-after-the-fi.md -->
