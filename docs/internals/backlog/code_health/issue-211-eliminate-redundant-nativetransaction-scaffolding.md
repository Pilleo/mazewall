---
title: "Deprecate and Eliminate Redundant NativeTransaction Scaffolding"
severity: "HIGH"
status: "open"
priority: 10
dependencies: []
component: "enforcer"
effort: "medium"
---

# 🔴 [Severity: HIGH]: Deprecate and Eliminate Redundant NativeTransaction Scaffolding

**Context:**
Currently, `io.mazewall.NativeTransaction`, `io.mazewall.TransactionManager`, and `io.mazewall.ffi.internal.RealTransactionManager` exist as a capability-based transaction layer wrapping all native calls in `NativeEngine` and `LinuxNative`. 

However, deep analysis reveals that `NativeTransaction` is an incomplete and redundant abstraction:
1. **No Runtime Enforcement or Logic:** `RealTransactionManager.withTransaction` merely returns a static dummy singleton (`object : NativeTransaction {}`). It does not perform locks, rollback tracking, carrier thread validation, or state management.
2. **Redundant Memory Lifecycle:** Off-heap memory lifecycle management is already fully implemented using `NativeArena` and `nativeScope { ... }` in `io.mazewall.ffi.memory`.
3. **Misleading Mental Model:** The term "Transaction" implies database-style atomic commit/rollback semantics, which do not apply to permanent kernel-level system calls (e.g., `seccomp` filter installation or `landlock_restrict_self`).
4. **Future Capability Separation:** If read/write privilege separation (e.g., `ReadOnly` vs `Mutating` operations) is needed in the future, it should be implemented directly on `NativeEngine` interfaces (e.g. `NativeEngine.readOnly` vs `NativeEngine.mutating`) rather than forcing call sites into wrapper blocks.

**Needed:**
1. **Remove `NativeTransaction` Scaffolding:**
   - Delete `NativeTransaction.kt`, `TransactionManager.kt`, and `RealTransactionManager.kt`.
   - Remove `withTransaction` methods from `NativeEngine` and `LinuxNative`.
   - Remove `context(_: NativeTransaction)` annotations from all methods in `NativeEngine`, `RawSyscallOperations`, `NativeFileSystem`, `NativeNetworking`, etc.
2. **Clean Up Call Sites:**
   - Update all enforcer core classes and integration/unit tests to invoke `LinuxNative` and `NativeEngine` methods directly without wrapping them in `withTransaction { ... }` closures.
3. **Update Documentation & Diagrams:**
   - Update `enforcer_class_diagram.puml` / `.svg` and internal design documents (`containment-design.md`, `enforcer_map.md`) to reflect the simplified `NativeEngine` architecture.
