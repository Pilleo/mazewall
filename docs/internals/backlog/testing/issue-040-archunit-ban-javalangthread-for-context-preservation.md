---
title: "ArchUnit: Ban `java.lang.Thread` for Context Preservation"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: MEDIUM]: ArchUnit: Ban `java.lang.Thread` for Context Preservation

**Target:** Entire project
**Context:** Direct usage of `java.lang.Thread` or standard `Executors` ignores `mazewall`'s thread-local containment states and structured concurrency requirements, leading to "context leaks" where sandboxed tasks execute unconstrained.
**Needed:** Implement an ArchUnit rule banning raw thread instantiation and unmanaged executor usage. Force all asynchronous execution through managed `Coroutines` or `ContextAwareExecutor` implementations.
