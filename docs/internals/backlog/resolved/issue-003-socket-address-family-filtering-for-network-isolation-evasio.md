---
title: "Socket Address Family Filtering for Network Isolation Evasion Prevention"
severity: "ENHANCEMENT"
status: "resolved"
priority: low
dependencies: []
target_files: []
target_modules: [\":enforcer\"]
component: "enforcer"
effort: "medium"
github_issue: 318
paperclip_issue_id: 5caca19b-f1bf-4edb-97c6-c54f2bb59b6e
paperclip_identifier: MAZ-208
---

# 🔵 [Severity: ENHANCEMENT]: Socket Address Family Filtering for Network Isolation Evasion Prevention

**Context:** Currently, `mazewall` blocks networking by disabling `socket` or `connect` completely. This breaks local IPC utilizing Unix Domain Sockets (`AF_UNIX`/`AF_LOCAL`) which are common for DB/daemon integration. NVIDIA OpenShell inspects the first argument (Address Family) of `socket()` to allow `AF_UNIX` while denying `AF_INET`/`AF_INET6`, `AF_PACKET`, etc.
**Needed:** Implement a `SocketAddressFamilyInspector` under `SyscallInspectionPipeline` to filter `socket` syscall arguments, preserving local IPC while preventing internet or raw packet capture.
