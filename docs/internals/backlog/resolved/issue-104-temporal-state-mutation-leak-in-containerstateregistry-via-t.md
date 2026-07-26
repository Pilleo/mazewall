---
title: "🟢 [RESOLVED]: Temporal State Mutation Leak in `ContainerStateRegistry` via Thread-Local Delegates"
severity: "RESOLVED"
status: "resolved"
---

# 🟢 [RESOLVED]: Temporal State Mutation Leak in `ContainerStateRegistry` via Thread-Local Delegates

**Target:** `io.mazewall.enforcer.ContainerStateRegistry`
**Context:** `ContainerStateRegistry` exposed multiple properties backed by a custom `ThreadLocalDelegate` alongside process-wide state variables under a single interface.
**Needed:** Split `ContainerStateRegistry` into two distinct, strongly-typed components: `ProcessStateRegistry` and `ThreadStateRegistry`. Enforce explicit lifecycle bounds and sanitization routines on the `ThreadStateRegistry` when task execution terminates.
**Resolved:** The registry was split into `ProcessStateRegistry` and `ThreadStateRegistry`. Additionally, `ThreadStateRegistry` now includes an explicit `sanitize()` method that purposefully throws an `UnsupportedOperationException`, documenting why clearing ThreadLocals violates immutable OS sandbox semantics and thus explicitly enforcing the lifecycle bound as "OS thread lifetime".
