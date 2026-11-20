---
title: "Select a bounded storage recovery budget for the exhausted offline Node build"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "core"
target_modules: []
target_files:
  - ".superpowers/sdd/2026-10-25-hotspot-offline-build-resume/worker-preparation-2/service.py"
target_symbols:
  - "main"
  - "run_solve"
open_questions: true
---

# 🟡 [Severity: MEDIUM]: Select a bounded storage recovery budget for the exhausted offline Node build

**Context:**
Actual original offline node-builder production exhausted retained storage after a successful momentary allocation probe; zero images qualified. First exhausting allocation, numeric inner solve exit and compiler peak remain unknown. Recovery requires explicit operator budget selection; preserve old data and evidence.

**Needed:**
1. Review the Task3 raw-evidence outcome and prepared additional16GiB worker-only proposal; explicitly select or decline global storage ceiling40GiB to56GiB before any new source/custody/policy review or fresh approved bounded batch.

## Evidence and scope

- [Task3 independent outcome review](../../../.superpowers/sdd/2026-10-25-hotspot-offline-build-resume/final-outcome-review.md), [actual diagnostic3 acquisition](../../../.superpowers/sdd/2026-10-25-hotspot-offline-build-resume/diagnostic-3/acquisition-report.md) and [second worker report](../../../.superpowers/sdd/2026-10-25-hotspot-offline-build-resume/continuation-task-2-execution-report.md) establish production storage exhaustion in the actual original offline Node build. The selected original solve log is node-builder; no numeric node-builder/buildctl exit or first exhausting allocation is proved.
- Current acquired filesystem device1792/mount50 has exactly **0 available bytes**:4096-byte fragments,3065634 total blocks/12556836864 total bytes,4096 free blocks/16777216 free bytes;596547 free/available inodes. Free bytes are not available bytes; inode exhaustion is not established. These are diagnostic-time, not failure-time/peak samples; current SFTP charge remains unknown.
- The actual node-builder probe **passed**: allocated5428490240 bytes across11 files against required5428448742 at6832472064 available bytes (ring-fenced6001782784). A momentary successful allocation is not a compiler/graph peak guarantee. This issue does **not** claim the probe algorithm is wrong; the other four capacity receipts are missing, not failed probes.
- Actual executor exit1/FAILED_WITH_OWNED_STOP; controlled harmless OCI qualification passed and both independently owned stops passed (qualification7/production2 captured identities,0 survivors; empty/absent cgroups,0 OCI states, closed listeners/held FDs, terminal units). The full durable production failure slot says OSError/errno28 ENOSPC/solveEvidence=null. Daemon ENOSPC error/trace-persistence failures corroborate exhaustion, not the first exhausting allocation; ordinary production-FAILED.json is empty.
- node-builder.oci.tar is a retained root:root regular **zero-byte mode0666** partial, expected0600; other four exports are missing. All five rejected/unqualified, no archive body read/hash; **0 qualified images**. Original solve numeric exit, first exhausting allocation, failure-time/compiler peak and safe repair size remain UNKNOWN. No historical report or UNKNOWN is rewritten.
- Frozen [service source](../../../.superpowers/sdd/2026-10-25-hotspot-offline-build-resume/worker-preparation-2/service.py), [source/task review](../../../.superpowers/sdd/2026-10-25-hotspot-offline-build-resume/continuation-review.md) and [final worker review](../../../.superpowers/sdd/2026-10-25-hotspot-offline-build-resume/final-worker-review.md) are evidence, not targets authorized for in-place repair.

## Required operator selection; no effects authorized

Review the prepared [additional16GiB storage proposal](../../../.superpowers/sdd/2026-10-25-hotspot-offline-build-resume/storage-recovery-proposal.md): **PROPOSED_NOT_EXECUTED**. Explicitly select an additional17179869184-byte worker-only envelope inside the same existing VM and global ceiling42949672960→60129542144 bytes (**40→56GiB**), preserving the complete old12GiB envelope, seeds, caches, partials, reservations and evidence. Proposed backing17163091968 plus inclusive outside custody16777216 equals16GiB; reserved total39912784468→57092653652. This is not VM resizing, a measured minimum or guaranteed compiler fit.

Only after that selection may a new source/custody/policy package be prepared/reviewed and a fresh exact bounded batch approved. Future pre-allocation admission must independently prove current VM-root available>=21474836480 bytes (new16GiB+4GiB system headroom), host growth budget and old/new-root identity separation. No current admission was performed here; budget selection alone is not execution approval.

No automatic prune, deletion, resize, retry, old clock/approval reuse, guest query/probe, public acquisition, network/permission/native grant, native application/collector/profile or integration action. Preserve captured issues019/431/520 and all old frozen references unchanged/open/unarchived. Current public181MiB charged/75MiB remaining, latest resolution-source ledger,1136MiB withheld and inclusive134217728-byte host P cap remain unchanged; all new documentation is charged. [Environment](../../../.superpowers/sdd/2026-10-25-hotspot-offline-build-resume/environment-scope.md) and [integration gates](../../../.superpowers/sdd/2026-10-25-hotspot-offline-build-resume/integration-gates.md) remain BLOCKED_BY_IMAGES; full integration is incomplete.

---
<!-- id: issue-20261026-003721-395-select-a-bounded-storage-recovery-budget-for-the-e  file: issue-20261026-003721-395-select-a-bounded-storage-recovery-budget-for-the-e.md -->
