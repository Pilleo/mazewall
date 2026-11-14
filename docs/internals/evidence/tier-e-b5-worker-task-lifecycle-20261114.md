# B5 D4 worker/task lifecycle — 2026-11-14

**Accepted scope:** `ACCEPT_STRUCTURAL_TASK_LIFECYCLE_WITH_B6_CLOSED`. This records a bounded structural D4 delivery under [issue 885](../backlog/issue-20261108-113622-885-b5-d4-bind-owned-worker-task-lifecycle-and-guarded.md); [issue 842](../backlog/issue-20261108-141254-842-b5-d4-bind-captured-engine-confined-helper-authori.md) remains the separate helper-authority dependency. D3 was already accepted under 075/080; see the [Pause/resume handoff](tier-e-pause-handoff-20261114.md#b5-d4-worker-task-lifecycle). This does not complete B5 or enable B6.

## Lifecycle and recovery ownership

A Live `TaskBeginReceipt` is prepared and used by `beginCurrent` on the original physical worker. If Begin throws or refuses, `recoverTask(receipt)` is attempted by that same worker before it exits. A recovered `TaskLease` is accepted only for that worker and receives exactly one End; the body does not run. Foreign/rejected task recovery is not published or ended. A task End Pending is retained; End is never retried. In contrast, after physical join only the retirement owner may recover a missing `WorkerOwner` from its `WorkerRegistrationReceipt` with `recoverWorker`, then attempt worker retirement. Task recovery/End never runs after join. See `OwnedInvocation.kt:268–396,399–422` and `OwnedDomain.kt:896–928`.

The Live invocation lease covers body, context application/restoration and synchronous delivery callbacks. Canonical late observations are merged before End and physical completion; fatal precedence and the already committed result are preserved without redelivery. Whole-install cancellation closes admission under the shared gate and performs one native invalidation outside locks. Pending from installation invalidation or installation-retirement progress may be retried only by a later explicit drain after quiescence; Pending worker retirement and task End are retained without retry. Legacy callback/dispatch boundaries remain unchanged. Representative cases are in `ConfinedHelperRuntimeDrainTest.kt:589–622,1405,3021` and the compiled privacy case `OwnedRuntimeTest.kt:1300`.

## Full and ABI evidence

Full manifest `a57f5fa022805a315834c0e73fcfa9899f2afbdc5daece9b9fca9406ff700ede`: runner, Gradle and pipeline exited 0; container retirement passed. The 39m39s run had 248 tasks (103 executed, 145 up-to-date), 694 XML files, 4,757 tests, 45 skips, zero failures/errors and 19 outer test paths. Enforcer unit coverage was 84,940/103,570; combined coverage was 85,367/103,570. The 91 fresh child executions establish identity/freshness only, not per-case probes. Source comparison and index were unchanged, HEAD remained `81b37a3f`, and 16,693 retained rows were verified.

Unit003 recorded 1,569 tests, two skips, zero failures/errors and the same unit coverage. Actual focused Detekt, ratchet, lint and SpotBugs reported zero issues; full enforcer Detekt/SpotBugs/lint were up-to-date. Full enforcer/platform Kotlin ABI checks and ratchet executed.

Legacy ABI002 manifest `7c46883da6b31d980307bb771371bd1001f730b373989bb00c518d2c4b0b0634` separately ran `:enforcer:checkLegacyAbi` and `:enforcer:checkKotlinAbi`; runner/Gradle exited 0 and physical container retirement passed. `internalDumpKotlinAbi` was up-to-date as a generated-output prerequisite/comparison only; no tracked API update resulted. Source comparison and index were empty; 17,435 retained rows were verified. The earlier Legacy001 preview97 stopped before execution and remains inconclusive, not a pass. Compiled privacy checks show no synthetic phase setter, pending getter or cancellation-permit getter; the existing public synthetic phase getter and lock getter remain.

## Limits and retained history

The Live lifecycle uses a synthetic already-installed physical-worker fixture. It does not prove successful production prepare, actual helper/native provenance, protected-native execution, or B6 activation. B6 remains closed; D4 structural delivery is not full B5 qualification. Issue 752 remains `Unknown`: three `profiler.sock` `FileSystemException: Function not implemented` primaries and suppressed directory pairs at 21439/21460, 21554/21575 and 21602/21623.

GREEN004's wrapper exit 95 was a collector failure before copying an empty directory; four Live execution artifacts were retained later. Preserve that as a collection failure, not a behavioral test failure. The [Pause/resume handoff](tier-e-pause-handoff-20261114.md#b5-d4-worker-task-lifecycle) documents the unavailable intermediate `OwnedDomain.kt` bytes/hash/mode after an earlier format/write sequence. This checkpoint does not claim full per-write preimage compliance or reconstruct that intermediate. No prior attempts, issue Needed entries or historical plans are rewritten or archived.


### Workspace-only receipt provenance

The following local ignored artifacts are retained as plain-text provenance; these paths are not portable links. Their bytes were present in this workspace at handoff preparation time.

- D3 reconciliation: `.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/b5-d3-reconciliation-20261113-001.md`; SHA-256 `c25c3e0bd10fa87f4d0cc2056d6e1bd8a9e71632daa649521752e5f3201e1ff3`.
- preimage incident record: `.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-manager-retirement-preparation/confined-handoff-step2-001/d4-preimage-incident-20261113-001.md`; SHA-256 `204502b60f106a891a43a644aba6cd404a09b35352bcab950ddf830e9459eed6`.
