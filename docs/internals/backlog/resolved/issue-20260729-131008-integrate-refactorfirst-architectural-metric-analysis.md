---
title: "Integrate RefactorFirst Gradle Plugin to Prioritize Architectural Cleanups"
severity: "LOW"
status: "resolved"
priority: high
dependencies: []
component: "ci"
target_modules:
  - ":platform"
  - ":enforcer"
  - ":profiler"
  - ":portal"
  - ":portal-codegen"
  - ":portal-worker"
target_files:
  - "build.gradle.kts"
  - "scripts/refactor_first.sh"
  - "CONTRIBUTING.md"
effort: "medium"
autonomy: "autonomous"
paperclip_issue_id: "ca262b38-cd1e-49aa-9a20-291f36ebcf43"
paperclip_identifier: "MAZ-1080"
---

# 🟢 [Severity: LOW]: Integrate RefactorFirst Gradle Plugin to Prioritize Architectural Cleanups

**Context:**
As `mazewall` expands from a Proof-of-Concept to a production-grade library, maintaining a clean and decoupled codebase is critical. Currently, identifying which classes are high-priority refactoring candidates (due to high coupling, high cyclic complexity, and low cohesiveness) is done ad-hoc by human reviewers or AI agents.

**The Opportunity:**
Integrating the **RefactorFirst** Gradle plugin (or equivalent static structural metrics engine) directly into the root `build.gradle.kts` introduces a data-driven, objective feedback loop.
RefactorFirst evaluates codebase coupling (Efferent/Afferent coupling) alongside complexity (WMC - Weighted Method Complexity) to calculate a "Priority Index". This pinpoints exactly which classes are "God Classes" or "Highly Coupled Spaghettis" that should be refactored first to maximize future development velocity and reduce regression risk.

**Needed:**
1. Keep RefactorFirst informational and manually invoked so architectural analysis cannot slow or fail ordinary verification.
2. Analyze only committed production sources in `platform`, `enforcer`, `profiler`, `portal`, `portal-codegen`, and `portal-worker`; exclude linked worktrees, tests, demos, build logic, and orchestrator sources structurally.
3. Generate the HTML report at `build/reports/refactorFirst/refactor-first-report.html` with deterministic inputs and explicit tool-version preflights.

**Resolution (2026-09-09):** `refactorFirstReport` is now an opt-in Gradle task backed by
an isolated sparse clone of committed `HEAD`. The runner preserves Git history for change-proneness
metrics, rejects dirty scoped sources, checks Java and Maven requirements, invokes RefactorFirst
0.10.0, publishes the report atomically, and removes its temporary clone on every exit path.
The ordinary `build`, `check`, `unitCheck`, and `kernelCheck` graphs do not include the report.

**Evidence:** The runner's black-box test covers all six source roots, structural exclusions,
dirty-source rejection, unrelated documentation changes, Java/Maven version failures, Maven
failure, atomic output preservation, and cleanup after success, failure, and interruption.
The real linked-worktree run analyzed 268 Kotlin production files and generated an 818,376-byte
report. Its links include every scoped module and no `.worktrees`, test, demo, `buildSrc`, or
orchestrator paths. `./gradlew --no-daemon build --dry-run` omitted both RefactorFirst tasks,
while the explicit report task completed successfully with the configuration cache reused.
