---
title: "Provide a readable JUnit feature showcase for presentation"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "demos"
target_modules: []
target_files:
  - "build.gradle.kts"
  - "demos/cli-demo/src/test"
  - "demos/agent-sandbox-demo/src/test"
  - "demos/koog-contained-tool/src/test"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Provide a readable JUnit feature showcase for presentation

**Context:**
Five demo directories and independent consumer harnesses have separate setup paths; there is no canonical short feature tour. Existing CLI, agent and web scenarios already use JUnit and the owned IsolatedProcessTester. Operator correction: presentation entry points should be readable JUnit tests, not another shell launcher. Native prerequisites and permanent filters still require fresh owned child JVMs.

**Needed:**
1. Curate independent named JUnit tests tagged presentation in owning modules; use existing fresh-JVM fixtures and assertions, add scoped Gradle presentationTest selection and normal reports/IDE execution, and stage dependencies through existing builds before an offline rehearsal.

**Acceptance:** Each feature is an individually runnable IDE/JUnit test with descriptive display name, real positive/negative assertions and a short observed outcome trace. Selected Gradle tasks rerun actual tests with visible output and standard XML/HTML results; no new shell launcher, custom test engine or duplicate scenario implementation. Missing required native prerequisites, wrong artifacts, unexpected outcome and incomplete cleanup fail the showcase rather than silently skipping. Module tasks are serial; aggregate excludes no advertised feature without naming its separate optional consumer gate. Native filter installation stays in owned fresh child JVMs, with stdout forwarded by the existing helper.

---
<!-- id: issue-20261013-005316-772-provide-a-prepared-offline-presentation-demo-launc  file: issue-20261013-005316-772-provide-a-prepared-offline-presentation-demo-launc.md -->
