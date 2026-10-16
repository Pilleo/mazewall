---
title: "Grant exact classpath artifacts without admitting sibling files"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: [enforcer]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/PolicyBuilder.kt"
  - "enforcer/src/main/kotlin/io/mazewall/landlock/Landlock.kt"
  - "enforcer/src/test/kotlin/io/mazewall/ExactClasspathGrantTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/landlock/LandlockFailureModeTest.kt"
  - "enforcer/src/integrationTest/kotlin/io/mazewall/ExactClasspathGrantNativeTest.kt"
  - "docs/internals/designs/enforcer/containment-design.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Grant exact classpath artifacts without admitting sibling files

**Context:**
C4/A03: explicit JVM classpath grants and automatic Landlock runtime closure widen JAR files to their parent directory. Preserve exact file grants, directory grants only for actual class/resource directories, and file-compatible Landlock rights.

**Needed:**
1. Prove policy artifact granularity and actual-kernel denial of a sibling beside an admitted classpath JAR.

## Implementation and verification evidence

Explicit `allowJvmClasspath()` records each existing artifact's absolute path, retaining actual class/resource directories as directories. Automatic Landlock runtime closure follows the same granularity and removes directory-only read rights for classpath files. Required runtime path-open and add-rule failures reject installation instead of downgrading EPERM/EACCES to warnings.

The policy granularity and actual-kernel sibling-denial regressions failed before implementation (`run-fb868a4fd6da`) and passed after (`run-a33eb02287d9`). Two runtime-rule permission regressions independently failed with the old warning paths (`run-da375f6c912a`) and passed with required failures. A pre-existing fallback-rights fixture now targets `/nonexistent/file` explicitly rather than accidentally failing the first runtime classpath open. Full enforcer/profiler/ABI/quality checks passed (`run-356a12d51f86`).

`./gradlew build integrationTest integrationTestFreshJvm` passed in 6m47s, 272 tasks (85 executed, 187 up-to-date), after adopting repository error style without suppressing the warning ratchet (`run-f6f84a349e45`). Serial caller-mapped Docker gates passed in 55s (`run-bb4342a20507`). The actual-kernel artifact case has zero skips/failures/errors: the admitted JAR opens while a fresh sibling read is denied, and the caller can still read that sibling.

Prepared artifact identity/generation binding and the complete resource-rights model remain C4/C6 work. This issue stays open for repository review.

---
<!-- id: issue-20261007-080402-436-grant-exact-classpath-artifacts-without-admitting-  file: issue-20261007-080402-436-grant-exact-classpath-artifacts-without-admitting-.md -->
