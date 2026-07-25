---
title: "Potential Buffer Overflow / OutOfBoundsException on Long UNIX Socket Paths"
severity: "RESOLVED"
status: "resolved"
priority: 3
dependencies: []
component: "unknown"
effort: "medium"
github_issue: 303
---

# ✅ [RESOLVED]: Potential Buffer Overflow / OutOfBoundsException on Long UNIX Socket Paths

*   **Status:** RESOLVED
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/ffi/networking/SupervisorSocketUtils.kt`, `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonManager.kt`, `profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerDaemonManager.kt`
*   **Context & Proof:** In `SupervisorSocketUtils.setupSockAddrUn`, the `socketPath` length was not validated before being copied into the 108-byte `sun_path` array layout of `sockaddr_un`. This could cause a memory copy size mismatch or a Java `IndexOutOfBoundsException` if a heavily nested or excessively long temp directory path is used (such as under certain nested CI/CD build environments).
*   **Fix:**
    1. Added an explicit bounds validation check `require(pathBytes.size < 108) { ... }` in `SupervisorSocketUtils.setupSockAddrUn` to guarantee absolute FFM boundary safety and fail-fast.
    2. Implemented an automatic fallback mechanism inside `SupervisorDaemonManager` and `ProfilerDaemonManager`: when a generated socket path exceeds the 107-byte limit, the system gracefully cleans up the long path and falls back to generating a safe, short temp directory under the standard `/tmp` mount using the injected `ProcessLauncher` abstraction.
    3. Added new overloaded directory creation capabilities to the `ProcessLauncher` interface, allowing full unit-test mocking without polluting host file structures or performing real I/O.
