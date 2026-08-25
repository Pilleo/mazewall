---
title: "ArchUnit: ContainerState must remain a non-subtype snapshot type"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/test/kotlin/io/mazewall/ContainerStateArchitectureTest.kt"
effort: "small"
autonomy: "autonomous"
---

# 🟡 [Severity: MEDIUM]: ArchUnit: ContainerState must remain a non-subtype snapshot type

**Context:** `ContainerState` is an immutable snapshot of applied policy. Lifecycle already lives in `SeccompInstallationState`, `LandlockState`, and `UnixListenDaemonState`. A `ContainerStateMachine` subclass hierarchy would duplicate those machines and invite ThreadLocal "states" that can be rolled back. Process-wide updates already use `AtomicReference.updateAndGet`.

**Needed:**
1. New ArchUnit class `ContainerStateArchitectureTest.kt`. Do not edit `ArchitectureTest.kt`.
2. Rule: classes assignable to `ContainerState` should have fully qualified name `io.mazewall.enforcer.state.ContainerState`, filtering Kotlin synthetics (`$Companion`, `DefaultImpls`, `$$`) the same way `sealedSecurityOutcomesHaveAClosedSubclassSet` does.
3. Do **not** ban the identifier `VersionedState` by string.

**New cases:**
- Current tree: only `ContainerState` (plus synthetics) is assignable to `ContainerState`.
- Comment in the test: an extra JVM subclass would fail the rule.

**Do not:**
- Convert `ContainerState` into a sealed lifecycle ADT.
- Add `VersionedState<T>` around the registry.

**Verify:** `./gradlew :enforcer:test --tests io.mazewall.ContainerStateArchitectureTest`
