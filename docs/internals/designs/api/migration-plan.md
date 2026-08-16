---
title: "Public API Migration Plan"
scope: "enforcer | profiler"
critical_syscalls: []
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/Policy.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/ContainedExecutors.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/Profiler.kt"
keywords: ["public-api", "migration", "compatibility", "deprecation"]
---

# Public API Migration Plan

## Constraint

Implementation includes breaking API changes and therefore requires explicit maintainer approval under repository rules. Documentation and additive facade work may proceed first.

## Phase 1: Additive Facade

1. Add `Mazewall`, explicit runtime profiles, assessment/receipt types and owned contained executors.
2. Add `MazewallProfiler`, session types and coverage metadata.
3. Add Java-first factories and builders.
4. Keep current APIs operational while marking unsafe lifecycle behavior in KDoc.

## Phase 2: Deprecation

Deprecate arbitrary executor wrapping, containment `AutoCloseable`, global profiler shutdown/state, ambiguous policy composition, duplicate builders and direct application access to low-level types. Every deprecation message must point to an executable replacement example.

## Phase 3: Surface Reduction

After a documented compatibility window, remove deprecated APIs and move low-level functionality behind `internal`, an unstable SPI, or a separate low-level artifact. Generate and version an API signature dump so accidental public declarations fail CI.

## Compatibility Verification

- Kotlin compile tests for all documented happy paths.
- Java compile tests without Kotlin-specific invocation patterns.
- Binary/source compatibility validation for the supported transition window.
- Integration tests proving owned executors terminate restricted workers.
- Tests proving receipts and `close()` never imply kernel rollback.
- Golden tests keeping README snippets and serialized `BillOfBehavior` schemas synchronized.
- Fail-closed tests for unsupported platforms, incompatible runtime profiles and partial profiler results.
