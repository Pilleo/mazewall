---
title: "Portal ERROR frames recycle the worker process"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies:
  - "issue-20260823-121200"
component: "enforcer"
target_modules:
  - ":portal"
target_files:
  - "portal/src/main/kotlin/io/mazewall/portal/ProcessBroker.kt"
effort: "small"
autonomy: "supervised"
open_questions: false
---

# 🟠 [Severity: MEDIUM]: Portal ERROR frames recycle the worker process

**Context:** `ProcessBroker.call` treats every `PortalCallException` as a dead worker, including `PortalKind.ERROR` replies. Guest exceptions (unknown method, Landlock `EACCES` from `TRY_OPEN_HOST_PASSWD`, application failures) therefore `destroy` + `spawnWorker` instead of returning the slot to the idle pool. Timeouts and process death should still recycle; a well-formed ERROR frame should not.

**Needed:**
1. On `PortalKind.ERROR`, throw `PortalCallException` and `idle.put(slot)` — do not `recycleDeadWorker`.
2. Recycle only on I/O failure, timeout, request-id mismatch, or process death.
3. Test: a denied `tryOpenHostPasswd` must not require a new JVM for the following `echo`.
