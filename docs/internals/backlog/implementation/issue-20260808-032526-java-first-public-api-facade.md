---
title: "Provide an Intentional Java Public API Facade"
severity: "ENHANCEMENT"
status: "open"
priority: high
dependencies:
  - "issue-20260808-032523"
component: "enforcer"
target_modules:
  - ":enforcer"
  - ":profiler"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/Policy.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/ContainedExecutors.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/Profiler.kt"
effort: "large"
autonomy: "supervised"
open_questions: true
---

# 🔵 [Severity: ENHANCEMENT]: Provide an Intentional Java Public API Facade

**Context:** Primary entry points are Kotlin objects, extensions, Kotlin function types and deeply generic policy states. Java consumers of a JVM security library should not need `INSTANCE`, star-projection-shaped signatures or Kotlin-specific lifecycle patterns.

**Needed:** Add static Java factories, conventional builders, `Callable`/`Supplier` overloads, stable Java-visible policy and result interfaces, and owned contained-executor factories. Add Java compilation/integration fixtures for every documented happy path and ensure JavaDoc communicates irreversibility, ownership and fail-closed behavior. Do not weaken Kotlin type safety merely to expose raw implementation types.

## ❓ Open Questions
1. **Package Placement & Class Naming:** Should the Java facade live in a separate subpackage (e.g. `io.mazewall.java.*` or `io.mazewall.api.*`), or should static facade entry points be placed alongside core packages using `@file:JvmName("Mazewall")` / `MazewallExecutors`?
2. **Generic Scope Representation in Java:** How should Kotlin phantom types (`PolicyScope.ThreadLocalSafe`, `PolicyScope.ProcessWideSafe`, `Uncompiled`) be represented in Java? Should Java APIs use distinct types (e.g. `ThreadPolicy`, `ProcessPolicy`) without generics, or standard generic builder patterns?

