---
title: "SeccompDaemonEngine: pollFds out-of-bounds crash + circular LoopAction typealias"
severity: "HIGH"
status: "resolved"
priority: 10
component: "enforcer"
target_modules: [":platform", ":profiler", ":enforcer"]
target_files: ["platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompDaemonEngine.kt", "profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerSessionHandler.kt", "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"]
---

# 🔴 [Severity: HIGH]: Two Critical Bugs Introduced During Reactor Unification

## Bug 1: `IndexOutOfBoundsException` in `SeccompDaemonEngine.run()` (pollFds OOB)

**Context:**
In the single-threaded reactor loop, `pollFds` is allocated at the start of each iteration:
```kotlin
val totalFds = 1L + activeConnections.size  // e.g. 1 slot (server only)
val pollFds = iterationArena.allocate(Layouts.POLLFD, totalFds)
```
After the `poll()` call, if the server FD is readable, `handleNewConnection(serverFd, activeConnections)` is called,
which **appends** a new `SeccompConnection` to `activeConnections`. The subsequent connection-processing loop then
iterates over `activeConnections.indices` (now 1 element), trying:
```kotlin
pollFds.asSlice((i + 1) * 8, 8)  // offset=8, length=8 on a segment of only 8 bytes → CRASH
```

**Impact:** `IndexOutOfBoundsException` in every test that triggers a client connection, crashing the
`SupervisorDaemon` subprocess immediately after handshake. Tests hang indefinitely waiting for a
response that never comes.

**Fix:** Snapshot `connectionCountForThisPoll = activeConnections.size` BEFORE `handleNewConnection` runs.
Use that snapshot count in both the poll-FD setup loop and the result-processing loop.
Any connections added during the iteration are preserved into `nextConnections` without being polled this round.

**File:** `platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompDaemonEngine.kt`

---

## Bug 2: Circular `typealias LoopAction = LoopAction` in `SupervisorSessionHandler.kt`

**Context:**
During the refactor, the following line was added to `SupervisorSessionHandler.kt`:
```kotlin
internal typealias LoopAction = LoopAction
```
This is a self-referential (circular) typealias. Kotlin resolves it ambiguously — the aliased type
shadows the imported `io.mazewall.platform.seccomp.daemon.LoopAction` class. When test code in the
`io.mazewall.enforcer.supervisor` package tries to call `LoopAction.Continue`, Kotlin cannot resolve
the `Continue` companion/nested object because the circular alias loses the concrete class's subclasses.

**Impact:** `Unresolved reference 'Continue'` compile error in `SupervisorSessionHandlerTest.kt`.

**Fix:** Remove the circular typealias entirely. The import at the top of the file already brings
`LoopAction` into scope correctly.

**File:** `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt`
