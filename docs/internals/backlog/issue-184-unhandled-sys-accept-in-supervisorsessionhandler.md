---
title: "Unhandled `SYS_ACCEPT` in SupervisorSessionHandler"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: HIGH]: Unhandled `SYS_ACCEPT` in SupervisorSessionHandler
**Context:** The `SupervisorSessionHandler` correctly handles `SYS_CONNECT` to establish a new file descriptor but lacks an equivalent handler to reconstruct a valid server side file descriptor dynamically via a delegated `SYS_ACCEPT` call to `LinuxNative.networking.accept()`. Without this, network listeners initialized inside the restricted target JVM environment are entirely blind and reject incoming packets.
**Needed:** Add handling logic for `SYS_ACCEPT` (or `SYS_ACCEPT4`) in `SupervisorSessionHandler.handleInjectFd`, using `LinuxNative.networking.accept` on the provided `dirfd` argument (representing the bounded socket).
