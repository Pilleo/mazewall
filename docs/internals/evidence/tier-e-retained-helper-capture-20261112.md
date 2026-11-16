# Issue 338 retained helper capture — bounded evidence, 2026-11-12

## Accepted scope

The controller classified this increment **ACCEPT_SCOPED_WITH_SEPARATE_RECOVERED_POSTFLIGHT**. It accepts the current B4 domain-owned support transaction only. It does not close issue 338, qualify bare component capture, release Live integration, or establish whole B5 or D4 completion.

The actual confined `OwnedDomain` preattaches its domain-bound capture owner and each genuine typed support slot before the corresponding support open. Acquisition permission linearizes under the domain gate; an already-authorized in-flight open remains counted until preparation quiesces. Cleanup checks the original domain, captured engine, and selected actor before mutation. Each slot close is dispatched once; `Unknown` retains the exact failure and is never redispatched. Other independent, not-yet-dispatched pins may still be closed. Helper/session and support settlement gate release of original resources. The existing bare component capture path remains unchanged. Previous B4 D1 and D2 statuses are not changed by this bounded result.

## Verification and result classification

The unfiltered enforcer unit qualification executed 1,506 invocations, with two skips and zero failures or errors. Enforcer unit instruction coverage was 80,835/98,481. The owning unit output recorded 58 fresh child coverage files.

The full Gradle run exited 0 and reported `BUILD SUCCESSFUL` in 37m20s. Its 694 retained XML files recorded 4,687 invocations, 45 skipped test invocations, and zero failures or errors. Enforcer instruction coverage was 80,842/98,481 unit and 81,269/98,481 combined. Source and index snapshots matched, and physical container retirement was verified.

The **original host wrapper remains failed with exit 95** because its late collector found the child-coverage destination directory absent. The successful Gradle result and the original failed wrapper remain separate outcomes. A separate recovery007 process exited 0 and read 58 previously captured files across 58 JaCoCo sessions, 9,491 class records, and 189 actual enforcer-main owner hits. Its 472 standard synthetic exclusions were excluded from owner-hit counts. Recovery007 is separate postflight evidence; it does not convert the original wrapper to success or verify `test.exec`. The recovery establishes metadata consistency under the operator/team no-subsequent-writer premise and before/after input checks; it does not establish continuous custody, per-read tracing, or attribution of files to individual test cases.

Only `:enforcer:integrationTestFreshJvm` used the nondefault 15,000 ms prewarm setting. The five-second default remains unqualified, and full-run supervisor propagation was `NOT_SAMPLED`. The three profiler socket shutdown exceptions, each with a suppressed directory exception, leave issue 752 process/FD/pump/directory retirement Unknown. The first cleanup actor's interrupt-bit restoration is code-reviewed but was not directly observed. The 45 skipped invocations remain unqualified; they are not a count of distinct capabilities.

## Open limits

Raw-success-to-typed-token, arena and VM/allocation gaps, bare component capture, Live integration, B5 trusted producer/custody/child closure, and unregistered handoff remain open. No named support producer or NoWriters guarantee was added. No native or public activation is released. Issue 338 remains open.

## Local source records

The LOCALONLY controller acceptance is `issue338-b4-retained-capture-controller-acceptance-20261112-001.md` under `.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/` (SHA-256 `56fd5721004b9ca06a5f1e711aab9c976e9f1c79b382d64646e6cd2fe78e4407`). Terminology correction 002 preserves its decision and clarifies that 45 means skipped test invocations. The LOCALONLY final report at `issue338-b4-retained-capture-final-report-20261112-001.md` in the same directory has SHA-256 `9b03f2c35e18a5b5ea6cf741e5abefe2446d39f650b1b1e115354440e96fa72f`. These workspace-local records support local audit and are not linked as portable artifacts.

See the tracked [open issue 338](../backlog/issue-20261106-013649-338-retain-failed-prepared-acquisition-rollback-owners.md) and [containment design](../designs/enforcer/containment-design.md) for the continuing boundaries.
