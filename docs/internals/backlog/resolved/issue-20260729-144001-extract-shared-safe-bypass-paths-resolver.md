---
title: "Extract shared safe bypass paths resolver to eliminate duplicated JVM scanning logic"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
  - ":profiler"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerSessionHandler.kt"
effort: "medium"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: Extract shared safe bypass paths resolver to eliminate duplicated JVM scanning logic

**Context:**
Both `ProfilerSessionHandler` and `SupervisorSessionHandler` independently implement a `safeBypassPaths` list of JVM/environment-internal paths (classpath JAR archives, java.home, javaagent arguments, virtual filesystems like `/proc` and `/sys`, and Gradle cache/build paths) to bypass seccomp user-notification roundtrips. This bypass is essential to avoid circular JVM safepoint/ClassLoader deadlocks during tracee classloading or JIT execution.

**The Issue:**
Duplication of this 100+ line logic across different modules leads to maintenance friction, potential drift, and feature disparity. Specifically, `SupervisorSessionHandler`'s resolution is highly robust: it resolves real paths via `toRealPath()`, recursively parses JAR manifest `Class-Path` entries, handles `GRADLE_USER_HOME`, and locates the project root directory. On the other hand, `ProfilerSessionHandler` uses a simpler, less robust approach that lacks JAR classpath manifest resolution, `GRADLE_USER_HOME` lookups, and project root detection. This could fail to match classpaths containing symlinks or custom layouts, risking silent deadlocks during profiling.

Since the `:profiler` module already has a dependency on `:enforcer`, we should extract this scanning and resolution logic to a single shared utility.

**Needed:**
1. Define a shared utility class `BypassPaths` in `:enforcer` under a core package (e.g. `io.mazewall.core` or `io.mazewall.enforcer.supervisor`) containing the unified, fully robust `safeBypassPaths` scanning and caching logic.
2. Implement a method `isBypassPath(path: java.nio.file.Path): Boolean` or expose `val safeBypassPaths: List<java.nio.file.Path>` in the shared utility.
3. Replace the local `safeBypassPaths` initialization in `ProfilerSessionHandler.Companion` and `SupervisorSessionHandler.Companion` with calls to the shared `BypassPaths` utility.
4. Write unit tests in `:enforcer` to verify the shared bypass resolution logic.
