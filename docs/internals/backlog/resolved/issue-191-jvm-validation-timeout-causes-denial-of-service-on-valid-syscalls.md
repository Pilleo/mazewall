---
title: "JVM Validation Timeout Causes Denial of Service on Valid Syscalls"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "unknown"
effort: "medium"
github_issue: 249
paperclip_issue_id: 725013b6-c7d7-43cf-a29f-7b89060fa7cb
paperclip_identifier: MAZ-368
---

# 🔴 [Severity: MEDIUM]: JVM Validation Timeout Causes Denial of Service on Valid Syscalls
**Context:** `SupervisorSessionHandler` at line 442 uses a loop that spin-polls on `EINTR` without yield or sleep if the timeout hasn't expired, wasting CPU.
**Needed:** Implement a backoff or yield when receiving repeated `EINTR` on `poll`, and handle signal masks to prevent uninterrupted spin loops.
