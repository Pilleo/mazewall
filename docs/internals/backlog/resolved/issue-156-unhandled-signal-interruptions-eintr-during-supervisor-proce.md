---
title: "Unhandled Signal Interruptions (`EINTR`) during Supervisor `process_vm_readv` Socket Message Tracing"
severity: "RESOLVED"
status: "resolved"
priority: medium
paperclip_issue_id: 2f3b38de-2637-46f9-a6fb-7d5901dbbbbc
paperclip_identifier: MAZ-332
---

# ✅ [RESOLVED]: Unhandled Signal Interruptions (`EINTR`) during Supervisor `process_vm_readv` Socket Message Tracing

*   **Status:** RESOLVED (June 2026)
*   **Dimension:** Vulnerability Chaining & Concurrency (The Sandbox View)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/ffi/memory/SupervisorProcessMemoryReader.kt`
*   **Context & Proof:** The `process_vm_readv` syscall can be interrupted by a signal, failing with `EINTR`. If this happens, the method incorrectly returns `null` instead of retrying.
*   **Fix:** Wrapped the `processVmReadv` call inside `SupervisorProcessMemoryReader.readBytes` in a retry loop on `EINTR` to guarantee memory reads are not interrupted.
