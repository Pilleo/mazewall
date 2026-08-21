---
title: "Gate the native-memory assertion on actual availability"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/TraceeReadOnlyNulTest.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3819751052
---

# 🟡 [Severity: MEDIUM]: Gate the native-memory assertion on actual availability

**Context:** On Linux hosts where `process_vm_readv` or the relevant mapping read is unavailable, `TraceeReadOnlyNul.find()` legitimately returns null, but this test is guarded only by the operating system and therefore fails the host unit suite. Reproduced with `:enforcer:test --tests io.mazewall.enforcer.supervisor.TraceeReadOnlyNulTest` on x86_64 Linux 6.18.35.

**Problem:**
- Test not gated by platform capability
- process_vm_readv unavailable on some hosts
- Test fails on those hosts

**Impact:**
- Test suite fails on hosts without process_vm_readv
- False test failures

**Needed:**
1. Probe required capability before test
2. Or test scanning logic directly
3. Gate test on platform capability

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3819751052
