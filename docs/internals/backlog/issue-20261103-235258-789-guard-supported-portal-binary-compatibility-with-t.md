---
title: "Guard supported Portal binary compatibility with the existing ABI mechanism"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: ["issue-20261006-152422-003-establish-packaged-java-and-kotlin-integration-con"]
component: "portal-runtime"
target_modules: [":portals:runtime", ":portals:codegen", ":portals:worker"]
target_files:
  - "portals/runtime/build.gradle.kts"
target_symbols:
  - "abiValidation"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Guard supported Portal binary compatibility with the existing ABI mechanism

**Context:**
Repository-wide review, 2026-11-04: [ARCH-02](../reviews/2026-11-04-repository-review-findings.md#arch-02). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P2 · qualification/enforcement gap, not an observed binary break · high confidence · Portal runtime/plugin and optional adapter release boundary.**

- **Condition/evidence:** Portal runtime and worker are Maven publications (`portals/runtime/build.gradle.kts:1–14`; `portals/worker/build.gradle.kts:1–14`), and codegen is a published Gradle-plugin surface (`portals/codegen/build.gradle.kts:7–14,39–46`). None configures `abiValidation()` or has an `api/*.api` snapshot. Root ABI/supported-surface gating names only platform/enforcer/profiler (`build.gradle.kts:1080–1128`); shared quality conventions do not add ABI validation (`buildSrc/src/main/kotlin/mazewall.quality-conventions.gradle.kts:5–11,50–68`). Yet current config deliberately preserves constructor/default-argument bridges (`portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRuntimeConfig.kt:10–30,68–113`), and the DX plan requires preserving legacy signatures/default bridges (`docs/superpowers/plans/2026-11-03-portal-developer-experience.md:48–52,279–298`).
- **Trigger/consequence:** during P02/P03 configuration/API expansion, removing one old JVM constructor or Kotlin `$default` bridge can leave all newly recompiled source fixtures green while a previously compiled application fails to link. Current source tests are useful, but do not establish that upgrade property. No such failing released upgrade was reproduced here.
- **Smallest correction:** classify the supported Portal application and generated-code bridge surfaces, then extend the existing ABI mechanism to those published contracts before expanding them. Mark deliberately internal cross-artifact bridges rather than stabilizing every implementation class. Apply the same policy to optional adapters only when their release promises compatibility; no new compatibility framework is needed.
- **Check:** capture the current accepted baseline, compile a Java and Kotlin config/binding consumer once, and run those unchanged class files against the next candidate; include original constructors/load entries and default bridges. Keep ordinary fresh published-plugin/source-consumer tests too. Wire the applicable ABI check into that artifact's release gate.
- **Tracking/status:** **newly identified uncovered acceptance dimension**, after exact searches for Portal ABI/binary-compatibility tracking. Coordinate with `docs/internals/backlog/issue-20261008-001530-668-stable-packaged-consumer-compatibility-gates.md`, `issue-20261008-072506-227-declare-optional-koog-production-plugin-bootstrap-.md`, and P02/P03 rather than duplicating their source-consumer work. CG03's wire-schema identity is related but does not cover JVM linkage.
- **Unblocks:** additive Portal-DX work and configuration evolution without relying on reviewer memory for binary bridges. Depends on ARCH-01's support classification.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235258-789-guard-supported-portal-binary-compatibility-with-t  file: issue-20261103-235258-789-guard-supported-portal-binary-compatibility-with-t.md -->
