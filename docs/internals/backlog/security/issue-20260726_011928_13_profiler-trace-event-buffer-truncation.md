---
title: TraceEvent buffer truncation on excessively long extracted string paths
type: issue
status: open
priority: low
labels:
- security
- profiler
- buffer-overflow
- truncation
component: enforcer
target_modules:
- :enforcer
- :profiler
target_files:
- enforcer/src/main/kotlin/io/mazewall/ffi/memory/SupervisorProcessMemoryReader.kt
paperclip_issue_id: 564d8b05-ca6f-49c5-88bd-603106471956
---

# Issue: TraceEvent Path Truncation Vulnerability

## Context
In `SupervisorProcessMemoryReader.kt`, strings are extracted via `process_vm_readv` up to `maxLen` (default 4096).

## The Bug
`readString` loops through the bytes until it finds a null terminator: `while (len < bytes.size && bytes[len] != 0.toByte()) len++`.
If a malicious or misconfigured application provides an extremely long path (e.g. `openat(AT_FDCWD, "A".repeat(100_000))`), `readString` will read the first 4096 bytes. Because `bytes.size` is 4096, the `while` loop finishes with `len = 4096`. It then creates a string out of those 4096 bytes without verifying that a null byte was ever actually reached.

This leads to silent truncation. The JVM validator will check the truncated string, allowing or denying it, but the kernel will process the full string, resulting in a sandbox bypass (TOCTOU/truncation).

## Recommendation
In `readString`, if the loop hits `bytes.size` without finding a null byte, throw an exception or return an error state instead of silently truncating the string.
