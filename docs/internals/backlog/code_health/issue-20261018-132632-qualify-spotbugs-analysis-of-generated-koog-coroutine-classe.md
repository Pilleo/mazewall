---
title: "Qualify SpotBugs analysis of generated Koog coroutine classes"
severity: "LOW"
status: "open"
priority: low
dependencies: []
component: "testing"
target_modules:
  - ":integrations:koog"
  - ":integrations:spring:mazewall-spring-boot"
target_files:
  - "buildSrc/src/main/kotlin/mazewall.quality-conventions.gradle.kts"
  - "integrations/koog/build.gradle.kts"
  - "integrations/koog/src/main/kotlin/io/mazewall/koog/ThreadInvocationAwait.kt"
  - "integrations/spring/mazewall-spring-boot/build.gradle.kts"
needs_kernel: false
core_lock: true
effort: "small"
autonomy: "supervised"
open_questions: false
has_side_effects: true
---

# 🟢 [Severity: LOW]: Qualify SpotBugs analysis of generated Koog coroutine classes

**Context:**
The successful optional Koog build reports missing classes during `:integrations:koog:spotbugsMain`: new `io.mazewall.koog.ThreadToolAdapter$execute` and existing `io.mazewall.koog.PortalToolAdapter$execute$2`. The configured analyzer exits zero with `ignoreFailures=false`, but that does not establish complete generated-coroutine analysis. The new adapter-specific occurrence must not be described as entirely pre-existing or silently dismissed as full coverage.

This is a separately scoped tooling/evidence follow-up, not a demonstrated runtime defect or permission to weaken the analyzer. No analyzer/dependency configuration is changed in the thread feature. The legacy scaffolder's recognized module is coarse routing metadata; the explicit build/plugin paths define this future tooling scope.

**Needed:**
1. Reproduce and identify whether the missing generated-coroutine names arise from analyzer compatibility, classpath selection or generated-class naming; inspect the actual compiled classes rather than assuming a missing runtime dependency.
2. Correct the owning tooling/configuration with a focused regression proving meaningful analysis for both adapter shapes. Do not turn off the analyzer, set ignoreFailures, add broad exclusions or upgrade dependencies without approval.
3. Retain typed Detekt, host/native behavior and manual review as independent evidence, not substitutes for a claim that the missing-class diagnostic was fixed. Run the real optional build and configuration-cache checks after a correction.

## Investigation
- `.superpowers/sdd/2026-10-18-koog-thread-enforcement/optional-build-first.log:76-79` contains both exact names. The build exits zero and configuration-cache reuse subsequently passes.
- That workspace's independent `final-review.md:33-35` records the limitation and the unchanged quality convention's ignoreFailures=false setting. No current production source fix was requested by the review.

### 2026-10-19 reproduced causes and remaining qualification

The original task was rerun with the pinned Gradle plugin6.5.5, SpotBugs4.9.8
and FindSecBugs1.13.0. All nine real Koog classes are in its actual analysis input;
the two reported names are not compiled classes. `javap` confirms correct real
`EnclosingMethod` attributes. Adding a runtime dependency is not justified.

Root `onlyAnalyze` masks used `io.mazewall.*` and `demo.vulnapp.*`. SpotBugs treats
`.*` as one exact package, not a recursive namespace. Koog method detectors were
therefore screened out. XML `ClassStats` listing all nine classes is insufficient
evidence of method analysis. Debug traces place the phantom enclosing lookups in
`UnreadFields.report:1171`, after it removes a numeric `$` suffix from generated
class names. The separate [recursive package coverage issue](../issue-20261019-013721-472-restore-recursive-project-package-coverage-in-spot.md)
contains the actual-root-convention regression and minimal selector correction.

Analyzing the same real inputs without the bad restriction removes those two
phantom-enclosing diagnostics but exposes independent limitations:

- FindSecBugs' `BasicInjectionDetector.getInjectionPoint:79` requests nonexistent
  classes named `invoke` and `call` while visiting `invokedynamic` instructions.
  The core-only diagnostic run has zero missing classes. This is evidence of a
  plugin compatibility problem, not absent application dependencies. Upstream
  version1.14.0 guards `BCELUtil.getSlashedClassName` for `INVOKEDYNAMIC`; no upgrade
  has been installed or qualified here.
- Core SpotBugs reports `NP_NONNULL_PARAM_VIOLATION` in generated
  `ThreadInvocationAwaitKt$awaitThreadInvocation$1.invokeSuspend`. Bytecode passes
  a null placeholder on coroutine reentry with the resume-label high bit set;
  resumed label1 restores the handle/context and does not call `submit` again.
  The reported site is compiler-generated continuation machinery, not a source
  call passing a null submitter. This explains the diagnostic but does not make
  the analyzer gate pass or establish complete coroutine-aware analysis.
