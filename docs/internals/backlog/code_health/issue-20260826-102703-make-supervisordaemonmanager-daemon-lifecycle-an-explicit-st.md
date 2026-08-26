---
title: "Make SupervisorDaemonManager daemon lifecycle an explicit state machine"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonManager.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonManagerTest.kt"
target_symbols:
  - "SupervisorDaemonManager"
verify_cheap:
  - "./gradlew :enforcer:test --tests io.mazewall.enforcer.supervisor.SupervisorDaemonManagerTest"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Make SupervisorDaemonManager daemon lifecycle an explicit state machine

**Context:**
`SupervisorDaemonManager` encodes a three-state daemon lifecycle (`NotStarted` / `Running` / `Crashed`) implicitly: `sharedDaemonContext: SupervisorContext?` (`SupervisorDaemonManager.kt:47`) is null-or-not, and "crashed" is only detected lazily via `daemonProcess.isAlive` inside the lock at `getOrSpawnSharedDaemon()` (`:81-94`). Two review hazards follow: (1) every caller must remember that a non-null context can still be dead — nothing in the type expresses it; (2) the happy path performs a side effect (`prctl(PR_SET_PTRACER, ...)`, `:85-87`) on *every* cache hit, mixing authorization refresh with lookup, which is easy to break during refactors. The fail-closed halt path (`onUnexpectedExit` -> `Runtime.halt(1)`) is correct but its precondition (stranded USER_NOTIF waiters) is invisible in the state representation.

**Needed:**
1. Introduce an internal sealed handle, e.g. `private sealed interface DaemonHandle { data object NotStarted; data class Running(val ctx: SupervisorContext); data class Defunct(val ctx: SupervisorContext) }`, stored in a single `AtomicReference`/lock-guarded field; transitions only in two named functions (`spawn()`, `markDefunct()`).
2. Replace lazy `isAlive` probing with explicit transition: when `isAlive` is observed false, transition to `Defunct` once (emit `MazewallEvents.DaemonExited` there), then respawn from `Defunct` — making re-spawn behavior reviewable and idempotent.
3. Move the `prctl(PR_SET_PTRACER)` refresh out of the lookup getter into the spawn/respawn path (or an explicitly named `ensurePtracerAuthorized()`), so the happy path has no hidden syscalls.
4. Unit-test with injected fake `ProcessLauncher`: crash-then-respawn transitions emit exactly one `DaemonExited` event; `stop()` from each of the three states is a no-op or safe shutdown.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260826-102703  file: issue-20260826-102703-make-supervisordaemonmanager-daemon-lifecycle-an-explicit-st.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
