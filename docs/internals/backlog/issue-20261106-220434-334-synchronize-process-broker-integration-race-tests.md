---
title: "Synchronize process broker integration race tests"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/ProcessBrokerIntegrationTest.kt"
target_symbols:
  - "ProcessBrokerIntegrationTest"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Synchronize process broker integration race tests

**Context:**
Retained full gate 023 shows three integration tests lack reliable overlap synchronization while preserving lifecycle ownership and shared RPC deadline contracts.

**Needed:**
1. Add test-only synchronization for close waiters, cancelled startup, and shared RPC checkout without changing runtime deadlines or ownership assertions.

The existing child fixture supplies a generated echo dispatcher, but the tested sleep RPC is handled directly by the built-in worker dispatcher. The controller approved a test-only host-side pre-send gate, preserving both sleep calls and the actual deadline; it signals after checkout rather than from the worker. The first call retains at most roughly 500 ms of scheduling and orchestration margin around its 1.5-second sleep.

---
<!-- id: issue-20261106-220434-334-synchronize-process-broker-integration-race-tests  file: issue-20261106-220434-334-synchronize-process-broker-integration-race-tests.md -->
