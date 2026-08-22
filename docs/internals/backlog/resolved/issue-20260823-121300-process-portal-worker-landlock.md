---
title: "Process portal: Landlock on the worker with JVM classpath"
severity: "ENHANCEMENT"
status: "resolved"
priority: medium
dependencies:
  - "issue-20260823-121200"
component: "enforcer"
target_modules:
  - ":portal"
  - ":enforcer"
target_files:
  - "portal/src/main/kotlin/io/mazewall/portal/PortalWorkerMain.kt"
  - "enforcer/src/main/kotlin/io/mazewall/ProcessPolicies.kt"
effort: "medium"
autonomy: "supervised"
open_questions: false
---

# 🔵 [Severity: ENHANCEMENT]: Process portal: Landlock on the worker with JVM classpath

**Context:** The worker currently installs `denyProcessCreation(HOTSPOT_JIT)` + `denyNetwork(HOTSPOT_JIT)` only. The design also wants Landlock so the worker cannot `open` host paths; it should only use granted FDs plus whatever the JVM still needs to run (classpath, `java.home`). Installing Landlock without those paths will EACCES classloading and kill the worker.

**Needed:**
1. After Unix connect, install Landlock that includes the JVM classpath and `java.home` (reuse existing `includeJvmClasspath` / policy path helpers). Fail closed if Landlock is required and unsupported.
2. Keep `openat` blocked or Landlock-denied for paths outside that set so a worker cannot open `/etc/shadow` itself.
3. Integration test: worker checksum of a granted FD still works; worker-side `FileInputStream("/etc/passwd")` (or `open`) fails with EACCES/EPERM.

## ❓ Open Questions
1. Besides classpath and `java.home`, which extra readable paths are required for GraalVM 25 HotSpot workers (locale data, `/tmp` for `jspawnhelper` which should already be blocked by NO_EXEC)?
   **Resolved:** extra paths are deferred; v1 allowlist is classpath + `java.home` only.
