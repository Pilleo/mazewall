---
title: "Unbounded Thread Creation in SupervisorDaemonEngine (DoS)"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "unknown"
effort: "medium"
github_issue: 77
paperclip_issue_id: 17b23b5d-ee2b-44de-8682-4baae7a5dbc6
paperclip_identifier: MAZ-363
---

# 🔴 [Severity: HIGH]: Unbounded Thread Creation in SupervisorDaemonEngine (DoS)
**Context:** `handleNewConnection` in `SupervisorDaemonEngine.kt:138` spawns a new unmanaged thread `Thread { handleConnection(clientFd) }.start()` for every incoming socket connection without any bounds checking or connection limits. An attacker within the sandbox could repeatedly `connect()` to the supervisor socket, causing an OutOfMemoryError due to unbound thread creation.
**Needed:** Implement a thread pool or restrict the maximum number of concurrent active listener connections to prevent resource exhaustion.
