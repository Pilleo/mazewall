---
title: "`ProfilerDaemon` memory-reading fails to resolve paths on page boundaries or large strings"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: `ProfilerDaemon` memory-reading fails to resolve paths on page boundaries or large strings

**Status:** RESOLVED (June 2026)
**Target:** `io.mazewall.profiler.engine.ProfilerMemoryReader` (specifically `readStringFromProcess`)
**Context & Proof:** If `process_vm_readv` reads a path string that does not contain a null terminator in the returned buffer (due to page boundaries or large lengths), the profiler returned `null`, breaking rule compilation.
**Fix:** Modified `readStringFromProcess` to return a best-effort decoded string of length `bytesRead` when a null terminator is not found, instead of returning `null`. Verified via unit tests.
