---
title: "Make portal worker transport and startup failure terminal"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "portal-worker"
target_modules: [":portal-worker", ":portal"]
target_files:
  - "portal-worker/src/main/kotlin/io/mazewall/portal/worker/PortalWorkerMain.kt"
target_symbols:
  - "PortalWorkerMain"
open_questions: false
---

# 🟡 [Severity: HIGH]: Make portal worker transport and startup failure terminal

**Context:**
Worker receive collapsed native I/O errors into clean peer closure; startup acquisitions lacked one cleanup boundary and fatal transport events were mislabeled protocol violations.

**Needed:**
1. Distinguish pre-frame EOF from failures, own startup until readiness, and preserve terminal failure identity.

**Resolution (2026-09-10):** A distinct pre-frame EOF exception maps to clean peer closure only when channel cleanup succeeded. Native I/O failures, partial reads/writes, and EOF with cleanup failure retain their failure cause. Startup owns the channel until containment, dispatcher/guest construction, and readiness publication all succeed; failed startup closes it with suppressed cleanup. Fatal driver/transport events terminate all active states, and duplicate failure instances never replace the primary through self-suppression.

**Evidence:** `PortalWorkerReceiveTest` covers clean EOF, idle timeout, I/O errors, incomplete frames, fatal identity, and failed cleanup on EOF (the latter failed RED as a silent PeerClosed). `PortalOwnershipDeadlineTest.clean peer EOF has a distinct outcome but partial header EOF is failure` exercises real UNIX sockets. `PortalWorkerStartupTest` verifies ordering and every startup failure boundary. `PortalWorkerMachineTest` has a 36-cell state/event matrix. Existing driver tests prove one response attempt on partial writes, no fallback error frame, and capability/transport cleanup suppression. Worker and portal suites, process integration tests, and static checks pass.

---
<!-- id: issue-20260910-185837-079-make-portal-worker-transport-and-startup-failure-t  file: issue-20260910-185837-079-make-portal-worker-transport-and-startup-failure-t.md -->
