---
title: "`poll` EINTR Logic Bug Causes Process Deadlock via Blocking `read`"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: `poll` EINTR Logic Bug Causes Process Deadlock via Blocking `read`

*   **Status:** RESOLVED (June 2026)
*   **Dimension:** Vulnerability Chaining & Concurrency (The Sandbox View)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt`
*   **Context & Proof:** If `poll` is interrupted by a signal, it returns a false positive for data readiness, leading to a blocking `read` that will never return if the JVM hasn't sent the data, permanently hanging the supervisor thread and the tracee.
*   **Fix:** Wrapped the `poll` call in a loop inside `readAndHandleJvmResponse` that correctly handles `EINTR`, updates the remaining timeout dynamically, and retries the poll instead of returning `1L` to trigger a premature blocking read.
