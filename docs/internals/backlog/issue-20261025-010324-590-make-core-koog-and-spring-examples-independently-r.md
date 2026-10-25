---
title: "Make core Koog and Spring examples independently runnable and copyable"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "demos"
target_modules: []
target_files:
   - "demos/README.md"
   - "README.md"
   - "docs/presentation/PRESENTER_GUIDE.md"
   - "demos/cli-demo/README.md"
   - "demos/cli-demo/build.gradle.kts"
   - "demos/cli-demo/src/main/kotlin/demo/WorkspaceExample.kt"
   - "demos/cli-demo/src/main/kotlin/demo/OwnedDemoRuntime.kt"
   - "demos/cli-demo/src/test/kotlin/demo/OwnedDemoRuntimeAwaitTest.kt"
   - "demos/cli-demo/src/test/kotlin/demo/WorkspaceExampleTest.kt"
   - "demos/cli-demo/src/test/kotlin/demo/EnforcementPresentationTest.kt"
   - "demos/koog-contained-tool/README.md"
   - "demos/koog-contained-tool/build.gradle.kts"
   - "demos/koog-contained-tool/src/main/kotlin/io/mazewall/demo/koog/PortalExample.kt"
   - "demos/koog-contained-tool/src/main/kotlin/io/mazewall/demo/koog/ThreadReadTool.kt"
   - "demos/koog-contained-tool/src/main/kotlin/io/mazewall/demo/koog/ThreadReadExample.kt"
   - "demos/koog-contained-tool/src/test/kotlin/io/mazewall/demo/koog/ThreadReadToolTest.kt"
   - "demos/koog-contained-tool/src/test/kotlin/io/mazewall/demo/koog/ThreadReadPresentationTest.kt"
   - "demos/koog-contained-tool/src/test/kotlin/io/mazewall/demo/koog/PortalPresentationTest.kt"
   - "integrations/koog/README.md"
   - "demos/spring-boot-sandbox/README.md"
   - "demos/spring-boot-sandbox/build.gradle"
   - "demos/spring-boot-sandbox/src/main/java/demo/boot/ReportService.java"
   - "demos/spring-boot-sandbox/src/main/java/demo/boot/Reports.java"
   - "demos/spring-boot-sandbox/src/main/java/demo/boot/ReportConfiguration.java"
   - "demos/spring-boot-sandbox/src/main/java/demo/boot/ReportBootstrap.java"
   - "demos/spring-boot-sandbox/src/main/java/demo/boot/ReportApplication.java"
   - "demos/spring-boot-sandbox/src/main/java/demo/boot/UnsupportedReportService.java"
   - "demos/spring-boot-sandbox/src/main/java/demo/boot/ReportScenarioService.java"
   - "demos/spring-boot-sandbox/src/main/java/demo/boot/ReportVerification.java"
   - "demos/spring-boot-sandbox/src/test/java/demo/boot/ReportServiceTest.java"
   - "demos/spring-boot-sandbox/src/test/java/demo/boot/BootPresentationTest.java"
   - "src/consumerVerification/kotlin/io/mazewall/verification/SpringBootPackagedAcceptanceTest.kt"
   - "src/consumerVerification/kotlin/io/mazewall/verification/OptionalIntegrationAcceptanceTest.kt"
   - "src/consumerVerification/kotlin/io/mazewall/verification/SpringBootMeasurementOwnershipTest.kt"
   - "tests/consumers/spring-boot/README.md"
target_symbols: ["readWorkspaceFile", "threadReadTool", "ReportService", "ReportBootstrap"]
open_questions: false
needs_kernel: true
core_lock: false
autonomy: "supervised"
has_side_effects: true
---

# 🟡 [Severity: MEDIUM]: Make core Koog and Spring examples independently runnable and copyable

**Context:**
Implement the approved inline example-usability plan: a HotSpot workspace core entry point, independent Koog thread and Portal entry points, a normal Spring report path separate from verification scenarios, and short verified guides. Preserve core/library APIs, policies, dependency versions, external review, finite budgets and all existing acceptance controls; no full agent loop or HTTP/Security expansion.

**Needed:**
1. TDD the core workspace read with allowed, real denied-sibling, missing-file and physical-retirement controls before adding its runnable entry point.
2. Add independent runnable Koog Portal and bounded strict-UTF-8 thread examples using existing adapters and separate fresh JVMs; preserve all current process/denial/retirement tests.
3. Separate the normal Spring report service from its verification scenarios, bind actual normal/verification/unsupported classes independently, and qualify the normal finished-JAR path plus all existing negatives.
4. Lead each guide with prerequisites, verified commands/results and copyable source; add guided exercises and retain the existing unfamiliar-human rehearsal gate.
5. Commit executable inputs before exact-publication acceptance; run focused tests, consumer harness, optional integration acceptance, one full build and one independent final review. Require exact-head hosted CI before operator-approved merge/publication.

**Execution constraints:** Native inline execution in the current checkout; no
worktrees, separate plan/evidence documents or execution journals. No library API,
dependency-version, core enforcement, timeout, suppression or fallback changes.
Keep fixed worker/admission limits, platform-only installation, real external
finished-JAR approval, no-clobber resource ownership and physical-retirement checks.
Full agent loops, live-model configuration and HTTP/Security expansion are deferred
by the approved first-pass scope. Core, Koog and Spring remain independently usable.

**Pre-flight:** The three code slices share no execution interfaces; only their
guide links meet in the final documentation task. Spring's existing measurement,
approval, required-binding and finished-JAR harness consume the selected actual
implementation class, so every target-selection change must reach those callers.

