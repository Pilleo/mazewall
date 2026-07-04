---
title: "Unhandled `SYS_ACCEPT` in SupervisorSessionHandler"
severity: "HIGH"
status: "resolved"
---

# 🔴 [Severity: HIGH]: Unhandled `SYS_ACCEPT` in SupervisorSessionHandler
**Context:** The `SupervisorSessionHandler` correctly handles `SYS_CONNECT` to establish a new file descriptor but lacks an equivalent handler to reconstruct a valid server side file descriptor dynamically via a delegated `SYS_ACCEPT` call to `LinuxNative.networking.accept()`. Without this, network listeners initialized inside the restricted target JVM environment are entirely blind and reject incoming packets.
**Resolution:** Implemented `handleAcceptAsync` to intercept `SYS_ACCEPT` and `SYS_ACCEPT4`. We safely duplicate the tracee's listening socket file descriptor using `pidfd_open` and `pidfd_getfd` by resolving the target thread's group leader ID (Tgid) via `/proc/TID/status`. This bypasses socket-level `/proc/TID/fd` open restrictions (which fail with `ENXIO`). Once duplicated, the supervisor performs the accept asynchronously on the duplicated file descriptor, then injects the accepted client socket descriptor back to the tracee via `SECCOMP_IOCTL_NOTIF_ADDFD`.
