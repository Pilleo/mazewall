---
title: SupervisorDaemonManager fails to catch throwable during daemon execution leading
  to silently dead supervisor
type: issue
status: resolved
priority: high
labels:
- security
- enforcer
- fail-open
- daemon
component: enforcer
target_modules: [":enforcer"]
target_files: ["enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonManager.kt"]
github_issue: 342
---

# Issue: `SupervisorDaemonManager` silently ignores process death or `Throwable`s

## Context
The enforcer spawns the `SupervisorDaemon` to handle out-of-process seccomp notifications.

**Resolution (2026-08-23, verified in code):** Implemented via the stdout pump lifecycle:
`SupervisorDaemonManager` starts `JvmChildProcess.startStdoutPump` whose `onStreamClosed` callback
detects daemon death, logs `SEVERE` with exit code and dumps the last daemon log lines
(`[SUPERVISOR-DAEMON-CRASH-LOG]`), then invokes `onUnexpectedExit(exitCode)` whose default is
`Runtime.halt(1)` — fail closed by design (a dead supervisor must never leave tracees silently
stranded; halting is preferable to an unbounded USER_NOTIF deadlock). A JVM shutdown hook also
`destroyForcibly()`es the daemon. No heartbeat was needed: stdout EOF is the liveness signal.
Stale issue file resolved without code change.

## The Bug
When `SupervisorDaemon` or `SupervisorDaemonEngine` crashes due to a structural error (e.g. OOM, ABI mismatch, native crash) the Java Process exits.

The JVM side `SupervisorDaemonManager` handles the IPC and process spawning. If the daemon process crashes, the JVM application (the tracee) might not detect this immediately and could become permanently deadlocked waiting for the kernel to receive an ACK for a `SECCOMP_USER_NOTIF`.

## Security / Stability Impact
- **Denial of Service (Deadlock)**: If the supervisor daemon crashes, any trapped syscall in the tracee will hang forever, effectively deadlocking the application process because there's nothing to respond to the seccomp notification.

## Recommendation
Implement a supervisor keep-alive or heartbeat mechanism, or rely on `prctl(PR_SET_PDEATHSIG)` for the daemon (though we care more about the daemon dying while the main app lives). If the daemon dies, the JVM should gracefully exit or log a severe error, though there's no way to resume tracees waiting on the seccomp listener FD once the listener FD owner dies without sending continue.
(Note: Since this is architecture level, verify how `SupervisorDaemonManager` tracks the lifecycle of the daemon process).
