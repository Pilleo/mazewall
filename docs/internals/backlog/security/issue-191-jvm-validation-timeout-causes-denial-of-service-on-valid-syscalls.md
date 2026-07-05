---
title: "JVM Validation Timeout Causes Denial of Service on Valid Syscalls"
severity: "MEDIUM"
status: "open"
priority: 6
dependencies: []
component: "unknown"
effort: "medium"
---

# 🔴 [Severity: MEDIUM]: JVM Validation Timeout Causes Denial of Service on Valid Syscalls
**Context:** `SupervisorSessionHandler` at line 442 uses a loop that spin-polls on `EINTR` without yield or sleep if the timeout hasn't expired, wasting CPU.
**Needed:** Implement a backoff or yield when receiving repeated `EINTR` on `poll`, and handle signal masks to prevent uninterrupted spin loops.