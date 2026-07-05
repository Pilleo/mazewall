---
title: "Compile-Time Enforced Tier 1 Process Baseline (`ProcessContainmentToken`)"
severity: "ENHANCEMENT"
status: "open"
priority: 2
dependencies: []
component: "enforcer"
effort: "medium"
---

# 🔵 [Severity: ENHANCEMENT]: Compile-Time Enforced Tier 1 Process Baseline (`ProcessContainmentToken`)

**Target:** `io.mazewall.enforcer.ContainedExecutors`
**Context:** `mazewall`'s Threat Model explicitly states that Tier 1 (process-wide `NO_EXEC` baseline) is an absolute architectural backstop against Arbitrary Code Execution (ACE) thread-hopping escapes. If a developer creates a Tier 2 (thread-scoped) sandbox without installing Tier 1, the system is highly vulnerable.
**Needed:** Make `ContainedExecutors.installOnProcess()` return a `ProcessContainmentToken<Tier1>` singleton. Modify `ContainedExecutors.wrap()` (which creates Tier 2 thread pools) to require this token as an argument. This forces developers to mathematically prove to the compiler that the Tier 1 process-wide baseline has been successfully installed before they can spawn a Tier 2 thread-scoped sandbox.
