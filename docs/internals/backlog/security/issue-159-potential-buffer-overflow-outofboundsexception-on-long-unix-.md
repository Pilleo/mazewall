---
title: "Potential Buffer Overflow / OutOfBoundsException on Long UNIX Socket Paths"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: LOW]: Potential Buffer Overflow / OutOfBoundsException on Long UNIX Socket Paths

*   **Dimension:** FFM ABI & Memory Safety (The Low-Level View)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/ffi/networking/SupervisorSocketUtils.kt`
*   **Failure Hypothesis:** If `socketPath` exceeds 108 bytes, `setupSockAddrUn` will throw an `IndexOutOfBoundsException` or cause memory corruption when copying path bytes into the `sockaddr_un` FFM struct.
*   **Context & Proof:** In `SupervisorSocketUtils.setupSockAddrUn`, the length of the string is not bounds-checked before copying into the 108-byte `sun_path` struct layout using `MemorySegment.copy(pathBytes, 0, pathSeg, ValueLayout.JAVA_BYTE, 0L, pathBytes.size)`. If the OS temporary directory path (`System.getProperty("java.io.tmpdir")`) is heavily nested, `Files.createTempDirectory` in `SupervisorDaemonManager` could produce a `socketPath` exceeding 108 bytes. This will cause `MemorySegment.copy` to crash the initialization of the supervisor.
*   **Recommendation:** Add an explicit bounds check `require(pathBytes.size < 108) { "Socket path too long" }` in `setupSockAddrUn` and consider using the abstract namespace (`\0` prefix) or `openat`-relative binding if paths get too long.
