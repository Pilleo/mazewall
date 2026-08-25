---
title: "Do not reject reused live descriptors during poll"
severity: "MEDIUM"
status: "resolved"
priority: medium
component: "platform"
dependencies: []
target_modules:
  - ":platform"
target_files:
  - "platform/src/main/kotlin/io/mazewall/core/FileDescriptor.kt"
effort: "medium"
autonomy: "autonomous"
---

# Do Not Reject Reused Live Descriptors During Poll

**Source:** Codex PR review comment (P2)
**Created:** 2026-08-20

## Problem

After Mazewall closes a tracked descriptor, its integer remains permanently marked retired; if ordinary JDK/native code later opens an unrelated resource that reuses that integer, this precheck returns `EBADF` without calling the kernel because a raw `pollfd` carries no generation information. This can reject a valid live descriptor whenever `LinuxNative.raw.poll` receives descriptors allocated outside the new wrapper factories.

## Impact

- Valid descriptors rejected as EBADF
- JD/native code interoperability broken
- False positives in FD validation

## Solution

Generation validation should require typed lifecycle evidence rather than treating every historically retired integer as stale.

## Related Files

- `platform/src/main/kotlin/io/mazewall/core/FileDescriptor.kt` - Line 394
