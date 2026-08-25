---
title: "Do not revive retired descriptors through unsafe wrappers"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "platform"
target_modules:
  - ":platform"
target_files:
  - "platform/src/main/kotlin/io/mazewall/core/FileDescriptor.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3819982863
---

# 🔴 [Severity: HIGH]: Do not revive retired descriptors through unsafe wrappers

**Review (2026-08-21):** ALREADY FIXED: FileDescriptor.unsafe() returns a non-live token when FdEpoch.isRetired(fd).

**Context:** When `unsafe(fd)` is called after that integer was retired, `open()` invokes `claimOpen()` and mints a new live generation without evidence that the caller actually obtained a new kernel descriptor. This defeats the reuse protection: `SupervisorSeccompNotifInstaller` closes its listener FD after passing it, then can wrap and close the same integer again on setup failure; if another JVM thread acquired that reused integer, it would be treated as live.

**Problem:**
- unsafe() mints new live generation for retired FDs
- No evidence of actual kernel descriptor acquisition
- Reuse protection defeated
- Race condition possible

**Impact:**
- Security: retired descriptor can be revived
- Use-after-close possible

**Needed:**
1. unsafe() should not claimOpen for retired FDs
2. Or require explicit evidence of kernel descriptor acquisition
3. Maintain reuse protection invariant

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3819982863
