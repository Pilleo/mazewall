---
title: "Make portal worker reply loop authoritative"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "portal-worker"
target_modules: ["portal-worker"]
target_files: ["portal-worker/src/main/kotlin/io/mazewall/portal/worker/PortalWorkerDriver.kt", "portal-worker/src/main/kotlin/io/mazewall/portal/worker/PortalWorkerMachine.kt"]
target_symbols: ["PortalWorkerDriver", "PortalWorkerMachine"]
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Make portal worker reply loop authoritative

**Context:**
The inline worker loop moved back to AwaitingRequest before the response write completed and its
broad dispatch catch could interpret a response-write failure as permission to send another frame.

**Needed:**
1. Make reply transmission an explicit state and isolate dispatch failure from transport failure.

**Resolved:**
1. Extracted an injected driver and unified builtin/generated dispatch behind one boundary.
2. Added SendingReply plus explicit ReplySucceeded/ReplyFailed transitions.
3. Partial receive and response-write failures are terminal, reflection failures are unwrapped,
   and READY is published only after all worker startup authorities exist.
4. Review follow-up unwraps every reflective target `Throwable`: fatal errors preserve identity,
   attempt no response, and retain terminal cleanup failures as suppressed evidence.

---
<!-- id: issue-20260909-214720-333-make-portal-worker-reply-loop-authoritative  file: issue-20260909-214720-333-make-portal-worker-reply-loop-authoritative.md -->
