---
title: Architectural DIP (Dependency Inversion) Violations in Native Scopes
severity: HIGH
status: resolved
priority: low
dependencies: []
target_files:
- enforcer/src/main/kotlin/io/mazewall/NativeEngine.kt
target_modules:
- :enforcer
component: enforcer
effort: large
paperclip_issue_id: "8f691e06-4ce7-4572-9d8f-c42fe60744a8"
paperclip_identifier: "MAZ-1431"
---

# ✅ Architectural DIP (Dependency Inversion) Violations in Native Scopes

**Target:** Entire project
**Context:** Many classes directly instantiate `Arena.ofConfined()` or rely on the `LinuxNative` object, making isolated unit testing without a Linux kernel difficult.
**Resolution (2026-09-14):** Native/FFM ownership remains confined to the
platform and FFI packages. Long-lived runtime operations capture a
`NativeEngine` once and accept it explicitly: BPF cache operations, profiler
runtime, socket connection/receipt, UNIX server creation, and supervised
installation all expose injected-native seams. Convenience overloads retain a
single `LinuxNative.snapshot()` only at their composition boundary.

`SupervisorInstaller` now obtains both initial and reused task-session TIDs
from its supplied engine. `ArchitectureTest` rejects any later direct
`LinuxNative` operation from that lifecycle. Platform socket tests prove that
connection, descriptor receipt, and server creation use supplied mock runtimes
without replacing global native state.

**Verification:**

- `./gradlew --no-daemon :platform:test --tests io.mazewall.ffi.networking.SupervisorSocketUtilsTest`
- `./gradlew --no-daemon :enforcer:test --tests io.mazewall.ArchitectureTest`
