---
title: "Portal Worker Idle-Tick and Call-Timeout Coverage With Injected Short Deadlines"
severity: "MEDIUM"
status: "open"
priority: high
component: "testing"
target_modules:
  - ":portal"
  - ":portal-worker"
target_files:
  - "portal/src/main/kotlin/io/mazewall/portal/PortalChannel.kt"
  - "portal-worker/src/main/kotlin/io/mazewall/portal/worker/PortalWorkerMain.kt"
  - "portal/src/integrationTest/kotlin/io/mazewall/portal/ProcessBrokerIntegrationTest.kt"
effort: "medium"
autonomy: "autonomous"
open_questions: false
dependencies: []
---

# 🟠 [Severity: MEDIUM]: Portal Worker Idle-Tick and Call-Timeout Coverage With Injected Short Deadlines

**Context:** The pooled-worker self-exit bug fixed today (worker treated channel read timeouts as
fatal and exited after 30s idle, forcing constant respawn) has **no regression test**: proving it
requires waiting past a deadline that is hardcoded to 30s in `PortalChannel.receive`, which is far
too slow for CI. The fix introduced `PortalReadTimeoutException` (timeout = idle tick vs fatal IO),
but nothing pins that behavior.

**Needed:**
1. Make the worker-side receive timeout injectable: e.g. `PortalWorkerMain` reads an optional
   `-Dio.mazewall.portal.worker.idleTimeoutMs` (default 30_000), passed to `channel.receive`.
2. Integration test with `idleTimeoutMs = 500`: spawn pool, sleep ~1.5s (≥3 idle ticks), then
   issue a normal echo call and assert it succeeds — this fails deterministically on the old
   `break` behavior.
3. Broker-side timeout coverage already exists ("timeout kills worker"); add one assertion that a
   timed-out call produces `PortalCallException` whose cause chain contains
   `PortalReadTimeoutException`, distinguishing deadline expiry from other IO errors.
4. Keep default production behavior unchanged (30s).

