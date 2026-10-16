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

## Cutover decision

Maintainer approval authorizes `0.1.0-SNAPSHOT` as the first supported breaking
API baseline. There is no compatibility window for direct native, platform,
protocol, BPF, FFM, daemon, attribution, replay, or collector imports.

## Supported facade

1. `Mazewall`, policies, containment, assessment, receipts, diagnostics and
   violations are the enforcer contract.
2. `MazewallProfiler`, sessions, options, results, evidence and coverage are
   the profiler contract.
3. Java-facing factories and builders remain supported only when their parameter
   and return types are themselves part of those facades.

## Removed surface

Legacy wrappers that existed only to preserve pre-cutover access are removed.
Implementation code is Kotlin `internal`; the repository uses explicitly scoped
friend paths only where a direct implementation module needs it. No unstable
artifact is published.

## Compatibility Verification

- Kotlin and Java facade compile tests for documented happy paths.
- Kotlin ABI snapshots for every production library.
- `verifySupportedApiSurface` in the normal build gate, rejecting forbidden
  implementation packages from published snapshots.
- Do not add owned-executor tests unless 032520 is un-deferred.
- Tests proving receipts and `close()` never imply kernel rollback.
- Golden tests keeping README snippets and serialized `BillOfBehavior` schemas synchronized.
- Fail-closed tests for unsupported platforms, incompatible runtime profiles and partial profiler results.
