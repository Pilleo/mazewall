---
title: "Restore recursive project-package coverage in SpotBugs gates"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "testing"
target_modules: []
target_files:
   - "build.gradle.kts"
   - "buildSrc/src/test/java/io/mazewall/build/RootVerificationFunctionalTest.java"
   - "buildSrc/build.gradle.kts"
   - "buildSrc/src/main/java/io/mazewall/build/GenerateSpotBugsSources.java"
   - "buildSrc/src/main/java/io/mazewall/build/SpotBugsToolchainPlugin.java"
   - "buildSrc/src/main/java/io/mazewall/build/VerifySpotBugsReport.java"
   - "buildSrc/src/test/java/io/mazewall/build/SpotBugsReportTest.java"
   - "buildSrc/src/test/java/io/mazewall/build/SpotBugsSourcePatchTest.java"
   - "buildSrc/src/test/java/io/mazewall/build/VerificationActionsTest.java"
   - "gradle/libs.versions.toml"
   - "gradle/verification-metadata.xml"
   - "gradle.lockfile"
   - "demos/vulnerable-web-app/gradle.lockfile"
   - "enforcer/gradle.lockfile"
   - "platform/gradle.lockfile"
   - "profiler/gradle.lockfile"
   - "portals/codegen/gradle.lockfile"
   - "portals/runtime/gradle.lockfile"
   - "portals/worker/gradle.lockfile"
   - "integrations/koog/gradle.lockfile"
   - "integrations/spring/mazewall-spring-boot/gradle.lockfile"
   - "integrations/spring/mazewall-spring-boot-starter/gradle.lockfile"
   - ".github/workflows/ci.yml"
   - "config/spotbugs/patches/README.md"
   - "config/spotbugs/patches/ValueRangeAnalysisFactory.properties"
   - "config/spotbugs/patches/UnconditionalValueDerefAnalysis.properties"
   - "config/spotbugs/patches/FindUselessObjects.properties"
   - "config/spotbugs/patches/FindBadCast2.properties"
   - "config/spotbugs/exclude.xml"
   - "portals/worker/src/main/kotlin/io/mazewall/portal/worker/PortalWorkerMain.kt"
   - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/TraceeAuthorityNotificationWork.kt"
   - "profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierEProfilerSession.kt"
   - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierEKotlinDaemon.kt"
   - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierERealPidReuseProbe.kt"
   - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierELongMatchedEstimator.kt"
   - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierERootfulPhaseProbe.kt"
   - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierERootfulGateRunner.kt"
   - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/controller/TierEFixtureLifecycle.kt"
target_symbols:
   - "onlyAnalyze"
needs_kernel: false
core_lock: true
effort: "medium"
autonomy: "supervised"
open_questions: false
has_side_effects: true
---

# 🟡 [Severity: MEDIUM]: Restore recursive project-package coverage in SpotBugs gates

**Context:**
During issue132632 qualification, the actual root SpotBugs task passes -onlyAnalyze io.mazewall.*,demo.vulnapp.*. SpotBugs4.9.8 documents .* as exactly one package and .- as a recursive prefix. Koog real classes are in io.mazewall.koog, so method detectors are screened out although summary metadata lists nine classes. The missing fake coroutine enclosing names arise in UnreadFields.report after skipped method visits. A diagnostic run of the same nine inputs without the selector visits methods and exposes additional pinned-tool diagnostics. This is analyzer coverage debt, not a demonstrated runtime vulnerability.

**Needed:**
1. Extend the actual-root-convention functional test with flat and nested packages for both intended roots; a real null-dereference probe in each must fail spotbugsMain, not a different quality gate.
2. Correct recursive selector semantics without disabling detectors, filters, severity or ignoreFailures, then evaluate newly visible real findings and tool limitations rather than suppressing them.

## Observed regression and scoped correction

