---
title: "execveat AT_EMPTY_PATH register rewrite on aarch64"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
  - ":platform"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
  - "platform/src/main/kotlin/io/mazewall/ffi/NativeConstants.kt"
effort: "medium"
autonomy: "supervised"
---

# 🟡 [Severity: MEDIUM]: execveat AT_EMPTY_PATH register rewrite on aarch64

**Context:**
Secure exec emulation uses `PTRACE_GETREGS`/`SETREGS` and the x86_64 `user_regs_struct`. On aarch64 the supervisor fail-closes every allowed execve. That is correct (no pathname CONTINUE) but it blocks supervised `ProcessBuilder` on arm64 hosts.

**Needed:**
1. Implement `PTRACE_GETREGSET`/`SETREGSET` (`NT_PRSTATUS`) for aarch64 (`x0`–`x8`, `regs[8]` syscall number).
2. Keep fail-closed if the register rewrite is unavailable.
3. Cover with a unit test that mocks `ptrace` and asserts `execveat` + `AT_EMPTY_PATH` in the written regset.
