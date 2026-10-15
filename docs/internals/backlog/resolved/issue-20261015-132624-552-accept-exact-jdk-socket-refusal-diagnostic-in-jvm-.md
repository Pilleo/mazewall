---
title: "Accept exact JDK socket refusal diagnostic in JVM floor probe"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: [":enforcer", ":profiler"]
target_files:
  - "src/sharedTest/kotlin/io/mazewall/enforcer/engine/JvmFloorWorkload.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/JvmFloorWorkloadTest.kt"
target_symbols:
  - "JvmFloorWorkload"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Accept exact JDK socket refusal diagnostic in JVM floor probe

**Context:**
CI run38741186203 correctly exercises the real default one-ms loopback Socket.connect probe, but Java25 emits Connection refused (connect failed). The existing exact-string classifier admits only Connection refused (SocketChannel form), so it rejects ordinary ECONNREFUSED. Admit only both exact refusal forms; retain identity and propagation of actual EPERM/EACCES and all unrelated IO failures.

**Needed:**
1. Add an isolated full-floor child case for the detailed Socket refusal string and prove it fails before the narrow classifier change; run existing actual permission-denial cases unchanged.

## Resolution

The detailed-refusal full-floor child fails against the original classifier
(`run-d38ae1e6b09c`). The helper now admits precisely `Connection refused` and
`Connection refused (connect failed)`. Other connection errors still propagate.
The default one-millisecond real connect remains unchanged.

All five isolated child cases pass (`run-259784b23295`): both refusal forms,
actual default probe, and real acquired EPERM/EACCES failures retaining identity.
An earlier whole-class attempt hit the separately tracked executable-mapping JVM
failure (`run-1ffce39ebb9f`); it was not hidden or called a test pass.

The full included-module build passes on OpenJDK25 (`run-c492ffafc9d7`,599028ms).

---
<!-- id: issue-20261015-132624-552-accept-exact-jdk-socket-refusal-diagnostic-in-jvm-  file: issue-20261015-132624-552-accept-exact-jdk-socket-refusal-diagnostic-in-jvm-.md -->
