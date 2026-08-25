---
title: "JvmChildProcess must strip JAVA_TOOL_OPTIONS so Graal JVMCI does not crash children"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "platform"
target_modules:
  - ":platform"
target_files:
  - "platform/src/main/kotlin/io/mazewall/core/JvmChildProcess.kt"
  - "platform/src/main/kotlin/io/mazewall/core/ProcessLauncher.kt"
  - "enforcer/src/test/kotlin/io/mazewall/IsolatedProcessTester.kt"
effort: "small"
autonomy: "autonomous"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: JvmChildProcess must strip JAVA_TOOL_OPTIONS so Graal JVMCI does not crash children

**Context:** Isolated `IsolatedTestRunner` children inherited Gradle/Graal `JAVA_TOOL_OPTIONS` (`-XX:+EnableJVMCIProduct`) and aborted in C1 `mmap(PROT_EXEC)` (exit 1). The workaround lives only in `IsolatedProcessTester`. Supervisor, profiler, and portal workers spawned via `JvmChildProcess` still inherit those env vars.

**Needed:**
1. Clear `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, and `JDK_JAVA_OPTIONS` in one place (`ProcessLauncher.startProcess` or `JvmChildProcess.start`).
2. Unit-test that a mock/env-capturing launcher sees those keys removed.
3. Keep IsolatedProcessTester's `-XX:-EnableJVMCI` flags or fold them into `JvmChildSpec` so daemon and portal children stay consistent.
