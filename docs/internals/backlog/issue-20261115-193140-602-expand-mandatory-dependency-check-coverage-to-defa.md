---
title: "Expand mandatory Dependency-Check coverage to default SDK production dependencies"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules:
  - "core"
  - "buildSrc"
target_files:
  - "build.gradle.kts"
  - ".github/workflows/ci.yml"
  - "buildSrc/src/main/kotlin/io/mazewall/build/CheckCiCachePolicy.kt"
  - "buildSrc/src/test/java/io/mazewall/build/VerificationWorkflowInputFunctionalTest.java"
  - "buildSrc/src/verification/kotlin/io/mazewall/build/CiCachePolicyTest.kt"
target_symbols:
  - "dependencyCheckAnalyze"
  - "dependencyCheckSdk"
open_questions: false
---

# 🟡 [Severity: HIGH]: Expand mandatory Dependency-Check coverage to default SDK production dependencies

**Context:**
The successful Security Validation job scanned only the root project's two reported jars (`annotations-13.0.jar` and `kotlin-stdlib-2.4.20-Beta1.jar`). Dependency-Check 13.0.0's `Analyze` task processes its own project, so the report does not establish coverage of default-included SDK modules. The scope audit identifies missed production dependencies in `:enforcer`, `:profiler`, and shipped `:portals:codegen`. Supporting local receipts are retained under `.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/ci-pr567-20261115-001/nvd-recovery002/scan-scope-audit.md` and `consumer-report-inventory.md` (workspace-local, not repository-published).

Keep the mandatory consumer keyless and read-only, gated by the existing trusted snapshot verifier and four-hour freshness rule. Preserve current scanner version, CVSS 7 threshold, `failOnError`, failure propagation, and default required join. The attempted Dependency-Check Aggregate approach hit a Gradle 9.8.2 lock failure; the approved correction uses per-project Analyze reports and a root join. Scope reports to the default-included SDK production dependencies, including shipped `:portals:codegen`; exclude demos, tests, and unrelated build-tool configurations. Exclude `:integrations` and `:tools` even when optional integration projects are enabled; Spring and Koog integration coverage remains unqualified and outside this issue's scope.

**Needed:**
1. Apply Dependency-Check to the root project and six SDK owners only in security scan mode: `:platform`, `:enforcer`, `:profiler`, `:portals:runtime`, `:portals:worker`, and shipped `:portals:codegen`. Keep `:portals` base explicit as no-scan. Preserve ordinary local plugin application and `dependencyCheckAnalyze` behavior.
2. Exclude all demos, `:integrations`, and `:tools`, including when optional integration projects are enabled. Limit each owner report to production `compileClasspath` and `runtimeClasspath` inputs.
3. Add a root `dependencyCheckSdk` join requiring all seven owner `dependencyCheckAnalyze` tasks. Run scans serially with `--no-parallel --max-workers=1` and explicit task ordering. Share the verified snapshot data directory; keep scan mode keyless and no-update behind the existing four-hour verifier. Preserve scanner version, CVSS 7, `failOnError`, suppressions, mandatory status, and failure/cancellation propagation.
4. Add functional tests for scan-mode-only plugin application, SDK owner lifecycle and selection, `:portals` no-scan behavior, excluded projects, production configurations, and the seven-report join. CI policy must require the root join and separately guard all seven report files; the join task itself does not check file presence. Reject a missing owner report.
5. Verify actual report inventories for all seven owners and fresh required CI. Require SDK-only sentinels such as serialization/coroutines and codegen production dependencies; reject demo and test-only entries where present. Do not treat the successful prior root-only report as SDK coverage.

**Current evidence (2026-11-15):** Per-owner scan configuration and the root join are implemented. The real security functional fixture passed (1m58s, exit 0); graph-init-free and the 625-case policy/report-invalidation checks passed; independent Option A review found no blocker. Root ktlint passed in lint006 after a formatting-only correction. Full002 and fresh CI with all seven report files and actual report inventories remain pending. Keep this issue OPEN; no SDK report contents or whole-SDK clearance are claimed.

---
<!-- id: issue-20261115-193140-602-expand-mandatory-dependency-check-coverage-to-defa  file: issue-20261115-193140-602-expand-mandatory-dependency-check-coverage-to-defa.md -->
