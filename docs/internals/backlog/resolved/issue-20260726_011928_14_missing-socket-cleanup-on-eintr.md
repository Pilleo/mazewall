---
title: SupervisorDaemonEngine leaks sockets on interrupted accept4
type: issue
status: resolved
priority: low
labels:
- security
- enforcer
- fd-leak
component: enforcer
target_modules:
- :enforcer
target_files:
- enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonEngine.kt
github_issue: 376
---

# Issue: `SupervisorDaemonEngine` fails to clean up sockets on interrupted or failed loop

## Context
The daemon loop calls `accept4` to handle incoming trace events.

## The Bug
If `handleNewConnection` or `handleActiveListener` throw exceptions, or if `EINTR` interrupts the main daemon accept loop repeatedly causing it to break, existing connections or pending connections might not be cleanly closed.

**Resolution (2026-08-23, verified in code):** Implemented in
`platform/io.mazewall.platform.seccomp.daemon.SeccompDaemonEngine`:
- `run()` wraps the accept loop in try/finally applying `UnixListenDaemonEvent.AcceptLoopFinished`,
  whose machine effects close the server fd and clear connection/listener tables.
- `handleNewConnection` cleans up on `InterruptedException`, `ClosedByInterruptException`, and any
  `Throwable` (remove from clientSockets + close); EINTR retries without leaking.
- `handleConnection` removes the socket and listener from tables and closes the connection in its
  `finally`, with interrupted-state escalation afterwards.
Stale issue resolved without code change.
