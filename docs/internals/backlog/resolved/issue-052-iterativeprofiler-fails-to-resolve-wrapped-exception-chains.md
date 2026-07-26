---
title: "`IterativeProfiler` fails to resolve wrapped exception chains"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: `IterativeProfiler` fails to resolve wrapped exception chains

**Status:** RESOLVED (June 2026)
**Target:** `/profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeProfiler.kt` (specifically `extractViolationPath`)
**Context:** Progressive profiling relied on catching permission failures, but failed if a library wrapped the underlying `AccessDeniedException` in a custom exception, as the profiler only inspected the top-level exception.
**Fix:** Updated `IterativeProfiler.extractViolationPath` to use the refactored `ContainmentViolationDetector.findViolationCause(t)`, which correctly traverses the exception cause chain to find the actual containment violation.
