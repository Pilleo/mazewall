---
title: "Preserve existing public enforcer package classes for binary compatibility"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainmentViolationException.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/SandboxDispatcher.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/ContainedExecutors.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/ContainmentViolationException.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/SandboxDispatcher.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: PRRC_kwDOScnnEM7iSmJD
---

# 🔴 [Severity: HIGH]: Preserve existing public enforcer package classes

**Context:** Applications compiled against `io.mazewall.enforcer.ContainmentViolationException` and `io.mazewall.enforcer.SandboxDispatcher` must keep linking after those types moved to `io.mazewall.enforcer.api`. `ContainedExecutors` already has a deprecated facade in the old package.

**Catch compatibility (this is the part that was getting reversed):**

The JVM `catch (T)` matches when the thrown object is an **instance of T**. So the **thrown** type must be T or a **subtype** of T.

Library sites throw `io.mazewall.enforcer.api.ContainmentViolationException`. Old callers catch `io.mazewall.enforcer.ContainmentViolationException`. Therefore:

- **Required:** API exception **extends** the historical class (API is-a historical). Then `throw api.CVE` is caught by `catch (historical)`.
- **Wrong:** historical extends API. Then `throw api.CVE` is a **superclass** of the catch type and is **not** caught. A Kotlin `typealias` also fails: it does not emit a JVM class, so Java `catch` cannot use it.

**Needed:**
1. Keep a deprecated **open** `io.mazewall.enforcer.ContainmentViolationException(message, cause)` that extends `RuntimeException`.
2. Make `io.mazewall.enforcer.api.ContainmentViolationException` extend that historical class. Do not reverse the extends.
3. Add a deprecated `io.mazewall.enforcer.SandboxDispatcher` object that forwards `execute` / `executeBlock` / `shutdownAll` to the API object.
4. Tests: throw the API exception and assert it is caught as `io.mazewall.enforcer.ContainmentViolationException`; call `io.mazewall.enforcer.SandboxDispatcher.execute` and `shutdownAll`.

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3796525635
