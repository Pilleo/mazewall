---
title: "ArchUnit FileDescriptor.unsafe production ban does not match Companion calls"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/test/kotlin/io/mazewall/ArchitectureTest.kt"
effort: "small"
autonomy: "autonomous"
---

# 🟡 [Severity: MEDIUM]: ArchUnit FileDescriptor.unsafe production ban does not match Companion calls

**Context:** `ArchitectureTest.fileDescriptorUnsafeMustNotBeUsedInProduction` rejects calls whose target owner is assignable to `FileDescriptor` and whose name is `unsafe`. Kotlin compiles `FileDescriptor.unsafe(...)` onto `io.mazewall.core.FileDescriptor$Companion` (`unsafe` / `unsafe$default`), not onto `FileDescriptor` itself. `javap` of `FileDescriptor$Companion.class` and of `SupervisorInstaller` (unixSocket `$default` on Companion) confirm this ABI. The predicate therefore never matches, so the new production ban is a no-op even if `unsafe()` is reintroduced in `io.mazewall` main sources.

**Needed:**
1. Match `FileDescriptor$Companion` (and `$default` synthetic) in the ArchUnit predicate, e.g. owner name `io.mazewall.core.FileDescriptor$Companion` and method name `unsafe` or `unsafe$default`.
2. Add a regression: a comment or a checked example so a deliberate `FileDescriptor.unsafe` in a production `io.mazewall` class fails the rule (temporary fixture class under `src/main` is not required if ArchUnit’s `expectViolation` / a dedicated analyzed package is used).
3. Keep test sources excluded (`DoNotIncludeTests`); tests may still call `unsafe` until they migrate.

**Resolved:** The production ban now matches `FileDescriptor$Companion` and `unsafe$default`. Remaining hole: callers in `io.mazewall.core` are still excluded — see `issue-20260820-185330-archunit-unsafe-ban-excludes-core-callers`.
