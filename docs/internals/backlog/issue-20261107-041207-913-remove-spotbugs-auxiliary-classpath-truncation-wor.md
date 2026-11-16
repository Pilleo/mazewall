---
title: "Remove SpotBugs auxiliary classpath truncation workaround when upstream is fixed"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "buildSrc/src/main/java/io/mazewall/build/SpotBugsToolchainPlugin.java"
  - "buildSrc/src/test/java/io/mazewall/build/RootVerificationFunctionalTest.java"
target_symbols:
  - "SpotBugsToolchainPlugin"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Remove SpotBugs auxiliary classpath truncation workaround when upstream is fixed

**Context:**
SpotBugs Gradle plugin 6.5.5 retains stale suffix bytes when rewriting a shorter auxiliary classpath; the build convention resets only the configured generated file before execution.

**Needed:**
1. Track the upstream fix and remove the workaround only after the real analyzer regression passes with the updated plugin.

---
<!-- id: issue-20261107-041207-913-remove-spotbugs-auxiliary-classpath-truncation-wor  file: issue-20261107-041207-913-remove-spotbugs-auxiliary-classpath-truncation-wor.md -->
