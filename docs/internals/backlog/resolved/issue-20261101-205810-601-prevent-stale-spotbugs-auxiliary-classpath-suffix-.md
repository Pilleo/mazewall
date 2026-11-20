---
title: "Prevent stale SpotBugs auxiliary classpath suffix after dependency graph shrink"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "buildSrc/src/main/java/io/mazewall/build/SpotBugsToolchainPlugin.java"
target_symbols:
  - "SpotBugsToolchainPlugin"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Prevent stale SpotBugs auxiliary classpath suffix after dependency graph shrink

**Context:**
Careful master merge normal pre-commit build failed after 24m50s at spotbugsConsumerVerification: errors=0 missingClasses=2 for org.apiguardian.api.API and API$Status, no BugInstance findings. Generated build/spotbugs/auxclasspath/spotbugsConsumerVerification retained a stale suffix, joining apiguardian-api-1.1.2.jar to the tail of junit-jupiter-api-5.10.2.jar. Official SpotBugs Gradle plugin 6.5.5 SpotBugsRunner.kt lines113-125 writes auxiliary classpath with StandardOpenOption.WRITE but not TRUNCATE_EXISTING. Failure evidence, raw metadata and prior outputs retained in /tmp/opencode/master-merge-mr5fp6z4/. Generated stale file relocated for clean regeneration without modifying sources, dependency pins, or weakening completeness checks. This is an integration cache-migration workaround, not a permanent plugin fix.

**Needed:**
1. Add a shrinking-classpath regression and choose a scoped safe auxiliary metadata regeneration fix or separately approved plugin upgrade; preserve failure checks and existing dependency locks.

## Resolution — 2026-11-02

The shared SpotBugs plugin now regenerates only the executing task's configured
auxiliary-classpath file before analysis. Deletion failures propagate with their
IOException cause; report completeness, dependency pins, output caching and
configuration caching remain enabled. This supersedes the historical manual
migration below without removing its evidence.

The actual shrinking-classpath regression failed before the fix, then passed.
Focused checks cover an executing deserialized action with identical arguments,
exact regenerated metadata, up-to-date/cache restoration, another task's file,
and IOException propagation. Independent review's cache-execution coverage gap
was corrected. True permission-denied behavior was not separately tested; the
failure fixture uses a nonempty directory.

The unchanged full build and portal gate passed with configuration-cache reuse:
233 tasks, 104 executed, 4 from cache, 125 up-to-date, 13m 1s. Logs and result:
`/tmp/opencode/profiler-resume-dfx7y2wp/completion-gate-formatted-buvjg_wv/`.
Focused red/green and review evidence: `spotbugs-fix.md`, `spotbugs-review.md`,
and `spotbugs-review-fix-test.log` in the same resume evidence directory.

**Local workaround evidence:** The unchanged `:spotbugsConsumerVerification` task
passed in 21 seconds after regenerating only this metadata file. Its XML reports
zero errors and zero missing classes; the API Guardian path is valid again. The
full commit gate must still pass. No permanent source fix or plugin upgrade is
claimed.

The next cached full run reused its configuration but exposed the same stale
suffix in profiler and portal-runtime annotation paths. Their reports contain
zero bug instances and two missing annotation classes each. A bounded scan of
12 generated auxiliary-classpath files found five malformed files across the
profiler, portal modules and vulnerable-web-app demo. Each was retained before
same-filesystem relocation; ordinary valid metadata was left alone. Evidence is
`malformed-spotbugs-aux-migration.json` under the retained merge directory. The
five owning analyzer tasks are checked separately before the next full gate.

All five focused analyzers passed in 1 minute 40 seconds after regeneration;
their XML reports contain zero analysis errors, missing classes and bug instances.
This establishes the metadata workaround, not a permanent plugin correction or
a passing full commit gate.

---
<!-- id: issue-20261101-205810-601-prevent-stale-spotbugs-auxiliary-classpath-suffix-  file: issue-20261101-205810-601-prevent-stale-spotbugs-auxiliary-classpath-suffix-.md -->
