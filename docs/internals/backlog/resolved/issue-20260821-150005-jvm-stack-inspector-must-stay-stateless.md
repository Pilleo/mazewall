---
title: "JvmStackInspector must not cache stacks (no fields + inspect freshness)"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/test/kotlin/io/mazewall/JvmStackInspectorArchitectureTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/JvmStackInspectorTest.kt"
effort: "small"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: JvmStackInspector must not cache stacks (no fields + inspect freshness)

**Context:** `JvmStackInspector.inspect()` is USER_NOTIF authorization (`supervisor-proxy-design.md`). A TTL or `ConcurrentHashMap` cache of `Thread.getStackTrace()` is a confused-deputy bug. Existing ArchUnit `jvmStackInspectorMustHavePrimitiveDependenciesOnly` only limits *types* of dependencies; a JDK `Map` field would still pass. Kotlin `object` may have synthetic `INSTANCE` — ignore that field only.

**Needed:**
1. New ArchUnit class `JvmStackInspectorArchitectureTest.kt`. Do not edit `ArchitectureTest.kt`.
2. Condition: `JvmStackInspector` has no fields whose raw type is assignable to `java.util.Map`, `java.util.Collection`, or a cached `StackTraceElement[]` holder, and no non-synthetic instance fields other than Kotlin `INSTANCE`. If ArchUnit reports only `INSTANCE`, the rule should pass today.
3. Behavioral tests on existing `JvmStackInspectorTest`.

**New cases:**
- `inspect(null)` returns `SafeToValidate` with empty `rawStack` (today `targetThread?.stackTrace ?: emptyArray()`).
- `inspect(currentThread)` twice: both `SafeToValidate`; do **not** require identity of `rawStack` arrays (`assertFalse(a.rawStack === b.rawStack)` is acceptable) — proves no sticky stored array reused as the sole result.
- `inspect` with `nr=1` then `nr=2` returns `nr` 2 on the second call (no stale args/nr).
- ArchUnit: adding `val cache = ConcurrentHashMap<...>` on the inspector would fail. Do not add such a field in production to "test" the rule.

**Do not:**
- TTL-cache stack traces.
- Change `SafeToValidate` to share arrays across calls.

**Verify:** `./gradlew :enforcer:test --tests io.mazewall.JvmStackInspectorArchitectureTest --tests io.mazewall.enforcer.supervisor.JvmStackInspectorTest`
