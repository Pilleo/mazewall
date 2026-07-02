---
title: "Incomplete EINTR Handling in process_vm_readv and Other Syscalls"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: Incomplete EINTR Handling in process_vm_readv and Other Syscalls

*   **Status:** RESOLVED (June 2026)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/ffi/memory/SupervisorProcessMemoryReader.kt` and other syscalls
*   **Context & Proof:** The `process_vm_readv` call in `SupervisorProcessMemoryReader.readBytes` and `SupervisorProcessMemoryReader.readString` does not check if the `errno` is `EINTR`. If a signal is received during the call, it will return an error, which the current implementation treats as a failure and returns `null`.
*   **Fix:** Wrapped the `processVmReadv` call in `readBytes` in a `while (true)` loop that retries if `errno == NativeConstants.EINTR`, preventing spurious read failures during signal interruptions.
