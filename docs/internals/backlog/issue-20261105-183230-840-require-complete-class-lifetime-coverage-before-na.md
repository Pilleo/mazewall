---
title: "Require complete class-lifetime coverage before native canonical-cache admission"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: []
target_files:
   - "profiler-native/src/agent.cpp"
   - "profiler-native/tests"
   - "profiler-native/CMakeLists.txt"
target_symbols:
   - "resolve_frame_cached"
   - "CanonicalContext"
   - "remember_canonical_context"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Require complete class-lifetime coverage before native canonical-cache admission

**Context:**
Recursive state-design review D-04: ordinary metadata-budget refusal deletes a class guard but returns copied metadata as successful. Later canonical raw-stack admission can succeed with incomplete guard coverage. Source-confirmed missing admission relationship; actual JVM method-ID reuse and stale labels were not reproduced, and existing retention-exhaustion telemetry already makes the run incomplete.

**Needed:**
1. Distinguish safe immediate copied metadata from cacheable metadata with owned lifetime evidence; require complete guard coverage or skip reusable raw-stack admission, preserving RAII cleanup and exhaustion accounting.

## Evidence and conditional manifestation

[Recursive review D-04](../reviews/2026-11-05-state-complexity-review.md):
`profiler-native/src/agent.cpp:694–711` deletes the weak class guard on ordinary
metadata-budget refusal, returns copied metadata as successful, and leaves the
output guard null. `:1185–1201,1219–1230` collects only nonnull guards but may admit
a reusable canonical raw stack anyway. Lookup at `:812–830` validates only guards
present. The larger metadata reservation can fail while smaller canonical
retention succeeds, especially with an already-interned logical stack or
concurrent capacity release.

The missing relationship is **raw cached identity requires lifetime coverage for
every contributing class**. Ordinary ClassPrepare does not necessarily advance a
stable generation (`include/symbolization_generation.hpp:47–51` only advances a
replacement-in-progress). Actual unload/reused method ID/stale diagnostic labels
remain conditional and unexecuted. Existing budget-exhaustion telemetry already
makes the run incomplete; this is not a false-completeness or policy bypass claim.

## Smallest correction and acceptance

- Distinguish immediate `CopiedOnly(frame)` from `Cacheable(frame, ownedGuard)`,
  or retain the output guard when declining metadata-cache retention. Admit a
  canonical raw stack only with complete lifetime coverage. Immediate immutable
  copied metadata need not authorize reusable raw lookup.
- Keep JNI reference creation/deletion, reservations and locks as effects, with
  RAII cleanup on rejection/exception paths. No ABI, wire, dependency or general
  native-framework change is required.
- Deterministically refuse metadata retention while allowing following canonical
  retention; prove complete coverage or no insertion. Cover mixed/partial frame
  coverage, existing-cache hits, cache disabled, truncation, guard invalidation/raw
  identity reuse seam and exactly-once cleanup.
- Preserve strong local references during copying, present-cleared-guard rejection,
  native outcomes, exhaustion/loss accounting and no-resurrection behavior. A
  one-byte total-budget exhaustion test is not selective-refusal coverage.
- Qualify actual unload/reload under supported JVMs before claiming live stale-label
  prevention. Run focused native ownership tests and the project completion gate
  during implementation; this review ran neither native tests nor a JVM reuse probe.

## Ownership reconciliation

Coordinate the [allocator-failure owner](issue-20261103-235248-477-contain-allocation-failure-inside-native-profiler-.md),
which handles bad_alloc/noexcept and rollback, not ordinary Boolean budget refusal.
The [resolved strong-copy lifetime correction](resolved/issue-20260913-024414-042-protect-jvm-metadata-copies-with-strong-lifetime-r.md)
remains implemented; the residual is retained raw-key reuse after copying. The
[resolved cache optimization](resolved/issue-20260906-221244-reduce-tier-e-per-boundary-jvmti-stack-capture-cost.md)
is not reopened. Active/resolved reconciliation found no narrower active owner;
replacement-generation correctness remains its existing separate issue.

---
<!-- id: issue-20261105-183230-840-require-complete-class-lifetime-coverage-before-na  file: issue-20261105-183230-840-require-complete-class-lifetime-coverage-before-na.md -->
