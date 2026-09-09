---
title: "Preserve supervised socket descriptions with bounded cancellable helper processes"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules: [":enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SocketRouteExecutor.kt"
target_symbols:
  - "SocketRouteExecutor"
open_questions: false
---

# 🟡 [Severity: HIGH]: Preserve supervised socket descriptions with bounded cancellable helper processes

**Context:**
Supervised connect created a replacement stream socket and returned an injected descriptor; blocking accept used unbounded non-cancellable JVM workers.

**Needed:**
1. Import the original socket and execute authorized bounded snapshots in a daemon-owned helper process pool.

## Resolution (2026-09-09)

Connect now imports the original open-file description and replies with zero; accepted descriptors retain ADDFD. A daemon-owned persistent helper-JVM pool bounds active operations (default 4) and queued operations (64), rejects overflow with EAGAIN, and terminates/reaps cancelled helpers before replacing their slots. IPC snapshots are bounded and immutable; accepted descriptors and peer-address writes are handed off through the terminal reply gate.

Evidence: full enforcer host suite (790 tests, zero failures/errors, two existing skips), three fresh-JVM USER_NOTIF tests (connect, accept, permanent registration; no skips), detektMain, and regenerated enforcer class diagrams passed. Regression coverage includes TCP/UDP identity, EINPROGRESS/native errno, overload, blocked cancellation/replenishment, helper crashes/reuse, late descriptor disposal, and interrupted ownership cleanup.

---
<!-- id: issue-20260909-183425-220-preserve-supervised-socket-descriptions-with-bound  file: issue-20260909-183425-220-preserve-supervised-socket-descriptions-with-bound.md -->