- Maximum effort retains both classes of diagnostic; removing the security
  plugin diagnostically eliminates only its missing-class errors. Neither
  diagnostic configuration is a proposed production workaround.

Raw argv, input lists, XML reports, stack traces and exit statuses are retained
under `.superpowers/sdd/2026-10-19-koog-spotbugs-qualification/`. The real namespace
regression failed before correction and passed afterward. Complete optional build,
configuration-cache acceptance and a supported resolution of the newly visible
findings remain required. This item remains **open**; no exclusions, detector
disabling, source distortion or dependency upgrade are authorized by this evidence.

### Further isolated qualification (before project adoption)

- A dependency-free Java22 lambda probe reproduces FindSecBugs1.13.0's fake
  `call`/`run` missing classes, while stock core analysis is clean. This is not
  specific to Kotlin, Koog or an absent application JAR.
- Temporary standalone comparisons—not project dependency changes—show
  FindSecBugs1.14.0 removes the dynamic-name errors with both core4.9.8 and4.10.5.
  Actual Koog analysis still reports the coroutine non-null-placeholder finding.
  Stable Kotlin2.4.20 reproduces that core finding too. Official core4.10.5 still
  detects a deliberate `NP_ALWAYS_NULL` control, so its remaining warning is not
  explained by method selection being disabled again.
- A temporary upstream-source matcher for coroutine ABI re-entry was **rejected**.
  Its first positive controls passed, but independent review demanded matching
  near-miss controls. Removing the resume-label update or using a parameter before
  restoration preserves its metadata predicates, causes real runtime NPEs, and is
  warned about by stock SpotBugs—but silently missed by the prototype. Metadata
  matching is not a sound fix or an acceptable generated-code exemption.

Receipts, verified official distribution/source hashes, exact argv, source diff,
runtime failures and analyzer XML are under
`.superpowers/sdd/2026-10-19-koog-analyzer-reproducer/`. The initial minimal
runtime probe did not force suspension; later `prototype/forced-abi/` controls
do, so its early “100 resumes” stdout must not be treated as re-entry evidence.
At that checkpoint all project analyzer pins, dependency locks/checksums and
production adapter/helper sources remained unchanged. That investigation did not authorize
adopting the rejected prototype or claim that the whole build is passing.

### Operator-approved correction and focused acceptance

The operator then approved strict reviewed tool-only corrections, followed by a
**small internal helper refactor** instead of a substantial coroutine-analysis fork.
The unchanged `awaitThreadInvocation` signature tail-delegates to a private holder's
suspend method. Generated resumption retains its real receiver, eliminating the
null submit-parameter placeholder without changing submission context, IO waiting,
cancellation propagation or failure unwrapping. It adds one small holder allocation;
no measured performance or adapter-overhead claim follows.

Core4.10.5/FindSecBugs1.14.0 and the
[four-family invalid-fact corrections](../../../../config/spotbugs/patches/README.md)
are now wired only to analyzer JVMs. Existing checksums are retained; lock review
confirms changes are limited to analyzer configurations. The newly strict report
gate also exposed Spring's missing optional Reactor signatures; their BOM-aligned
dependency is supplied only to the analyzer, not published application requirements.

All eight previously failing analyzer tasks now report zero errors/missing classes,
and the complete Koog host suite passes. The final Maven-resolved toolchain still
detects both genuine near-match coroutine NPE controls and null dereferences
injected into both actual adapter bodies. Removing each invalid-fact patch family
independently restores the corresponding actual-module analysis failure.
The unsafe matcher/contradictory annotations remain rejected; no coroutine detector
or class exclusion was adopted. The corrected full optional build exits0, with
healthy test aggregation; configuration-cache reuse was verified while both real
adapter analyzers executed. Independent review's one Important recipe finding was
fixed and checked; a minor TestKit escaped-path limitation is deferred. This issue
remains open for scoped integration, not because an analyzer gate is still red.
Fresh registry retrieval is separately permission-blocked. Evidence is under
`.superpowers/sdd/2026-10-19-strict-analyzer-repair/`.

## Side effects
- Tooling-only follow-up: missing generated coroutine classes limit analyzer evidence; do not weaken ignoreFailures or change runtime behavior in the Koog feature.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20261018-132632  file: issue-20261018-132632-qualify-spotbugs-analysis-of-generated-koog-coroutine-classe.md -->
