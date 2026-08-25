---
title: "Test Infrastructure Hygiene: Stale Result Dirs, Broken --tests Filters, Up-to-Date Bisect Pitfalls"
severity: "MEDIUM"
status: "resolved"
priority: high
component: "testing"
target_modules:
  - ":enforcer"
  - ":profiler"
target_files:
  - "enforcer/build.gradle.kts"
  - "scripts"
effort: "medium"
autonomy: "autonomous"
open_questions: false
dependencies: []
---

# 🟠 [Severity: MEDIUM]: Test Infrastructure Hygiene

**Context:** Three concrete failure modes were hit repeatedly during the 2026-08-23 session, each
capable of producing WRONG conclusions during debugging:

1. **Stale result directories.** `enforcer/build/test-results/integrationTestFreshJvm` retained XML
   from a previous source revision; a renamed test method kept "failing" with its old name after
   `--rerun-tasks`, because results were read instead of the live run.
2. **Silently ineffective `--tests` filters.** `./gradlew :enforcer:integrationTest --tests X`
   fails with "No tests found" for classes routed to `integrationTestFreshJvm` (e.g. any
   `@NeedsFreshJvm` class), and there is no hint pointing at the correct task.
3. **Up-to-date caching invalidates stash-bisects.** Reverting sources to HEAD makes Gradle treat
   test tasks as up-to-date against pre-existing results, so a "baseline passes" check can be
   vacuous unless `cleanTest`/`--rerun-tasks` is forced (cost several wasted cycles diagnosing
   issue-20260823-140500).

**Resolution (2026-08-23):**
1. Root task `./gradlew cleanAllTestResults` wipes every module's `build/test-results`.
2. Configuration-time scan detects CLI `--tests` patterns matching only `@NeedsFreshJvm` classes
   and emits `[FILTER HINT]` pointing to `:enforcer:integrationTestFreshJvm` (Gradle 9 does not
   expose CLI patterns to Test tasks; tag detection matches the NeedsFreshJvm annotation reference
   since meta-annotated tags don't appear in test-class bytes).
3. AGENTS.md §5 documents both plus mandatory bisect pairing with
   `cleanAllTestResults --rerun-tasks`.

**Needed:**
1. Add a `cleanAllTestResults` convenience task wiping every module's `test-results` dirs (all
   variants), documented in AGENTS.md §5 alongside the existing commands.
2. Make `integrationTest`/`integrationTestFreshJvm` fail with an actionable message when a
   `--tests` filter matches zero classes, listing which task owns the filtered class names
   (scan compiled integration-test class dirs for the pattern).
3. Document in root AGENTS.md §5: bisect protocol MUST pair source reverts with
   `cleanAllTestResults --rerun-tasks`; add the same note to `.agents/skills/review/SKILL.md`.
4. Optional CI guard: after each test task, assert every result XML's timestamp is newer than the
   oldest compiled class it covers (cheap staleness tripwire).

