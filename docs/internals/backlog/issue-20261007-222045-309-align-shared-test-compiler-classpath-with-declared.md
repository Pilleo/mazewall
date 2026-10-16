---
title: "Align shared test compiler classpath with declared friend JARs"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "build.gradle.kts"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Align shared test compiler classpath with declared friend JARs

**Context:**
Fresh full build run-898ecce8d097 exposed compileSharedTestKotlin rejecting internal PlatformProvider. Root shared fixtures name platform/enforcer JAR friend paths but compileClasspath can select Java-library classes-directory variants, matching the previously repaired profiler visibility mismatch.

**Needed:**
1. Select JAR library elements for sharedTestCompileClasspath and verify actual shared fixture compilation without widening internal visibility.

**Evidence (pending repository review):**
- Fresh build `run-898ecce8d097` failed compileSharedTestKotlin on internal PlatformProvider access. The shared compilation now selects JAR library elements matching its existing platform/enforcer friend paths.
- Actual `./gradlew compileSharedTestKotlin` passed (`run-a0e4077f13a7`); full fresh host build (`run-87b7f35d556f`) and fresh Docker gates (`run-a7557f9c33a8`) passed. Internal visibility is unchanged.

---
<!-- id: issue-20261007-222045-309-align-shared-test-compiler-classpath-with-declared  file: issue-20261007-222045-309-align-shared-test-compiler-classpath-with-declared.md -->
