---
title: "Reuse nested Gradle TestKit caches across disposable CI checkouts"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "ci"
target_modules: []
target_files:
   - "buildSrc/build.gradle.kts"
   - "buildSrc/src/test/java/io/mazewall/build/TestKitCacheFunctionalTest.java"
   - "buildSrc/src/test/java/io/mazewall/build/VerificationActionsTest.java"
   - "docs/internals/designs/core/testing-strategy.md"
target_symbols: []
open_questions: false
needs_kernel: false
core_lock: true
effort: "small"
autonomy: "supervised"
has_side_effects: true
verify_cheap:
   - "./gradlew -p buildSrc testKitCacheTest --console=plain --max-workers=1"
   - "./gradlew -p buildSrc verificationActionsTest --console=plain --max-workers=1"
---

# 🟡 [Severity: MEDIUM]: Reuse nested Gradle TestKit caches across disposable CI checkouts

**Context:**
Follow-up to merged PR541. Warm CI still takes20m21s versus historical6m21s. Retained producer evidence shows Portal bootstrap7m55s with buildSrc verificationActionsTest executing many real nested Gradle fixtures, while subsequent producer test/publication stage took56s. TestKit cache currently lives at buildSrc/build/test-kit and is discarded in every disposable consumer checkout. Move it under the current Gradle User Home, preserving host/container isolation, all real regression cases and fresh native outcomes. Do not skip verification or change source/byte qualification.

**Needed:**
1. Add real two-fresh-directory TestKit regression: require the cache to follow the configured Gradle User Home and demonstrate compileJava reuse FROM_CACHE across independent fixtures. Preserve explicit `-g` overrides, including operator-chosen checkout-local homes.
2. Use the current Gradle User Home for both JavaExec verification and JUnit TestKit cache paths, without new dependency or cache archive.
3. Run focused RED/GREEN, complete build, independent review and exact committed native acceptance; compare hosted timings before claiming speedup.

## Verification checkpoint

The original checkout-local home failed the new real regression. The persistent
home passes with two independent fixture directories: the first compilation
executes and the second restores real bytecode `FROM-CACHE`. A final negative
control restoring the old task property fails the configured-home assertion.
The test follows the configured Gradle home rather than banning explicit local
`-g` overrides.

The complete optional local build passed (286 tasks), including the unchanged
mandatory build-tool suite. Independent review found no blocking defect; its
checkout-local `-g` compatibility concern was corrected. Backlog validation and
whitespace checks pass. Committed native acceptance and hosted timing remain
pending; this checkpoint makes no end-to-end speedup claim.

### Hosted boundary regression and correction

First hosted optional acceptance passed, but the main demo failed with exit124.
The absolute TestKit-home system properties were task inputs, so switching from
host to container and back reran the entire mandatory verification suite during
the demo's existing60-second packaging bound. Do not increase that bound or skip
verification to conceal this regression.

A JVM argument provider now models the relative cache layout as input and marks
the absolute Gradle home as internal cache transport. The actual task-input
regression failed against the raw system properties, then passed with the provider.
A two-invocation task probe executes verification first and is `UP-TO-DATE` when
only the configured transport home switches. Sources/classpaths remain inputs.
Final full-build and hosted requalification are required after this correction.

---
<!-- id: issue-20261019-203415-734-reuse-nested-gradle-testkit-caches-across-disposab  file: issue-20261019-203415-734-reuse-nested-gradle-testkit-caches-across-disposab.md -->
