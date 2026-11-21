---
title: "Phase 3: Implement stable io.mazewall.lockdown API and ABI surface gates"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies:
  - "issue-20261120-122431-839-phase-2-extract-lockdown-module-and-cut-four-coupl"
  - "issue-20261028-232811-964-reject-installation-receipts-containing-both-succe"
  - "issue-20261028-232811-132-freeze-policy-definition-collections-at-every-cons"
component: "core"
target_modules: [":lockdown", ":enforcer"]
target_files:
  - "build.gradle.kts"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/InstallationReceipt.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/PolicyDefinition.kt"
target_symbols:
  - "Mazewall"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Phase 3: Implement stable io.mazewall.lockdown API and ABI surface gates

**Context:**
Provide the minimal Java-friendly Kotlin stable facade (Mazewall.installOnProcess, ProcessPolicies.denyProcessCreation, RuntimeProfile, opaque ProcessPolicy, non-AutoCloseable ProcessInstallation, MazewallInstallationException) with forced FAIL semantics and ABI/ArchUnit isolation.

**Needed:**
1. Author Java and Kotlin consumer examples, implement io.mazewall.lockdown.* with forced FAIL and defensive PolicyDefinition collection copy, add :lockdown ABI dump and ArchUnit leakage checks, and update verifySupportedApiSurface.

## Resolution Evidence (2026-11-21)
- Implemented the stable `io.mazewall.lockdown` public API in `:lockdown`: `Mazewall.installOnProcess(...)`, `ProcessPolicy.denyProcessCreation(...)`, `RuntimeProfile` (`HOTSPOT_JIT`, `NATIVE_IMAGE`), `ProcessInstallation`, and `MazewallInstallationException`.
- Enforced defensive `PolicyDefinition.frozenCopy()` / `PolicyDefinition.combineAll(...)` and `InstallationReceipt` contradiction invariant (`installed && failure != null`).
- Added `lockdown/api/lockdown.api`, `config/detekt/warnings/lockdown.txt` (0 warning budget), `:lockdown:test` Kotlin/Java (`LockdownJavaApiTest.java`) unit suites, and extended `verifySupportedApiSurface` + `verifyPackageTopology` + `verifyGradleReproducibility` (`lockdown/gradle.lockfile`).

- Full completion gate verified in commits `52e81d2e1` and `7e62bacf7` (`./gradlew build -PmazewallKernelLane=BASELINE` and rootful Docker `CONFINED` kernel lane passed).
---
<!-- id: issue-20261120-122433-169-phase-3-implement-stable-io-mazewall-lockdown-api-  file: issue-20261120-122433-169-phase-3-implement-stable-io-mazewall-lockdown-api-.md -->
