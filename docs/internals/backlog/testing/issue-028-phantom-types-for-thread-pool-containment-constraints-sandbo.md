---
title: "Phantom Types for Thread Pool Containment Constraints (`SandboxedExecutor`)"
severity: "ENHANCEMENT"
status: "open"
priority: 2
dependencies: []
component: "enforcer"
effort: "medium"
---

# 🔵 [Severity: ENHANCEMENT]: Phantom Types for Thread Pool Containment Constraints (`SandboxedExecutor`)

**Target:** `io.mazewall.enforcer.ContainedExecutors`
**Context:** Standard `ExecutorService` usage trivially bypasses Tier 2 (thread-scoped) sandboxes if a developer accidentally delegates tasks to global thread pools (e.g., via `CompletableFuture.supplyAsync`).
**Needed:** Introduce `interface SandboxedExecutor<out P : Policy> : Executor`. Require sensitive classes to explicitly depend on this typed executor (e.g., `SandboxedExecutor<Policy.NO_NETWORK>`). This API guardrail forces the compiler to verify that components run on thread pools with the required security baseline, preventing *accidental* architectural leaks of data-oriented workloads. Note: Due to JVM Type Erasure, this does NOT prevent a malicious actor with ACE from reflecting or escaping the sandbox at runtime (which is caught instead by the Tier 1 Process-Wide baseline).
