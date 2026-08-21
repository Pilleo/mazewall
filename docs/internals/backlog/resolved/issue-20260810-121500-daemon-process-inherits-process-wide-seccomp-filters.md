---
title: "SupervisorDaemon Inherits Process-Wide Seccomp Filters Leading to ENOSYS"
severity: "HIGH"
status: resolved
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonManager.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
effort: "medium"
autonomy: "supervised"
---

# 🔴 [Severity: HIGH]: SupervisorDaemon Inherits Process-Wide Seccomp Filters Leading to ENOSYS

**Context:**
During a full `./gradlew build`, integration tests run sequentially in the same JVM. An earlier test (`ProcessContainmentTest`) calls `ContainedExecutors.installOnProcess(Policy.NO_EXEC)` or similar, which installs a global, process-wide seccomp filter on the JVM.
When `SupervisorDaemonManager` spawns the `SupervisorDaemon` process using `ProcessBuilder`, the child daemon process inherits this global seccomp filter. When the daemon then attempts to execute system calls (like `openat`) on behalf of a supervised tracee, its inherited seccomp filter rejects the call (often yielding `ENOSYS` because it is an unhandled system call in the global policy).

This explains why `test daemon fast-path allows reads inside java home even from evil context` passes in isolation but fails during a full build with `java.io.FileNotFoundException: ... (Function not implemented)`. To "fix" this previously, `handleInjectFd` was replaced with `sendSeccompContinue` for pointer-based system calls like `openat`. However, as documented in `issue-164`, returning `SECCOMP_USER_NOTIF_FLAG_CONTINUE` for pointer-based system calls after making a security decision based on memory contents is a structurally dangerous TOCTOU vulnerability.

**Needed:**
1. **Revert TOCTOU Vulnerability:** Revert `SupervisorSessionHandler.kt` to use `handleInjectFd` (which uses `SECCOMP_IOCTL_NOTIF_ADDFD`) instead of `sendSeccompContinue` for `openat` and other pointer-based system calls, adhering strictly to the safety invariant from `issue-164`.
2. **Isolate Daemon Process:** Ensure the `SupervisorDaemon` is spawned in a pristine state without inheriting the parent JVM's seccomp filters. This might require isolating tests that mutate the JVM global state (e.g., configuring gradle to run them in a separate fork using `@Isolated` or `forkEvery`), or launching the daemon via a mechanism that drops inherited seccomp filters (though seccomp filters cannot be dropped once applied, so test isolation is the most viable path).
3. **Verify:** Run the full `./gradlew build` suite to ensure `SupervisorProxyIntegrationTest` passes and the TOCTOU vulnerability is closed.
