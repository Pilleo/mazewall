---
title: "Network Isolation via Namespaces (`CLONE_NEWNET`)"
severity: "ENHANCEMENT"
status: "open"
---

# 🔵 [Severity: ENHANCEMENT]: Network Isolation via Namespaces (`CLONE_NEWNET`)

**Context:** Seccomp effectively blocks *new* network connections (`socket`, `connect`), but it cannot prevent data exfiltration over a pre-existing, inherited network file descriptor if the policy permits `write` or `send` calls (which are often needed for file I/O).
**Needed:** Propose an optional process-wide `CLONE_NEWNET` initialization to create a private network namespace. This physically removes the host's routing tables and network interfaces (leaving only loopback), ensuring that even if a process possesses an open socket FD, it has no route to the external network, providing a stronger architectural guarantee than syscall blocking alone.
