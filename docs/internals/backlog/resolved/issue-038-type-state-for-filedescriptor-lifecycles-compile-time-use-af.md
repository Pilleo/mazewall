---
title: "Type-State for FileDescriptor Lifecycles (Compile-Time Use-After-Close Safety)"
severity: "ENHANCEMENT"
status: "resolved"
priority: low
dependencies: []
target_files:
- platform/src/main/kotlin/io/mazewall/core/FileDescriptor.kt
- platform/src/main/kotlin/io/mazewall/core/NativeArg.kt
target_modules:
- :platform
component: platform
effort: medium
---

# ✅ [RESOLVED]: Type-State for `FileDescriptor` Lifecycles (Compile-Time Use-After-Close Safety)

**Status:** RESOLVED (August 2026)
**Target:** `io.mazewall.core.FileDescriptor`
**Context:** FD safety previously relied on runtime validity checks. A closed token could still be passed as a raw syscall argument, and `close()` left the original Open-typed handle reporting `isValid == true`.
**Fix:**
- `FileDescriptor<Role, Open|Closed>` phantom types: `close()` and `use()` exist only on `FdState.Open` and return a `FdState.Closed` view.
- Native I/O APIs (`NativeFileSystem`, `NativeMemory`, `NativeNetworking`, `RawSyscallOperations`, `SocketManager`) accept only `FileDescriptor<*, FdState.Open>`.
- `NativeArg.FdArg` now requires an Open descriptor, so a Closed token cannot be passed to `syscall`.
- Open and Closed views of the same kernel fd share a `FdLifecycle` flag, so the leftover Open-typed variable reports invalid after close (Kotlin cannot consume that variable).
- `FdEpoch` generation table: `close` retires the generation so a leftover Open token cannot I/O on a later kernel reuse of the same integer.
- NativeEngine I/O (real and mock) returns `EBADF` instead of throwing or reaching the kernel when `isLiveForIo()` is false.
- Public `FileDescriptor(int)` constructor removed; mint via `unsafe` / role factories (`generic`, `unixSocket`, `ruleset`, `oPath`, `seccompNotif`).
- `NativeArg.FdArg.asLong` refuses retired tokens.
**Verification:** `FileDescriptorTest` and `FileDescriptorReproductionTest` assert close invalidates both views, generation reuse, constructor reflection, and that Open descriptors can be wrapped as `FdArg`.
