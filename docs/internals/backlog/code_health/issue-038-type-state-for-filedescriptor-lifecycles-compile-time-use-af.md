---
title: "Type-State for `FileDescriptor` Lifecycles (Compile-Time Use-After-Close Safety)"
severity: "ENHANCEMENT"
status: "open"
priority: 2
dependencies: []
component: "unknown"
effort: "medium"
---

# 🔵 [Severity: ENHANCEMENT]: Type-State for `FileDescriptor` Lifecycles (Compile-Time Use-After-Close Safety)

**Target:** `io.mazewall.core.FileDescriptor`
**Context:** Current FD safety relies on runtime validity checks. Use-after-close errors result in runtime crashes rather than being caught by the compiler.
**Needed:** Introduce a second Phantom Type parameter `Lifecycle` (e.g., `FileDescriptor<Role, Open>`). Methods like `close()` should consume an `Open` FD and return a `Closed` one, making any subsequent usage of the `Closed` token a compile-time error.
