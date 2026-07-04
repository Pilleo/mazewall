---
title: "🟢 [WONTFIX]: Permanent thread pool contamination, classloader leaks, and state pollution via un-cleared `ThreadLocal` variables"
severity: "MEDIUM"
status: "resolved"
---

# 🟢 [WONTFIX]: Permanent thread pool contamination, classloader leaks, and state pollution via un-cleared `ThreadLocal` variables

**Target:** `/enforcer/src/main/kotlin/io/mazewall/enforcer/ContainedExecutors.kt` and `ContainerStateRegistry.kt`
**Context:** Standard JVM thread pools reuse worker threads. Since the sandbox tracks thread-scoped seccomp and Landlock states using `ThreadLocal` registers but never clears them when a wrapped task finishes, the thread-scoped security state leaks permanently into subsequent tasks on the same thread, causing unexpected `IllegalStateException` throws or ClassLoader memory leaks during redeploys.
**Resolution (WONTFIX):** See resolution for `ContainedExecutors Thread-Local State Persistence and Poisoning` below. Clearing `ThreadLocals` breaks critical deduplication and violates immutable OS sandbox semantics. Users must manage thread pool lifecycles directly (via `shutdown()`) for restricted tasks.
