---
title: "Do not reject reused live descriptors during poll"
severity: "LOW"
status: "open"
priority: low
dependencies: []
component: "platform"
target_modules:
  - ":platform"
target_files:
  - "platform/src/main/kotlin/io/mazewall/core/FileDescriptor.kt"
effort: "medium"
autonomy: "autonomous"
related_pr: 512
related_thread: PRRC_kwDOScnnEM7kBdR
---

# 🟡 [Severity: LOW]: Do not reject reused live descriptors during poll

**Context:** After Mazewall closes a tracked descriptor, its integer remains permanently marked retired. If ordinary JDK/native code later opens an unrelated resource that reuses that integer, the poll precheck returns `EBADF` without calling the kernel because a raw `pollfd` carries no generation information. This can reject a valid live descriptor whenever `LinuxNative.raw.poll` receives descriptors allocated outside the new wrapper factories.

**Problem:**
- Generation tracking marks descriptor integers as retired
- Kernel can reuse integers for new file descriptions
- Poll precheck uses integer-only validation
- Valid live descriptors get rejected with EBADF

**Impact:**
- False positives on descriptor validation
- Legitimate operations fail when integer reuse occurs

**Needed:**
1. Require typed lifecycle evidence rather than integer-only validation
2. Track descriptor identity via generation/capability tokens, not just integer
3. Only reject when typed evidence confirms the descriptor is stale

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3825587178
