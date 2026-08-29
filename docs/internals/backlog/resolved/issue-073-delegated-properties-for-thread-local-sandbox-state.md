---
title: "Delegated Properties for Thread-Local Sandbox State"
severity: "ENHANCEMENT"
status: "resolved"
priority: low
dependencies: []
component: "enforcer"
effort: "medium"
github_issue: 305
paperclip_issue_id: de231d3b-1c3d-4a3d-a3e7-d694c83952b7
paperclip_identifier: MAZ-257
---

# 🔵 [Severity: ENHANCEMENT]: Delegated Properties for Thread-Local Sandbox State

**Target:** `io.mazewall.enforcer.ContainerStateRegistry.kt`
**Context:** Accessing thread-local state requires explicit `.get()` and `.set()` calls on `ThreadLocal` objects.
**Needed:** Implement property delegates for `ThreadLocal` values. This would allow accessing the current thread's sandbox state as a standard property (`var currentPolicy by ThreadLocalDelegate(...)`), making the code more readable while safely encapsulating the underlying storage.
