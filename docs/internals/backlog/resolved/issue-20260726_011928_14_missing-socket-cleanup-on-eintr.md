---
title: SupervisorDaemonEngine leaks sockets on interrupted accept4
type: issue
status: open
priority: 3
labels:
- security
- enforcer
- fd-leak
component: enforcer
target_modules:
- :enforcer
target_files: [enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonEngine.kt,enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonEngineTest.kt]

github_issue: 376
---

# Issue: `SupervisorDaemonEngine` fails to clean up sockets on interrupted or failed loop

## Context
The daemon loop calls `accept4` to handle incoming trace events.

## The Bug
If `handleNewConnection` or `handleActiveListener` throw exceptions, or if `EINTR` interrupts the main daemon accept loop repeatedly causing it to break, existing connections or pending connections might not be cleanly closed.

## Recommendation
Ensure the daemon lifecycle cleanly closes all `socketManager` resources when breaking out of its main accept loop.