**Task 1:** The compiling unconfined read baseline fails because it successfully
reads the private sibling. Owned dispatch then passes the allowed/denied/missing-
file/subsequent-read native contract, the four existing enforcement presentation
cases, and the actual runnable workspace command without skips. The complete CLI
module suite passes all 37 tests with zero skips; backlog and whitespace checks pass.

**Ruling:** Runnable example tasks use their actual packaged demo JAR plus runtime
dependencies. Gradle's source runtime classpath includes a nonexistent NO-SOURCE
Java output directory; required resource capture correctly rejects that path.
Packaging removes the nonexistent entry rather than weakening resource resolution
or silently filtering missing artifacts. The Koog runnable tasks use the same shape.

**Task 2:** Host reader tests first reject an unbounded compiling baseline at
65,537 bytes; the native registry contract first fails because missing argument
validation reaches the filesystem instead of rejecting before admission. The
bounded strict-UTF-8 reader and real ThreadToolAdapter validators then pass. The
two-stage producer wrapper runs the entire Koog demo test suite, both presentation
cases, generated Portal package check and both separate fresh-JVM runnable commands
successfully. Portal presentation uses the same application path, retaining actual
child identity/lifecycle, immutable diagnostics, bounds and physical-death checks.

**Task 3:** The host RED exposes the old service's fixture-only owned-worker
metadata requirement even inside a real JDBC transaction. The finished-JAR RED
rejects the not-yet-implemented normal measurement command. Ordinary report code
now accepts a bounded business label and commits exactly once; invalid labels
reject before SQL/file effects. The separate selected verification Class
keeps every existing denial, rollback, unsupported-binding and held-cleanup case.
Two host tests, four finished-JAR presentation tests and the native parent matrix
pass with zero skips. The parent executes all nine acceptance scenarios and 21
existing/symlink/hardlink/directory-alias controls, including the new normal target.

**Ruling:** The standalone Spring `test` task explicitly uses the existing JUnit
Platform dependencies and excludes the already-separated presentation tag, so
ordinary host tests actually execute. Native worker observation moves to the
existing trusted bootstrap context bridge; ordinary `ReportService.generate`
contains neither fixture modes nor a worker-observation hook. Its selected Class,
review/generation, unchanged advice and actual physical retirement remain required.

**Task 4:** Root/demo/presenter guides now lead with three independent runnable
paths, prerequisites, expected observations, copyable source and guided changes.
The existing real-human rehearsal item stays open; no agent timing/comprehension
claim replaces it. Full agent loops, live models and HTTP/Security remain follow-ups.

**Ruling:** The three new runnable tasks are selected by the existing optional
producer/acceptance phase so exact-head CI exercises the documented entry points,
not just adjacent test helpers. No workflow/job or verification budget is added or
increased. Focused native/host gates pass; completion and post-review checkpoints
are recorded below.

**Completion checkpoint:** Exact committed producer `6c9f819cd808e7423536035e0cdac7389b2a522d`
passes optional integration acceptance in 8m05s. Inspected fresh-clone output
confirms all three runnable entry points executed, four Boot presentation cases
passed without skips, and the existing native phases plus 21 ownership controls
passed. The full build on that source passed in 5m14s.

**Final review correction:** The one independent review found one Important gap:
the optional Spring phase selected only bootJar/presentationTest, omitting the two
new ordinary-service host tests. Replaying that exact completed source-bound
command reproduces RED: no ReportServiceTest JUnit report exists. Adding `test`
to the same invocation produces both passing host cases, zero skips/failures.
The parent now requires that exact two-case successful JUnit suite, with external
XML entities/DTDs disabled, so later task omission or skipped cases cannot pass.
Consumer host tests, ktlint and SpotBugs pass. Post-correction exact committed
producer `d754da35a6e819791ef09eaa1755342e2d8e9185` passes the complete optional
aggregate in 7m06s, including the new ordinary-service host-JUnit gate, all three
runnable entry points, four Boot presentations and every prior native/ownership
phase. The full build then passes in 5m12s with configured prerequisite skips
unchanged. Exact-head hosted CI and operator merge approval remain pending;
no second review is needed after the verified correction pass.

**Final review boundaries:** Full agent/live-provider/HTTP/Security expansion,
hostile-Java/shared-heap/preopened-FD/concurrent-deployment guarantees and human
usability/timing remain outside this scoped implementation. Human rehearsal and
operator merge/publication approval remain separate, not silently certified by
the engineering checks. No Critical or Minor findings were reported.

## Subsequent ponytail review

The user requested remediation of two additional findings on PR559:
[stale final lifecycle state](issue-20261025-111413-642-publish-final-report-lifecycle-state-only-after-ac.md)
and [undeclared Clang/BPF source-build prerequisites](issue-20261025-111426-953-declare-clang-bpf-prerequisites-for-source-built-c.md).
These are tracked separately with their actual reproductions and unchanged-budget
corrections. Updated exact-head qualification is required before merge; earlier
green CI is not used to waive either finding.

Both follow-ups are implemented with deterministic native / cold-project evidence.
Exact committed producer `3126b772995c5779c86ebb6706d295f8e44b0af7` passes optional
acceptance in 9m52s and the full build in 5m20s. A scoped independent review reports
no remaining findings; updated PR559 exact-head CI is still required before merge.

Updated run `40191302429` exposed a distinct single-admission workspace race:
result delivery preceded lease release, and the following read was rejected.
[The scoped demo-await correction](issue-20261025-121925-834-await-execution-end-before-reusing-single-admissio.md)
waits for the original handle's execution end without changing core semantics,
capacity, budgets, or adding retries. The two prior review fixes remain intact;
no failed CI gate is waived or rerun merely to obtain green.

---
<!-- id: issue-20261025-010324-590-make-core-koog-and-spring-examples-independently-r  file: issue-20261025-010324-590-make-core-koog-and-spring-examples-independently-r.md -->
