---
title: "Bound portal RPC transport and share closeable capability ownership"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "portal"
target_modules: ["platform", "portal", "portal-worker", "portal-codegen"]
target_files:
  - "portal/src/main/kotlin/io/mazewall/portal/PortalChannel.kt"
target_symbols:
  - "PortalChannel"
open_questions: false
---

# 🟡 [Severity: HIGH]: Bound portal RPC transport and share closeable capability ownership

**Context:**
Task 6: per-stage timeout resets and blocking FD transfer can hang RPCs; partial FD handoff and capability aliases lack deterministic close ownership.

**Needed:**
1. Use one RPC deadline and nonblocking transport; share idempotent capability owners through worker dispatch and cleanup.

**Resolution (Task 6, 2026-09-09):** One absolute broker deadline spans checkout,
request bytes, descriptor sends and response receipt. Readiness-polled nonblocking
portal sockets and MSG_DONTWAIT descriptor operations cannot restart the budget.
Partial-frame failures terminate the channel and retire all partial received owners.
Capability.ReadFd now owns an idempotent, synchronized close transaction; active borrows
exclude close, post-close use rejects, and pre-native failures retain cleanup for retry.
Generated worker dispatch and final cleanup use the same owner instances.

**Evidence:** Platform, portal, portal-worker and portal-codegen host tests pass;
portal integration passes including shared checkout/RPC budget. Native transport tests
cover EAGAIN readiness retry and zero-byte send rejection. Portal tests cover stalled
write, fragmented header/payload, absent/partial FD sequences, no leaked received FD,
descriptor order, idle-vs-terminal timeout, expired budgets and ownership races.
Reflective generated dispatch verifies that guest close and final cleanup share an owner.
Portal/worker detekt passes. See task-6-report.md in the scoped SDD directory for commands.

---
<!-- id: issue-20260909-205031-376-bound-portal-rpc-transport-and-share-closeable-cap  file: issue-20260909-205031-376-bound-portal-rpc-transport-and-share-closeable-cap.md -->
