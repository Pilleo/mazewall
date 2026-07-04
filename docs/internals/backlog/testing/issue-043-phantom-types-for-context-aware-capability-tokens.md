---
title: "Phantom Types for Context-Aware Capability Tokens"
severity: "ENHANCEMENT"
status: "open"
---

# 🔵 [Severity: ENHANCEMENT]: Phantom Types for Context-Aware Capability Tokens

**Target:** `io.mazewall.NativeTransaction` and `io.mazewall.LinuxNative`
**Context:** Currently, `NativeTransaction` acts as a blanket capability token, allowing any transaction to perform any native operation (read-only or read-write). This means an auditing or profiling phase can accidentally invoke a mutating system call (like `prctl` or `socket`) when it only intended to read memory.
**Needed:** Implement context-sensitive capability tokens using **Phantom Types**.
1. Define marker interfaces `ReadOnly` and `ReadWrite`.
2. Refactor `NativeTransaction` to `NativeTransaction<Mode>`.
3. Update `NativeEngine` methods to demand specific modes via context receivers, e.g., `context(_: NativeTransaction<out ReadOnly>)` for `processVmReadv` and `context(_: NativeTransaction<ReadWrite>)` for `prctl`. This ensures at compile-time that restricted scopes cannot perform mutating operations.
