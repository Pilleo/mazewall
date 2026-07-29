---
title: "Integrate RefactorFirst Gradle Plugin to Prioritize Architectural Cleanups"
severity: "LOW"
status: "open"
priority: 10
dependencies: []
component: "ci"
target_modules:
  - ":enforcer"
  - ":profiler"
  - ":tools:orchestrator"
target_files:
  - "build.gradle.kts"
effort: "medium"
autonomy: "autonomous"
---

# 🟢 [Severity: LOW]: Integrate RefactorFirst Gradle Plugin to Prioritize Architectural Cleanups

**Context:**
As `mazewall` expands from a Proof-of-Concept to a production-grade library, maintaining a clean and decoupled codebase is critical. Currently, identifying which classes are high-priority refactoring candidates (due to high coupling, high cyclic complexity, and low cohesiveness) is done ad-hoc by human reviewers or AI agents.

**The Opportunity:**
Integrating the **RefactorFirst** Gradle plugin (or equivalent static structural metrics engine) directly into the root `build.gradle.kts` introduces a data-driven, objective feedback loop.
RefactorFirst evaluates codebase coupling (Efferent/Afferent coupling) alongside complexity (WMC - Weighted Method Complexity) to calculate a "Priority Index". This pinpoints exactly which classes are "God Classes" or "Highly Coupled Spaghettis" that should be refactored first to maximize future development velocity and reduce regression risk.

**Needed:**
1. Configure the `org.refactorfirst.gradle.plugin` (or equivalent open-source complexity metric analyzer) in the root Gradle file.
2. Add a `./gradlew refactorFirst` task that scans the `:enforcer`, `:profiler`, and `:tools:orchestrator` main source sets and generates an HTML/JSON structural analysis report inside `build/reports/refactorFirst/`.
3. Introduce an architectural barrier (fitness function) where classes above a certain complexity-priority score trigger warnings during CI, helping developers focus refactoring effort on the most impactful regions of the codebase.
