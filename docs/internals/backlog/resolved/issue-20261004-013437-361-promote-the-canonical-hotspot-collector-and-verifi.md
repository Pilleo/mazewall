---
title: "Promote the canonical HotSpot collector and verified local assets"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/hotspot-agentless"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotAssets.kt"
  - "profiler/build.gradle.kts"
  - "experiments/hotspot-agentless"
target_symbols:
  - "HotSpotAssets"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Promote the canonical HotSpot collector and verified local assets

**Context:**
Approved integration milestone 2 needs one canonical engine and locally installed verified executable plus OCI data without runtime builds or downloads

**Needed:**
1. Test version ABI checksum and incomplete asset installation refusal before promoting source and packaging

**Resolution:** One canonical collector/runtime/overlay tree with delegated
experiment builds; pinned reproducible static executable and local OCI asset
graph; bounded internal Kotlin checksum/version/ABI/closure admission; optional
Gradle build/verify/install tasks with explicit operator destination. Verified
native finalization and three installed strict capture runs (3600 correct marked
segments, zero missing/wrong/skipped), focused contracts, full profiler tests and
merge gate. Full image references preserve Gadget's actual normalized local-loader
contract. See [milestone evidence](../../../plans/2026-10-04-mazewall-hotspot-assets-milestone.md).

---
<!-- id: issue-20261004-013437-361-promote-the-canonical-hotspot-collector-and-verifi  file: issue-20261004-013437-361-promote-the-canonical-hotspot-collector-and-verifi.md -->
