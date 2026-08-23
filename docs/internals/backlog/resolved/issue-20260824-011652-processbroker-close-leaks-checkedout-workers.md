---
title: "ProcessBroker.close() Leaks Checked-Out Workers; Recycle Blocks on Full JVM Spawn"
severity: "MEDIUM"
status: "resolved"
priority: high
component: "enforcer"
target_modules:
  - ":portal"
target_files:
  - "portal/src/main/kotlin/io/mazewall/portal/ProcessBroker.kt"
effort: "small"
autonomy: "autonomous"
open_questions: false
dependencies: []
---

# 🟠 [Severity: MEDIUM]: ProcessBroker.close() Leaks Checked-Out Workers; Recycle Blocks on Full JVM Spawn

**Context:** Two related lifecycle gaps in `ProcessBroker`:
1. `close()` only drains the `idle` queue — workers currently checked out by an in-flight
   `call()` are neither tracked nor destroyed, so closing a broker mid-call orphans live worker
   JVMs (each with `installOnProcess` restrictions but real heap).
2. `recycleDeadWorker` destroys the dead slot and then **synchronously spawns** a replacement,
   blocking the caller for a full JVM boot (seconds). A burst of failures serializes into
   repeated multi-second stalls.

**Needed:**
1. Track every spawned slot (e.g. `ConcurrentHashMap<WorkerSlot, Boolean> alive` or an
   all-slots set); `close()` destroys idle AND checked-out slots, and the return-to-pool path
   destroys instead of pooling when the broker is closed.
2. Pre-spawn replacement before destroying the dead slot (`spawnWorker()` first, then
   `destroySlot(dead)`) so recycle latency is bounded by process-start overlap rather than
   serialized boot time.
3. Tests: close-during-inflight-call asserts no surviving worker processes
   (`ProcessHandle` children of the test JVM or `spawnedWorkers()` bookkeeping); recycle test
   asserts replacement slot is ready without waiting for the old one's teardown.

**Resolution (2026-08-24):** Implemented both items with integration coverage:
1. Slot registry (trackedSlots) + closed flag; close() destroys idle AND checked-out workers;
return-to-pool destroys orphans post-close. Test asserts zero surviving PortalWorkerMain
descendants after close-during-inflight.
2. Pre-spawn-before-teardown recycle; test asserts replacement exists immediately after the
crash hook and the pool still serves calls.
