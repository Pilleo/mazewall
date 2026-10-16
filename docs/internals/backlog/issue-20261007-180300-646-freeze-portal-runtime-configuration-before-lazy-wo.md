---
title: "Freeze Portal runtime configuration before lazy worker startup"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: [portals-runtime]
target_files:
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRuntimeConfig.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalRuntimeTest.kt"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Freeze Portal runtime configuration before lazy worker startup

**Context:**
Approved P0 requires application-owned immutable configuration. PortalRuntimeConfig currently retains mutable manifest/artifact/profile collection references, so lazy worker startup and health can observe configuration changed after validation. Snapshot collections at construction and verify profile/artifact selectors cannot be replaced.

**Needed:**
1. Mutate caller collections after configuration construction and require original manifests/profiles/artifacts to remain selected.

## Evidence

Manifest list, worker-artifact map and worker-profile map are captured as immutable collection copies at configuration construction. Mutable caller selectors cannot change lazy worker startup or health after validation. The mutation regression failed before snapshots (`run-771488cfbc5f`) and passed with the complete Portal host suite (`run-8c2e42f4b504`). A shared Portal merge checkpoint passed (`run-21e594d7cc0f`, 1m43s, 265 tasks, 42 executed/223 up-to-date). No worker profile or policy default changed. Issue remains open for repository review.

---
<!-- id: issue-20261007-180300-646-freeze-portal-runtime-configuration-before-lazy-wo  file: issue-20261007-180300-646-freeze-portal-runtime-configuration-before-lazy-wo.md -->
