---
title: "Provide an Intentional Java Public API Facade"
severity: "ENHANCEMENT"
status: "resolved"
priority: high
dependencies:
  - "issue-20260808-032523"
component: "enforcer"
target_modules:
  - ":enforcer"
  - ":profiler"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/Mazewall.kt"
  - "enforcer/src/main/kotlin/io/mazewall/JavaPolicyBuilder.kt"
  - "enforcer/src/main/kotlin/io/mazewall/InstallationReceipt.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/Profiler.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/BillOfBehavior.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/ProfilingResult.kt"
effort: "large"
autonomy: "autonomous"
open_questions: false
paperclip_issue_id: fd1ea5f9-1634-471f-9f10-46792da11279
---

# 🔵 [Severity: ENHANCEMENT]: Provide an Intentional Java Public API Facade

**Context:** Primary entry points are Kotlin objects, extensions, Kotlin function types and deeply generic policy states. Java consumers of a JVM security library should not need `INSTANCE`, star-projection-shaped signatures or Kotlin-specific lifecycle patterns.

**Needed:** Add static Java factories, conventional builders, `Callable`/`Supplier` overloads, stable Java-visible policy and result interfaces, and owned contained-executor factories. Add Java compilation/integration fixtures for every documented happy path and ensure JavaDoc communicates irreversibility, ownership and fail-closed behavior. Do not weaken Kotlin type safety merely to expose raw implementation types.

**Resolution:**
1. **`io.mazewall.Mazewall` Facade:** Added static Java factories and entry points via `@file:JvmName("Mazewall")` in `io.mazewall`:
   - Static presets: `Mazewall.pureCompute()`, `Mazewall.pureComputeUnsafe()`, `Mazewall.noExec()`, `Mazewall.noExecHotspot()`, `Mazewall.noExecNativeImage()`, `Mazewall.noNetwork()`, and static fields (`PURE_COMPUTE`, `NO_EXEC`, etc.).
   - Static builder factory: `Mazewall.builder()`, `Mazewall.builder(runtime)`, `Mazewall.threadLocalBuilder()`.
   - Policy combination: `Mazewall.combine(Policy...)`.
   - Installation & Assessment: `Mazewall.installOnCurrentThread(policy, ...)`, `Mazewall.installOnProcess(policy)` (enforces scope check runtime validation preventing Landlock process-wide), `Mazewall.assessOnCurrentThread(policy)`, `Mazewall.assessOnProcess(policy)`.
   - Contained execution: `Mazewall.runContained(policy, Callable<T>)`, `Mazewall.runContained(policy, Supplier<T>)`, `Mazewall.runContained(policy, Runnable)`.
   - Contained executor wrappers & owned factories: `Mazewall.wrapExecutor(...)`, `Mazewall.wrap(...)`, `Mazewall.newContainedSingleThreadExecutor(policy)`, `Mazewall.newContainedFixedThreadPool(nThreads, policy)`, `Mazewall.newContainedCachedThreadPool(policy)`.
2. **`JavaPolicyBuilder`:** Provided a fluent builder supporting vararg syscalls, `java.nio.file.Path`, `java.io.File`, `java.util.regex.Pattern`, `String`, and runtime profiles (`RuntimeProfile.HOTSPOT_JIT`, `NATIVE_IMAGE`).
3. **Ergonomic Java Accessors in `InstallationReceipt`:** Added `isInstalled()`, `isProcessWide()`, and `isLandlockApplied()` accessors.
4. **`Profiler` & SBoB Java APIs:**
   - Added `@JvmStatic` and `Callable`/`Runnable` overloads to `Profiler.profile(...)`, `Profiler.wrap(...)`, and `Profiler.shutdown()`.
   - Added `@JvmOverloads` on `toPolicy()` and `toDsl()` in `ProfilingResult` and `BillOfBehavior`.
5. **Java Test Fixtures:** Created pure Java test fixtures in `enforcer/src/test/java/io/mazewall/MazewallJavaApiTest.java` and `profiler/src/test/java/io/mazewall/profiler/ProfilerJavaApiTest.java` verifying all happy paths, builders, contained executors, and fail-closed boundary enforcement.
