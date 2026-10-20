---
title: "Release HotSpot bridge v0.2.0 and pin actual provider handoff"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "hotspot-bridge/README.md"
  - "docs/plans/2026-10-19-hotspot-bridge-release.md"
  - "profiler/hotspot-agentless/go.mod"
  - "profiler/hotspot-agentless/go.sum"
  - "experiments/hotspot-agentless/go.mod"
  - "experiments/hotspot-agentless/go.sum"
target_symbols:
  - "Verify"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Release HotSpot bridge v0.2.0 and pin actual provider handoff

**Context:**
Operator requested continue all the way after scoped admission93837185. Release v0.2.0 format3 SDK through the existing review path, preserve immutable v0.1.0, then use actual remotely resolved sums for hermetic native builds and provider handoff. Never publish assumed future checksums or bypass review, deployment-context or dirty-prototype gates.

**Needed:**
1. Register the exact tested SDK source and release scope for review, publish only the new submodule tag after the gate, verify downloaded origin/checksums, then update both native owners and rebuild/qualify actual release artifacts

---
<!-- id: issue-20261019-071745-155-release-hotspot-bridge-v0-2-0-and-pin-actual-provi  file: issue-20261019-071745-155-release-hotspot-bridge-v0-2-0-and-pin-actual-provi.md -->
