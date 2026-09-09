# Daemon startup ownership migration

Supervisor and profiler managers keep their existing public constructors and context fields.
`getOrSpawnSharedDaemon` now fails if `PR_SET_PTRACER` fails; it no longer returns a daemon
that may be unable to read tracee memory. Custom test/process launchers must pair fake PIDs
with a fake `NativeEngine`, rather than invoke real `prctl` on an invented PID.

`ProfilerDaemonManager.cleanupDaemon(context)` only retires the manager's own child. A
context is descriptive data, not a transferable cleanup capability. Call the owning manager's
`stop()` for deterministic retirement. Dead profiler resources retire before replacement.

Platform's `@MazewallInternal` `ChildStdoutPump` now records whether readiness was actually
observed, separately from output completion. Recompile consumers of this internal helper:
EOF wakes readiness waiters but does not report successful startup. Use
`JvmChildProcess.startStdoutPump` / `awaitReady` instead of manufacturing pump values.

New `ChildStartupTransaction` and `ChildProcessResources` are internal-use platform APIs
(`@MazewallInternal`), not a generic process framework. Retain the provisional transaction in
the manager before calling `runStartup`; acquire endpoint/child/output/hook, verify readiness,
then retain the committed resource owner. The startup boundary requires a commit before success
and aggregates rollback failures explicitly: a fatal cleanup error outranks an ordinary startup
failure, retaining the earlier failure as suppressed evidence. Kotlin `use` does not provide that
precedence and is not the startup boundary. If rollback fails, keep the provisional transaction
reachable and retry `close()` on `stop()` or before any replacement launch. Clear the retained
owner only after successful retirement. The caller remains responsible for its distinct daemon
lifecycle and authority policy.

Profiler's native transport and reply-result types remain module-internal. Native syscall
signatures, layouts, notification encoding, and portal wire formats are unchanged.
