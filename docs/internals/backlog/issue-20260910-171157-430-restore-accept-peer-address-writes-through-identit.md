---
title: "Restore accept peer address writes through identity-bound authority"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/TraceeAuthorityExecution.kt"
target_symbols:
  - "TraceeAuthorityExecution"
open_questions: false
---

# 🟡 [Severity: HIGH]: Restore accept peer address writes through identity-bound authority

**Context:**
The authority helper must preserve accept EFAULT semantics without risking a process_vm_writev write through a recycled numeric TID. Linux offers no pidfd form of process_vm_writev, while /proc/task/mem positional writes use FOLL_FORCE and may write read-only mappings. The broker explicitly returns UnsupportedTraceeAuthority.AcceptPeerMemory and EPERM for non-null peer address outputs; address-less accept remains supported.

**Needed:**
1. Design and verify a tracee-cooperative or kernel-supported identity-bound memory-write capability that preserves writable-page checks and accept errno, then replace the explicit unsupported result with tests for PID exit/reuse and read-only output pages.

---
<!-- id: issue-20260910-171157-430-restore-accept-peer-address-writes-through-identit  file: issue-20260910-171157-430-restore-accept-peer-address-writes-through-identit.md -->
