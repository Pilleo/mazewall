---
title: "Process portal: pooled workers with crash/restart and call timeouts"
severity: "ENHANCEMENT"
status: "resolved"
priority: high
dependencies:
  - "issue-20260823-121000"
  - "issue-20260823-121100"
component: "enforcer"
target_modules:
  - ":portal"
target_files:
  - "portal/src/main/kotlin/io/mazewall/portal/ProcessBroker.kt"
  - "portal/src/main/kotlin/io/mazewall/portal/PortalWorkerMain.kt"
effort: "medium"
autonomy: "supervised"
open_questions: false
---

# 🔵 [Severity: ENHANCEMENT]: Process portal: pooled workers with crash/restart and call timeouts

**Context:** `ProcessBroker` currently owns a single worker JVM. The design is a Chrome-style **pool** of long-lived workers. Timeouts must kill/restart the worker process, not `Thread.interrupt()` the broker. Spawn must still happen before the broker installs process-wide seccomp.

**Needed:**
1. Configurable pool size (default ≥ 1). Checkout/return per RPC; pin-to-worker is out of scope until stateful APIs exist.
2. On worker death or RPC timeout: destroy the process, spawn a replacement, fail the in-flight call closed (typed exception, no host `Impl()` fallback).
3. Tests: two concurrent echoes on pool size 2; timeout kills the worker; crashed worker is replaced and a later call succeeds.
4. Do not overload `SandboxDispatcher`.
