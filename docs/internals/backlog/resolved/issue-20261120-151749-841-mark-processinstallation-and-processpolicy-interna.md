---
title: "Mark ProcessInstallation and ProcessPolicy internal constructors and accessors JvmSynthetic and validate null varargs"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "lockdown/src/main/kotlin/io/mazewall/lockdown/ProcessInstallation.kt"
target_symbols: 
  - "ProcessInstallation"
open_questions: false
---

# 🟡 [Severity: HIGH]: Mark ProcessInstallation and ProcessPolicy internal constructors and accessors JvmSynthetic and validate null varargs

**Context:**
Kotlin internal constructors and internal vals on ProcessInstallation and ProcessPolicy compile to public JVM bytecode without @JvmSynthetic, exposing constructible constructors and internal PolicyDefinition return types to Java callers; passing null in Java varargs throws NPE naming mangled internal getters.

**Needed:**
1. Annotate ProcessInstallation and ProcessPolicy constructors and internal getters with @JvmSynthetic, validate non-null varargs in Mazewall.installOnProcess, and verify public bytecode signatures via reflection in LockdownJavaApiTest.

## Resolution Evidence (2026-11-21)
- Made constructors `private` on `ProcessInstallation` (with `@JvmSynthetic internal fun create(...)` on its internal companion object) and `ProcessPolicy`, and marked internal properties (`runtime`, `definition`) `@get:JvmSynthetic` (`lockdown/src/main/kotlin/io/mazewall/lockdown/ProcessInstallation.kt`, `ProcessPolicy.kt`).
- Added `Objects.requireNonNull` checks on `first` and each `vararg more` element in `Mazewall.installOnProcess`.
- Verified in `LockdownJavaApiTest` (`:lockdown:test`) that zero non-synthetic public constructors or `$`-mangled internal methods/leaked types appear in the public JVM surface, and null varargs throw clean `NullPointerException` messages.

- Full completion gate verified in commits `52e81d2e1` and `7e62bacf7` (`./gradlew build -PmazewallKernelLane=BASELINE` and rootful Docker `CONFINED` kernel lane passed).
---
<!-- id: issue-20261120-151749-841-mark-processinstallation-and-processpolicy-interna  file: issue-20261120-151749-841-mark-processinstallation-and-processpolicy-interna.md -->
