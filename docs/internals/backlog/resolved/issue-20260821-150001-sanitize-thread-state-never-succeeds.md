---
title: "sanitizeThreadState must be uncallable as cleanup (test + Nothing)"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/state/ContainmentStateRegistry.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/ContainmentStateRegistryTest.kt"
effort: "small"
autonomy: "autonomous"
---

# 🟡 [Severity: MEDIUM]: sanitizeThreadState must be uncallable as cleanup (test + Nothing)

**Context:** Seccomp filters and Landlock domains are permanent for the lifetime of the OS thread (LWP). Clearing JVM `ThreadLocal` tracking after a successful install desynchronizes the registry from the kernel and causes duplicate stacking until `E2BIG` / the 32-filter cap. This is WONTFIX as product behavior: `docs/internals/backlog/performance/issue-102-permanent-thread-pool-contamination-classloader-leaks-and-st.md` and `issue-103-containedexecutors-thread-local-state-persistence-and-poison.md`.

`ContainmentStateRegistry.sanitizeThreadState()` exists only to throw `UnsupportedOperationException`. It currently returns `Unit`, so a wrapper can look like successful cleanup. No unit test calls it today. `ContainedExecutorWrapper` must keep restoring `threadState` only when `receipt == null`.

**Needed:**
1. Add tests on `ContainmentStateRegistryTest`. Keep the existing `@AfterEach` that resets thread and process state to `ContainerState()`.
2. Change `fun sanitizeThreadState()` to `fun sanitizeThreadState(): Nothing`. The body must still throw `UnsupportedOperationException` (message must mention that OS restrictions are permanent for the thread lifetime).
3. Do not delete the method (it documents the WONTFIX). Do not add `restore()`, checkpoint, or `VersionedState` APIs.

**New cases:**
- `sanitizeThreadState throws UnsupportedOperationException` — message mentions permanent restrictions and/or thread lifetime.
- `sanitizeThreadState has Kotlin return type Nothing` — `ContainmentStateRegistry::sanitizeThreadState.returnType.classifier == Nothing::class` (or equivalent `KType` check).
- Negative: assign `threadState = ContainerState(filterDepth = 3)`, call `sanitizeThreadState()`, catch the throw, then assert `threadState.filterDepth == 3` (the throw is not a clear).

**Do not:**
- Clear `threadHolder` / `threadState` inside `sanitizeThreadState`.
- Call `sanitizeThreadState` from `ContainedExecutorWrapper`.
- Treat this as permission to sanitize after a successful contain.

**Verify:** `./gradlew :enforcer:test --tests io.mazewall.enforcer.ContainmentStateRegistryTest`
