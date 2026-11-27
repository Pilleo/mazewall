---
title: "Retain replacement worker ownership across broker close races"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "portal-runtime"
target_modules: [":portals:runtime"]
target_files:
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/ProcessBroker.kt"
target_symbols:
  - "spawnWorker"
open_questions: false
---

# 🟡 [Severity: HIGH]: Retain replacement worker ownership across broker close races

**Context:**
Repository-wide review, 2026-11-04: [PRT-02](../reviews/2026-11-04-repository-review-findings.md#prt-02). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P1 · lifecycle/concurrency · NEW expansion of an existing ownership invariant · high source confidence · release boundary: Portal runtime**

**Trigger/interleaving:**

1. Stateless maintenance finishes replacement acquisition. `spawnWorker()` creates `WorkerSlot`, marks its `StartupAttempt` transferred, removes the attempt from `provisionalAttempts`, and returns (`ProcessBroker.kt:591–608`).
2. Close changes lifecycle from Running before replacement publication obtains `lifecycleGate`.
3. The replacement path sees non-Running and calls `destroySlot(fresh)` (`742–752`). Unlike initial startup (`139–141`), it has never put `fresh` in `provisionalSlots`.
4. One cleanup operation fails before completion—for example an injected pre-native socket close failure, or an incomplete reap/pump join. `destroySlot` throws while that resource remains unfinished (`767–838`). Maintenance only logs the failure (`753–755`) and drops its local reference.

**Effect:** Neither live/provisional slot registries nor the startup-attempt registry retains the fresh slot. Once maintenance terminates, `allOwnedResourcesRetired` can report true (`463–470`) and close can publish Closed even though this replacement's cleanup failed. A later close cannot retry the lost owner. Depending on the failed step, the residue is a descriptor, process/reap obligation, pump, endpoint, or cgroup. This is a source-confirmed missing ownership edge, not a claim that a real orphan was observed.

**Smallest complete correction:** Make transfer from `StartupAttempt` to a registered provisional slot continuous and synchronized with close accounting, for both initial startup and replenishment. Move that same owner to the live registry only on accepted publication; retain it on rejected publication until all cleanup succeeds. Avoid a second lifecycle owner or relying on the maintenance stack as the retry registry.

**Regression:** Pause a real/injected replacement at the acquisition-to-publication boundary, close the broker, fail one exact acquired resource's pre-native cleanup once, and release maintenance. First close must report incomplete cleanup and retain that owner; retry must retire it exactly once and verify physical child/pump end. Keep the normal successful replacement and close-race controls. Current `ProcessBrokerIntegrationTest` covers initial provisional rollback and late initial launch (`203–264,413–495`) and ordinary replacement (`769–790,840–854`), but not this combination.

**Mapping/dependencies:** Historical `docs/internals/backlog/resolved/issue-20260909-214715-001-make-portal-broker-lifecycle-transactional.md` promises retained provisional ownership; `resolved/issue-20260824-011652-processbroker-close-leaks-checkedout-workers.md` addresses the older checked-out-slot leak. Neither describes this replacement handoff. P09's existing `issue-20261103-090026-932-bound-stateless-portal-worker-replacement-with-an-.md:40–43` explicitly requires all provisional owners to remain tracked and close races not to leak. Record the concrete defect centrally as an expansion/follow-up; fix the ownership invariant before adding restart budgets or claiming P13 close coverage.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

**Implementation evidence (2026-11-27):** Initial and replacement startup now
register the ready slot before relinquishing its startup attempt. Both use the
same provisional-to-live transfer, and rejection during close leaves the slot
in the close path's ownership.

The real-child integration regression first reproduced a successful close
despite an injected replacement-listener cleanup failure. After the correction,
the first close reports the original failure, both children are reaped, and
the retry closes only the retained listener. The replacement and initial
startup rollback controls, runtime unit tests, ABI validation and lint also
passed on JDK 25.

---
<!-- id: issue-20261103-235253-262-retain-replacement-worker-ownership-across-broker-  file: issue-20261103-235253-262-retain-replacement-worker-ownership-across-broker-.md -->
