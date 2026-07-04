---
title: "Potential Buffer Overflow / OutOfBoundsException on Long UNIX Socket Paths"
severity: "LOW"
status: "open"
---

# 🔴 [Severity: LOW]: Potential Buffer Overflow / OutOfBoundsException on Long UNIX Socket Paths
**Context:** In `SupervisorSocketUtils.setupSockAddrUn`, the `socketPath` length is not validated before being copied into the 108-byte `sun_path` array layout of `sockaddr_un`. This causes a memory copy size mismatch (and throws a Java `IndexOutOfBoundsException`) if `socketPath` generated in `SupervisorDaemonManager` (via `System.getProperty("java.io.tmpdir")`) is heavily nested or excessively long.
**Needed:** Add an explicit bounds check like `require(pathBytes.size < 108) { "Socket path too long" }` in `SupervisorSocketUtils.setupSockAddrUn` to fail fast and properly.
