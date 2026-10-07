---
title: "Capture supervisor daemon lifecycle backend and socket ownership"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: [enforcer, platform]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorDaemonManager.kt"
  - "platform/src/main/kotlin/io/mazewall/platform/socket/SocketManager.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonManagerTest.kt"
  - "docs/internals/designs/enforcer/containment-design.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Capture supervisor daemon lifecycle backend and socket ownership

**Context:**
C3: the daemon manager defaults to the mutable LinuxNative facade and RealSocketManager independently selects global traits for transport. Capture the lifecycle backend once and bind default socket operations to that owner, preserving separate caller authorization.

**Needed:**
1. Reproduce global replacement between manager construction, startup and retirement; ensure owned transport and authorization remain on the selected lifecycle owner.

## Implementation and verification evidence

The manager's default engine is an immutable native snapshot. Default socket transport is bound to that same owner. A shared native-parameterized socket implementation serves live standalone entry and captured owners without duplicating transport logic; acquisition, accept/connect, descriptor transfer and close use explicit traits. Cached daemon caller authorization continues to accept the separate selected caller backend.

Two host regressions failed with the previous defaults (`run-a3b262954446`): replacement before startup redirected ptrace authorization; replacement before retirement redirected shutdown socket acquisition. Both passed after capture along with platform tests (`run-fc9a06ea3976`). Full enforcer/profiler/platform checks, ABI and quality passed (`run-4f51686b77bd`).

`./gradlew build integrationTest integrationTestFreshJvm` passed in 4m30s, 272 tasks (88 executed, 184 up-to-date), including captured backend/provider native cases (`run-2825cadc2324`). Serial caller-mapped full Docker gates passed in 57s (`run-ca08c0e078d2`).

Shared-daemon application lifetime and prepared-runtime ownership remain C7 work; this fixes native lifecycle authority selection, not a new lifecycle API. The issue remains open for repository review.

---
<!-- id: issue-20261007-083749-261-capture-supervisor-daemon-lifecycle-backend-and-so  file: issue-20261007-083749-261-capture-supervisor-daemon-lifecycle-backend-and-so.md -->
