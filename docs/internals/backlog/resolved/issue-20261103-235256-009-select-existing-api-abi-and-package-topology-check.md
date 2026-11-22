---
title: "Select existing API ABI and package topology checks in required CI"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "build"
target_modules: []
target_files:
  - ".github/workflows/ci.yml"
target_symbols:
  - "required"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Select existing API ABI and package topology checks in required CI

**Context:**
Repository-wide review, 2026-11-04: [BUILD-02](../../reviews/2026-11-04-repository-review-findings.md#build-02). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P2 · confirmed gate omission · high confidence · boundary: core/profiler publication compatibility.**

**Trigger/evidence:** add an otherwise unused public overload to a supported API without updating its approved ABI dump. `build.gradle.kts:1080–1084,1125–1128` deliberately attaches `verifySupportedApiSurface` and the three `checkKotlinAbi` tasks to root **`check`**; package-cutover validation is likewise attached only to root `check` (`:1135–1178`). The root `unitCheck` allowlist does not include these tasks (`:856–875`), and module unit/kernel gates only select tests/coverage (`:820–838`).

CI instead executes `:unitCheck :verifyGradleReproducibility` (`ci.yml:189–200`), then `:kernelCheck assemble` (`:236–240`), plus adapter-specific builds, the build-logic verifier and consumer fixtures. No workflow selects root `check`, `build`, `mergeCheck`, the ABI tasks, `verifySupportedApiSurface` or `verifyPackageTopology`. The vulnerability-demo script builds only its own demo artifacts (`scripts/run_vulnerable_app_demo.sh:266–274`); it does not close this missing edge. BuildSrc fixture builds validate copied wiring, not the current product ABI snapshots.

**Effect:** the current publication prerequisite graph omits the intentionally approved API-baseline and package-cutover checks. Source/test compilation cannot detect an unreviewed additive public API change. A local complete `build` does include these gates, but CI's six-success join alone does not.

**Smallest complete correction:** select the existing API/surface/topology gates explicitly in an appropriate required host lane, or add them to its existing host aggregate. Do not reintroduce unnecessary kernel/build-logic duplication merely to get this edge.

**Regression:** a copied production-workflow/task-selection fixture should show these real gate owners selected; an intentional ABI mismatch must make the required lane fail before publication. Preserve the six-literal-success join.

**Tracking:** **NEW missing CI edge**, related to resolved `issue-20260808-032525-reduce-and-version-public-api-surface.md:28,42–49` and its promised CI compatibility checks. This is not a request to change the approved API baseline. Independent of BUILD-01.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

## Resolution Evidence (2026-11-21)
- Added `"verifySupportedApiSurface"` and `"verifyPackageTopology"` to root `unitCheck`'s lazy root task allowlist and `"checkKotlinAbi"` to `unitCheck`'s lazy non-demo subproject allowlist in `build.gradle.kts`, so CI's existing `./gradlew :unitCheck :verifyGradleReproducibility` step enforces all API, ABI, and package-topology gates without adding workflow-level task duplication.
- Extended `CoreLaneFunctionalTest` (`buildSrc/src/test/java/io/mazewall/build/CoreLaneFunctionalTest.java`) with the exact CI `core-quality` command arguments plus a `-PfailAbi=true` negative control, and extended `AggregateVerificationFunctionalTest` (`buildSrc/src/test/java/io/mazewall/build/AggregateVerificationFunctionalTest.java`) to assert configuration-cache reuse and demo exclusion for `:verifySupportedApiSurface`, `:verifyPackageTopology`, and `:checkKotlinAbi`.
- Full completion gate verified in commits `52e81d2e1` and `7e62bacf7` (`./gradlew build -PmazewallKernelLane=BASELINE` and rootful Docker `CONFINED` kernel lane passed).

---
<!-- id: issue-20261103-235256-009-select-existing-api-abi-and-package-topology-check  file: issue-20261103-235256-009-select-existing-api-abi-and-package-topology-check.md -->
