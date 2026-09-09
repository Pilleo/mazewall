---
title: "Profiler kernel replies must retry EINTR and terminate failed reply attempts"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: ["profiler", "platform"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerSessionHandler.kt"
target_symbols:
  - "ProfilerSessionHandler"
open_questions: false
---

# 🟡 [Severity: HIGH]: Profiler kernel replies must retry EINTR and terminate failed reply attempts

**Context:**
A throwing noise responder falls through into publication and ACK; one-shot ioctl treats EINTR as failure and ENOENT as sent.

**Needed:**
1. Use shared USER_NOTIF send and terminal reply-attempt ownership with explicit expired notification result.

**Resolution (2026-09-09):** Profiler sends use UserNotifReply.send and explicit SENT /
NOTIFICATION_GONE outcomes. Reply attempts are marked before I/O; failures terminate without
publication, ACK, a false success ledger entry, or a second reply. Pure ProfilerNoiseClassifier
consumes resolved evidence. The native adapter captures engine/socket dependencies and the
reactor bridge uses injected raw operations. Reducers and native layouts remain distinct/unchanged.

**Evidence:** RED→GREEN repeated EINTR and throwing-noise tests; explicit ENOENT/EPERM,
ordinary/fatal failure, no second reply and injected raw adapter tests. Final :profiler:test:
526 tests, no failures/skips. ProfilerIntegrationTest in a fresh JVM: 12 tests, no failures/skips.
Platform/enforcer/profiler detektMain tasks passed; profiler PlantUML/SVG regenerated.

---
<!-- id: issue-20260909-193839-143-profiler-kernel-replies-must-retry-eintr-and-termi  file: issue-20260909-193839-143-profiler-kernel-replies-must-retry-eintr-and-termi.md -->
