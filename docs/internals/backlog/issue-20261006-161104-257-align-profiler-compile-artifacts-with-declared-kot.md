---
title: "Align profiler compile artifacts with declared Kotlin friend paths"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "profiler/build.gradle.kts"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Align profiler compile artifacts with declared Kotlin friend paths

**Context:**
Java-library API variants expose enforcer classes directories while profiler friend paths point only to its JAR; fresh profiler compilation rejects existing internal Landlock imports.

**Needed:**
1. Require JAR library elements for profiler compile classpaths and verify fresh compilation with the existing friend boundary.

## Evidence

- During C2 profiler caller migration, fresh `:profiler:compileKotlin` failed on the existing internal Landlock import. The dependency variant report showed `classes` compile elements, while configured Kotlin friend paths named enforcer/platform JARs.
- Profiler compile classpaths now request JAR library elements, matching the existing explicitly declared friend boundary. Enforcer internals stay internal; no visibility or compiler metadata gate was weakened.
- `./gradlew :profiler:test :profiler:compileIntegrationTestKotlin :enforcer:checkKotlinAbi` passed after the variant correction and intentional DSL fixture migration. The full profiler main/test/integration compilations used the corrected artifact selection.
- The subsequent root build, host integration/fresh-JVM gates, and full local Docker test gate passed with this artifact selection.

---
<!-- id: issue-20261006-161104-257-align-profiler-compile-artifacts-with-declared-kot  file: issue-20261006-161104-257-align-profiler-compile-artifacts-with-declared-kot.md -->
