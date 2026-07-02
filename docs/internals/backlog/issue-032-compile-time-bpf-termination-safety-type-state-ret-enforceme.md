---
title: "Compile-Time BPF Termination Safety (Type-State RET Enforcement)"
severity: "ENHANCEMENT"
status: "open"
---

# 🔵 [Severity: ENHANCEMENT]: Compile-Time BPF Termination Safety (Type-State RET Enforcement)

**Context:** Currently, `BpfBuilder.NrLoaded.build()` can be called on a program that does not end with a `RET` instruction. While the kernel verifier will reject such programs at runtime, it results in a "Fail Closed" crash rather than a compile-time error.
**Needed:** Split `NrLoaded` into `Active` and `Terminated` states. The `ret()` method should transition the builder to the `Terminated` state, and only `Terminated` should expose the `build()` method.
