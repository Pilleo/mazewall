---
title: "Complete Kotlin upgrade locks and independent optional consumer approvals"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "build"
target_modules: []
target_files:
  - "gradle/libs.versions.toml"
target_symbols:
  - "kotlin"
open_questions: false
---

# 🟡 [Severity: HIGH]: Complete Kotlin upgrade locks and independent optional consumer approvals

**Context:**
Repository-wide review, 2026-11-04: [BUILD-01](../reviews/2026-11-04-repository-review-findings.md#build-01). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P1 · confirmed source integration defect; not Gradle-reproduced in this review · high confidence · boundary: optional Koog/Spring/publication join.**

**Trigger/evidence:** `gradle/libs.versions.toml:2` now selects Kotlin **2.4.21**. `integrations/koog/build.gradle.kts:1–3,11–14,20` applies that compiler/serialization plugin and declares `kotlin("test")` under strict all-configuration locking, but `integrations/koog/gradle.lockfile:94–138` still pins the ordinary compiler/plugin, stdlib and Kotlin test configurations to **2.4.20-Beta1** (2.4.21 appears only in separate ABI-compat configurations). In particular the direct Kotlin test/stdlib and compiler-classpath requirements do not match those old locked selections. The two Spring integration lockfiles also retain Beta1 stdlib selections (`integrations/spring/mazewall-spring-boot/gradle.lockfile:70`, `.../mazewall-spring-boot-starter/gradle.lockfile:69`).

The affected path is real and required: `.github/workflows/ci.yml:39–44` selects these opt-in builds, while `settings.gradle.kts:48–66` excludes them from an ordinary default full build. The publication join still requires optional acceptance (`ci.yml:889,898,901`). Gradle 9.8.2 documents locked selections as strict constraints; raising a direct requirement above the lock fails resolution, rather than updating locks automatically.

There is a downstream approval gap too: the four standalone metadata files under `demos/{koog-document-agent,spring-report-api}/gradle/` and `tests/consumers/{koog-portal,koog-spring}/gradle/` contain **zero** `org.jetbrains.kotlin:*:2.4.21` approvals. The codegen publication now carries the new KGP dependency (`portals/codegen/build.gradle.kts:26–31`). Acceptance adds only source-bound `io.mazewall` approvals, not new external Kotlin approvals (`OptionalIntegrationAcceptanceTest.kt:53–75`; `PitchExamplesAcceptanceTest.kt:262–276`). This must be reviewed after the first lock blocker is removed; it must not be fixed by disabling verification.

**Effect:** a default-build result cannot qualify the shared catalog upgrade; the required optional path has incompatible direct lock constraints, followed by unreviewed external consumer approvals. Spring's old transitive lock may also retain/downgrade older stdlib rather than necessarily producing the same first failure; I do not claim every listed path fails identically.

**Smallest complete correction:** complete the existing Kotlin upgrade across the opted-in producer locks and the actual independent consumer closure. Review exact new external hashes and newly selected locks; preserve strict modes, historical candidate locks and all existing approvals. Do not broaden trust or refresh unrelated dependencies.

**Regression/qualification:** after an authorized dependency-state correction, the exact CI optional-adapter command must resolve/compile with strict locks, followed by source-bound `optionalIntegrationCheck` and its copied consumers. Retain a wrong/missing-checksum negative. Do not relabel the old Beta1/JDK22 matrix as current 2.4.21 evidence.

**Tracking:** **NEW working-tree integration gap**; related to `issue-20261031-143810-764-fix-cold-spring-verification-and-reduce-ci-cache-c.md` and `issue-20261008-001530-668-stable-packaged-consumer-compatibility-gates.md`. The Gradle-wrapper upgrade issue `20261101-164416-960` is not proof that this later compiler upgrade's whole consumer closure is complete. **Unblock first:** producer lock consistency precedes consumer approval/native acceptance.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235255-561-complete-kotlin-upgrade-locks-and-independent-opti  file: issue-20261103-235255-561-complete-kotlin-upgrade-locks-and-independent-opti.md -->
