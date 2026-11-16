---
title: "Align Codegen Kotlin reflection runtime with project Kotlin version"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules:
  - "portals/codegen"
target_files: 
  - "portals/codegen/build.gradle.kts"
target_symbols: 
  - "runtimeOnly"
open_questions: true
---

# 🟡 [Severity: MEDIUM]: Align Codegen Kotlin reflection runtime with project Kotlin version

**Context:**
A new Codegen Dependency-Check report identifies org.jetbrains.kotlin:kotlin-reflect:2.1.21 on the runtime graph, transitively through KotlinPoet 2.2.0. Dependency-Check maps this to CVE-2026-53914 with a broad CPE and CVSS 9.8. The related GHSA describes Kotlin Gradle Plugin versions below 2.4.20-Beta1 and a KAPT fix, not kotlin-reflect specifically, so applicability to this reflect artifact is unconfirmed; do not claim the artifact itself is vulnerable or suppress the finding. Codegen already has kotlin-reflect 2.4.20-Beta1 in testRuntime, with the existing version/hash constraints. The user's preferred remediation is one Codegen production runtimeOnly kotlin(reflect) declaration aligning the existing runtime module to the project Kotlin version. Do not force versions globally, add another module/version, or add a suppression.

**Needed:**
1. Confirm the Codegen runtime path and align its existing `kotlin-reflect` module to the current project Kotlin version (`2.4.20-Beta1`) with one production `runtimeOnly(kotlin("reflect"))` declaration. Do not add a module, force a version globally, or add a suppression.
2. Verify the resolved Codegen runtime graph and a published-artifact consumer use the aligned version, and retain focused functional/runtime coverage.
3. Run the mandatory security scan and inspect the Codegen report. Treat the Dependency-Check CPE/CVSS match as a finding requiring investigation, not proof that `kotlin-reflect` is vulnerable; the related GHSA concerns KGP/KAPT and does not establish applicability to this artifact.
4. Preserve existing dependency verification hashes and all unrelated owner checks. Keep the issue open until implementation and CI evidence are reviewed.

---
<!-- id: issue-20261116-002649-322-align-codegen-kotlin-reflection-runtime-with-proje  file: issue-20261116-002649-322-align-codegen-kotlin-reflection-runtime-with-proje.md -->
