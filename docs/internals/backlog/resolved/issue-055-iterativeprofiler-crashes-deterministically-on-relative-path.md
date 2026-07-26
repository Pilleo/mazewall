---
title: "`IterativeProfiler` crashes deterministically on relative-path filesystem violations"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: `IterativeProfiler` crashes deterministically on relative-path filesystem violations

**Status:** RESOLVED (June 2026)
**Target:** `io.mazewall.profiler.iterative.IterativeProfiler` (specifically `extractViolationPath` and `resolveAbsolutePath`)
**Context & Proof:** When a profiled workload accessed a file using a relative path, the extracted path was passed raw to the builder, violating the absolute path requirement and crashing the profiling loop. Furthermore, `resolveAbsolutePath` explicitly returned `null` for relative paths in exception messages, ignoring them completely.
**Fix:**
1. Updated `extractViolationPath` to always resolve and normalize paths to absolute form (`java.nio.file.Paths.get(it).toAbsolutePath().normalize().toString()`).
2. Modified `resolveAbsolutePath` to allow returning relative paths so they can be processed and canonicalized properly.
3. Verified via unit and build verification tests.
