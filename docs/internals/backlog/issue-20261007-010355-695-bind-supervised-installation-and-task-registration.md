---
title: "Bind supervised installation and task registration to attempt native authority"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: [enforcer, profiler]
target_files: 
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorInstaller.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorDaemonManager.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
  - "enforcer/src/integrationTest/kotlin/io/mazewall/CapturedLandlockBackendNativeTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonManagerTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/internal/DaemonStartupRollbackTest.kt"
  - "docs/internals/designs/enforcer/containment-design.md"
target_symbols: 
  - "SupervisorInstaller"
open_questions: false
---

# 🟡 [Severity: HIGH]: Bind supervised installation and task registration to attempt native authority

**Context:**
C3: supervised installation already accepts explicit native authority, but ordinary installer entry and cached task registration still use the convenience overload that captures current global backend. Forward the installation attempt authority and capture notification support before native callbacks.

**Needed:**
1. Reproduce authority replacement before supervised install/registration, forward captured native and supported feature value, preserve lease acquisition/rollback and listener lifetime.

## Implementation and verification evidence

- Ordinary supervised installation, pre-install daemon acquisition, and cached task-session registration now receive the attempt's selected native backend. Notification support is captured before native callbacks in the explicit installer entry.
- Reusing a live shared daemon authorizes the caller through the supplied backend. Daemon startup resources and retirement remain owned by the manager's lifecycle engine; this slice does not establish a unified application-owned installation context.
- The actual-kernel regression replaces the global backend during Landlock creation, then requires successful USER_NOTIF installation and a genuine fresh-file denial with no foreign-backend calls. The original failure was `PartialInstallationException(landlockApplied=true, seccompApplied=false)` caused by `prctl(PR_SET_PTRACER)` returning `EPERM`; forwarding only filter installation was insufficient because cached daemon authorization still consulted the other backend.
- A host regression independently verifies caller-native authorization for a cached daemon while preserving the original resource owner. The profiler rollback reflection fixture uses the explicit internal signature, without a compatibility overload.
- Focused native tests passed: two cases, zero skips/failures/errors. Full enforcer/profiler host tests, ABI and quality checks passed.
- `./gradlew build integrationTest integrationTestFreshJvm` passed in 3m56s: 272 actionable tasks, 86 executed, 186 up-to-date (`run-1facc4e70c9f`).
- `MAZEWALL_CONTAINER_ENGINE=docker MAZEWALL_DOCKER_HOST=unix:///var/run/docker.sock ./scripts/run_tests.sh` passed in 43s (`run-041ad732a46e`).

The issue remains open for the repository review path. Unified capability/runtime capture, fresh daemon lifecycle ownership, and optional self-verification capture remain separate C3 work.

---
<!-- id: issue-20261007-010355-695-bind-supervised-installation-and-task-registration  file: issue-20261007-010355-695-bind-supervised-installation-and-task-registration.md -->
