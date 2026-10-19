---
title: "Reject contradictory OCI descriptor media before digest deduplication"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "hotspot-bridge/bundle/verify.go"
target_symbols:
  - "OCIGraph"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Reject contradictory OCI descriptor media before digest deduplication

**Context:**
PR542 whole-SDK review reproduced order-dependent acceptance of a missing reachable layer when one digest has manifest and opaque descriptors. This is incomplete graph validation, not a trusted-manifest SHA bypass. The shared verifier serves MazeWall tooling and node-agent; the Kotlin counterpart needs its own regression.

**Needed:**
1. Add both descriptor orders, same-media shared-blob positives and missing or unlisted child negatives; reject conflicting effective media before deduplication without changing budgets.

---
<!-- id: issue-20261019-163139-343-reject-contradictory-oci-descriptor-media-before-d  file: issue-20261019-163139-343-reject-contradictory-oci-descriptor-media-before-d.md -->
