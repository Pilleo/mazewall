---
title: "Excessive Landlock directory capability leak on unlinked/deleted files ending in ` (deleted)`"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: Excessive Landlock directory capability leak on unlinked/deleted files ending in ` (deleted)`

**Status:** RESOLVED (June 2026)
**Target:** `io.mazewall.profiler.engine.ProfilerMemoryReader` (specifically `resolveLink`) and `io.mazewall.landlock.Landlock.kt` (specifically `handleInitialOpenFailure`)
**Context & Proof:** If an application opens a file (e.g. `/var/log/app/tmp_file`) and unlinks it immediately, reading the `/proc/$pid/fd/$fd` symlink returns `/var/log/app/tmp_file (deleted)`. Landlock's fallback mechanism previously opened the parent directory, exposing the entire directory to the sandbox.
**Fix:**
1. Stripped any trailing `" (deleted)"` suffix from resolved symlink paths in `ProfilerMemoryReader.resolveLink`.
2. Modified `Landlock.handleInitialOpenFailure` to return `res to false` immediately without falling back to the parent directory if the path ends with `" (deleted)"`.
3. Verified via unit and integration tests.
