---
title: "Prevent Silent Path/String Truncation in Remote Process Memory Reads"
severity: "CRITICAL"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/ffi/memory/SupervisorProcessMemoryReader.kt"
effort: "medium"
autonomy: "autonomous"
github_issue: 452
---

# ☣️ [Severity: CRITICAL]: Prevent Silent Path/String Truncation in Remote Process Memory Reads

**Context:**
In `SupervisorProcessMemoryReader.readString`, the remote process memory read has a default limit of `maxLen` (4096 bytes). If a remote process passes a string that is longer than `maxLen` or does not contain a null terminator within `maxLen` bytes, `readBytes` fetches exactly `maxLen` bytes. The `readString` method then scans these bytes, fails to find a null terminator, and silently constructs a JVM string of the truncated bytes.

This creates a high-severity security vulnerability. A sandboxed thread could invoke a file system syscall (e.g., `openat`) passing a path that is padded with extra characters to exceed 4096 bytes, ending with a malicious path (e.g., `/permitted/path/` + `A` * 4000 + `/etc/passwd`). If the supervisor reads this, it will silently truncate the path, match it against a permitted prefix (e.g., `/permitted/path/`), and approve the operation. However, the kernel resolves the path using the full un-truncated buffer (up to the real null terminator), opening `/etc/passwd` instead, which is a total sandbox bypass!

**Needed:**
1. Refactor `SupervisorProcessMemoryReader.readString` to strictly fail-closed if no null terminator is encountered within `maxLen` bytes.
2. If `bytes[len] != 0.toByte()` is not found in the read buffer, throw a `ContainmentViolationException` or return `null` (representing an invalid string/protocol violation) rather than returning a truncated string.
3. Add a dedicated unit test in `SupervisorProcessMemoryReaderTest.kt` verifying that reading a remote string that lacks a null terminator within the read limit returns `null` or throws an exception.

**Verification/Regression Tests:**
- Verify that a mocked remote string of size 4096 with no null terminator throws or is rejected.
- Run `./gradlew :enforcer:test` to ensure all existing memory read tests pass.
