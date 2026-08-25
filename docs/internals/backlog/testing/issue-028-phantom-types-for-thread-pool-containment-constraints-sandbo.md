---
title: Phantom Types for Thread Pool Containment Constraints (`SandboxedExecutor`)
severity: ENHANCEMENT
status: open
priority: low
dependencies: []
target_files:
- enforcer/src/main/kotlin/io/mazewall/enforcer/ContainedExecutors.kt
target_modules:
- :enforcer
component: enforcer
effort: medium
paperclip_issue_id: 03861f5a-6351-4165-a4e6-e768e0dbc7d0
---

# 🔵 [Severity: ENHANCEMENT]: Phantom Types for Thread Pool Containment Constraints (`SandboxedExecutor`)

**Target:** `io.mazewall.enforcer.ContainedExecutors`
**Context:** Standard `ExecutorService` usage trivially bypasses Tier 2 (thread-scoped) sandboxes if a developer accidentally delegates tasks to global thread pools (e.g., via `CompletableFuture.supplyAsync`).
**Needed:** Introduce `interface SandboxedExecutor<out P : Policy> : Executor`. Require sensitive classes to explicitly depend on this typed executor (e.g., `SandboxedExecutor<Policy.NO_NETWORK>`). This API guardrail forces the compiler to verify that components run on thread pools with the required security baseline, preventing *accidental* architectural leaks of data-oriented workloads. Note: Due to JVM Type Erasure, this does NOT prevent a malicious actor with ACE from reflecting or escaping the sandbox at runtime (which is caught instead by the Tier 1 Process-Wide baseline).
