---
title: "ArchUnit: ban kotlinx.coroutines on enforcer supervisor/seccomp/landlock/state"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/test/kotlin/io/mazewall/ContainmentAckPathArchitectureTest.kt"
  - "enforcer/src/test/kotlin/dummy/violator/DummyCoroutineAckViolator.kt"
effort: "small"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: ArchUnit: ban kotlinx.coroutines on enforcer supervisor/seccomp/landlock/state

**Context:** Putting `SupervisorActor` + `Dispatchers.IO` on the validation reactor can deadlock `USER_NOTIF` (ACK zone in `docs/internals/designs/core/architectural-map.md` section 2) and poison Loom carriers. `:enforcer` already has coroutines as `compileOnly` for `SandboxDispatcherCoroutines` only. Ban package-level use on the ACK/kernel path. Do not copy profiler issue-019 onto the enforcer reactor.

**Needed:**
1. New test class `ContainmentAckPathArchitectureTest.kt`. Do **not** edit `ArchitectureTest.kt` (merge conflict).
2. Copy `@AnalyzeClasses(packages = ["io.mazewall"], importOptions = [ImportOption.DoNotIncludeTests::class])`.
3. Rule: `noClasses().that().resideInAnyPackage("io.mazewall.enforcer.supervisor..", "io.mazewall.seccomp..", "io.mazewall.landlock..", "io.mazewall.enforcer.state..", "io.mazewall.enforcer.engine..").should().dependOnClassesThat().resideInAPackage("kotlinx.coroutines..")`.
4. Allow `io.mazewall.enforcer.api.SandboxDispatcherCoroutines` (it is not in those packages).
5. Dummy violator: test source whose **declared package** is `io.mazewall.enforcer.supervisor` (file may live under `enforcer/src/test/kotlin/dummy/violator/DummyCoroutineAckViolator.kt` but the Kotlin `package` line must be `io.mazewall.enforcer.supervisor`). Reference `Dispatchers.IO` or `kotlinx.coroutines.channels.Channel`. Meta-test: `ClassFileImporter().importClasses(...)` + `assertThrows<AssertionError> { rule.check(classes) }` like `ArchitectureTest.ffmApiMustBeIsolatedToFfiPackageRuleDetectsViolation`.

**New cases:**
- `@ArchTest` production scan passes on current tree (no production coroutine imports in those packages today).
- Meta-test: dummy fails the rule.
- Optional second dummy in `io.mazewall.landlock` — nice, not required.

**Do not:**
- Add `implementation(libs.kotlinxCoroutines)` on `:enforcer`.
- Ban the whole `io.mazewall..` tree (would break `SandboxDispatcherCoroutines`).
- Put coroutines on `SupervisorInstaller` / `JVMValidationListener` as a "fix".

**Verify:** `./gradlew :enforcer:test --tests io.mazewall.ContainmentAckPathArchitectureTest`
