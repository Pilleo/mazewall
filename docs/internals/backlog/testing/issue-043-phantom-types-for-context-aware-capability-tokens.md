---
title: Phantom Types for Context-Aware Capability Tokens
severity: ENHANCEMENT
status: open
priority: 2
dependencies: []
target_files:
- enforcer/src/main/kotlin/io/mazewall/Policy.kt
target_modules:
- :enforcer
component: enforcer
effort: large
---

# 🔵 [Severity: ENHANCEMENT]: Phantom Types for Context-Aware Capability Tokens

**Target:** originally `io.mazewall.NativeTransaction` and `io.mazewall.LinuxNative`
**Current state:** `NativeTransaction` / `TransactionManager` were removed in resolved issue-211. They were a dummy singleton with no enforcement. Any future read/write split belongs on `NativeEngine` traits (`NativeProcess` vs a read-only memory trait), not a resurrected transaction wrapper.
**Context:** Historically, `NativeTransaction` was described as a blanket capability token. That type no longer exists. The remaining gap is that a profiler/audit caller can still hold a full `NativeEngine` and call `prctl` / `socket`.
**Needed:** Implement context-sensitive capability tokens using **Phantom Types**.
1. Define marker interfaces `ReadOnly` and `ReadWrite`.
2. Refactor `NativeTransaction` to `NativeTransaction<Mode>`.
3. Update `NativeEngine` methods to demand specific modes via context receivers, e.g., `context(_: NativeTransaction<out ReadOnly>)` for `processVmReadv` and `context(_: NativeTransaction<ReadWrite>)` for `prctl`. This ensures at compile-time that restricted scopes cannot perform mutating operations.