The actual-root-convention TestKit fixture previously tested a null-dereference class only in flat `io.mazewall`. Extended the same real probe to flat/nested `io.mazewall` and `demo.vulnapp`. Before changing production configuration, the first flat probe correctly failed SpotBugs, while `io.mazewall.nested.Broken` unexpectedly passed the complete unit gate. The verifier exited1 with `UnexpectedBuildSuccess`, proving the actual detector-selection gap rather than a source-string assertion.

Exact log: `.superpowers/sdd/2026-10-19-koog-spotbugs-qualification/namespace-red.log:1114-1117,1452-1460`. No root selector correction preceded that RED. The minimal candidate changes only each package mask's `.*` to SpotBugs' recursive `.-`; it does not disable filters/detectors/severity or change runtime code/dependencies. Actual GREEN, broader analysis findings and independent acceptance remain separate requirements.

This root coverage issue is a prerequisite revealed by [Koog coroutine qualification](code_health/issue-20261018-132632-qualify-spotbugs-analysis-of-generated-koog-coroutine-classe.md). Properly enabled analysis also exposes existing Kotlin/FindSecBugs compatibility diagnostics, which must not be suppressed or represented as complete coverage.

## Completion checkpoint: candidate correct, integration blocked

The real four-namespace regression is GREEN and independent review found no
selector/test defects. Serial full builds with both optional integrations selected
still exit1 at eight analysis tasks: enforcer/platform/Koog/Spring main, Portal
codegen/worker main, and profiler main/tooling. Test-health aggregation succeeds;
the separately reproduced web-demo locale fixture was corrected only at its test
launch boundary. These results are retained in
`.superpowers/sdd/2026-10-19-koog-thread-host-contracts/final-clean-reports-build.log`.

An intervening completion attempt was polluted by the manual JUnit stress
launcher's default test-health output. That run also failed aggregation and is
not equivalent acceptance evidence. The stress reports were then explicitly
isolated, repeated successfully, and the fully serial final build above again
failed only the eight analyzer tasks, with healthy test aggregation.

Do not merge the recursive selector as a passing whole-build correction. The
newly visible findings/compatibility errors need their own supported resolution;
no analyzer disabling, blanket generated-code exemption or tool pin change had been
made to hide them. A temporary coroutine matcher experiment was rejected after
it missed real near-match runtime failures; see the linked Koog issue.

## Resumed qualification after operator tooling approval

The preceding checkpoint is historical, not the current gate result. The operator
subsequently authorized reviewed tool-only dependency/fork corrections, not further
remaining-defect suppressions. SpotBugs core4.10.5 plus FindSecBugs1.14.0 now use a
reproducible, source-verified four-family overlay for invalid value-number facts.
No `FindNullDeref` replacement or coroutine exemption ships. Source/license and
reconstruction details are in [the tool patch documentation](../../../config/spotbugs/patches/README.md).

The real TestKit verifier exercises both namespaces, intentional singleton seams
and an unlisted same-class field, reachable range/cast/null/useless-object controls,
and an actual missing-class failure. Every analyzer requires an error-free XML
report after waiting for its worker, rather than accepting process exit zero.

Focused analysis now passes all eight previously failing tasks with zero errors
and missing classes. Removing each overlay family independently restores its
specific actual-module analysis failure; neither valid coroutine near-match NPE
control is hidden, and both actual adapter-body null injections remain detected.
Receipts: `.superpowers/sdd/2026-10-19-strict-analyzer-repair/`.
The corrected full build with both optional integrations now exits0 (286 tasks),
including `aggregateTestHealth`; actual Koog/Spring analyzer executions also pass
with configuration-cache reuse. Independent whole-change review found no Critical
issue; its portable-recipe Important finding was fixed and exercised against fresh
temporary storage. The known TestKit escaped-checkout-path limitation is deferred.
See `completion-receipt.json` in that evidence directory. Changes remain local and
this issue remains open pending the scoped orchestrator integration path. External
registry retrieval still requires operator-provided `read:packages` permission.

---
<!-- id: issue-20261019-013721-472-restore-recursive-project-package-coverage-in-spot  file: issue-20261019-013721-472-restore-recursive-project-package-coverage-in-spot.md -->
