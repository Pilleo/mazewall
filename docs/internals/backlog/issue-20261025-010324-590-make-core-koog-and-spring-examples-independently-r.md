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

---
<!-- id: issue-20261025-010324-590-make-core-koog-and-spring-examples-independently-r  file: issue-20261025-010324-590-make-core-koog-and-spring-examples-independently-r.md -->
