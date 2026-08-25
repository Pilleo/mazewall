---
title: "Gradle 9 Cross-Module Execution-Time Resolution: Canonical Pattern + Build-Script Lint"
severity: "MEDIUM"
status: "open"
priority: high
component: "ci"
target_modules:
  - ":portal"
  - ":platform"
target_files:
  - "portal/build.gradle.kts"
  - "scripts"
effort: "medium"
autonomy: "autonomous"
open_questions: false
dependencies: []
paperclip_issue_id: 448cac7b-35e9-46eb-9009-60323c3571cb
---

# 🟠 [Severity: MEDIUM]: Gradle 9 Cross-Module Execution-Time Resolution — Canonical Pattern + Build-Script Lint

**Context:** Debugging the portal worker CNFE took hours because four distinct Gradle 9 pitfalls
stacked on top of each other, and each fix revealed the next:
1. Resolving a configuration at **configuration time** (`config.asPath` inside `systemProperty`)
   → "without an exclusive lock" hard error.
2. Wrapping it in `providers.provider { }` → loses task dependencies; producer classes are never
   built before consumption → empty paths / ClassNotFoundException at execution.
3. Passing raw source-set unions through CC-serialized providers → `UnionFileCollection`
   resolution errors at CC store.
4. Referencing script-delegated vals from `doFirst`/provider classes → "cannot serialize Gradle
   script object references".

The working canonical pattern (now in `portal/build.gradle.kts`): a resolvable **Configuration**
consumed by an `@Classpath`-annotated `CommandLineArgumentProvider` (implicit producer deps +
CC-safe fingerprinting) PLUS explicit `dependsOn(":module:classes")`, with string conversion
deferred to execution. Additionally: Python/heredoc generation of build scripts can silently emit
`${'$'}`-escaped Kotlin templates that never interpolate (the literal `${workerCp.asPath}`
classpath bug) — generated Kotlin must be verified for interpolation.

**Needed:**
1. Extract a reusable helper (convention plugin or shared function in `buildSrc`) implementing
   the pattern: `workerClasspathArgProvider(consumerTask, producerProjectPath, propertyName)`.
2. Add a lightweight lint (script or check task) that fails when a `*.gradle.kts` file contains
   `.asPath` (or `.singleFile`) OUTSIDE of a `doFirst`/`doLast`/provider lambda — i.e., eager
   configuration-time resolution of resolvable configurations.
3. Document the pattern + pitfalls in the repo (AGENTS.md build section or
   `docs/internals/designs/core/architectural-map.md` build subsection).
4. Note for AI agents: Kotlin snippets produced via shell heredocs must escape `$` correctly AND
   be greppable afterwards (`grep -F '${'` over written files) to catch non-interpolating
   templates.

