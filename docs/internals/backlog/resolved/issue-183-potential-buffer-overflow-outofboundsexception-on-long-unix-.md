---
title: "Potential Buffer Overflow / OutOfBoundsException on Long UNIX Socket Paths"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: Potential Buffer Overflow / OutOfBoundsException on Long UNIX Socket Paths

*   **Status:** RESOLVED
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/ffi/networking/SupervisorSocketUtils.kt`
*   **Context & Proof:** In `SupervisorSocketUtils.setupSockAddrUn`, the `socketPath` length was not validated before being copied into the 108-byte `sun_path` array layout of `sockaddr_un`. This could cause a memory copy size mismatch or a Java `IndexOutOfBoundsException` if a heavily nested or excessively long temp directory path is used.
*   **Fix:** Added an explicit fast-failing validation check `require(pathBytes.size < 108) { "Socket path too long" }` to ensure all socket paths are strictly within safe kernel limits before memory copying.
