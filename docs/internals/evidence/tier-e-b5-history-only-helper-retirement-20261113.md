# B5 history-only helper retirement checkpoint — 2026-11-13

**Scoped acceptance:** `ACCEPT_SCOPED_HISTORY_ONLY_HELPER_RETIREMENT_WITH_SYNTHETIC_LIMITS`.

This checkpoint records a narrowly scoped private retirement observation. For the exact original runtime context in `TransferredSettled`, after all tracked resources are retired, the manager is dead, and startup is quiescent, the owner may make one stop attempt for the original helper if that stop has not already been attempted. This preserves the existing `Unknown` status and history. It does not replay drain or native cleanup, erase history, or turn the private observation into public `Complete`. The default `Complete` path is unchanged.

## Full-gate evidence

Full run manifest: `bcdbfa6fd56665a80482e10d2198a4f07ec798aefa136c67c9adca2ed251a2ce`. The runner exited 0; Gradle reported `BUILD SUCCESSFUL` in 37m21s with 248 tasks (102 executed, 146 up-to-date), 694 XML files, 4,730 tests, 45 skipped, and zero failures/errors. There were 19 expected and actual outer test paths. Unit coverage was 83,438/101,660; combined coverage was 83,875/101,660. The 66 fresh task-owned child executions establish task identity/freshness only, not per-case or per-class probe proof.

Root and independent review confirmed candidate pins match, source comparison is empty, index unchanged (SHA-256 prefix `2439abf5…`), HEAD remains `81b37a3f`, physical container retirement passed, and 16,668 retained rows were verified. Full enforcer Detekt, SpotBugs, and lint analyses were up-to-date; actual clean SpotBugs evidence came from focused unit002. Ratchet and Kotlin ABI checks executed. The only nondefault FreshJvm duration was 15,000 ms for the owning enforcer task.

Focused results: unit002 had 1,542 tests, two skips, zero failures/errors, and 83,431/101,660 coverage. Actual lint and SpotBugs had zero issues. Static004 passed all 68 analysis/ABI cases. The full run logged three `profiler.sock` `FileSystemException: Function not implemented` primaries with suppressed parent-directory exceptions at lines 21294/21315, 21409/21430, and 21457/21478. Issue 752 remains `Unknown`.

## Scope and limits

The composition tests use reflection/Unsafe and a mock process represented by the current JVM; they send no actual signals. They do not establish native provenance, a protected-native positive path, or actual packaged-helper OS behavior. The packaged support-manifest parser and genuine checked-request support extension compile, but the positive packaged helper mode was `MODE_UNLAUNCHED`. The 49 historical support candidates are not an authorized closure and do not establish custody or private-helper physical proof.

B6 remains closed. This checkpoint does not qualify worker leases, Begin/End, cancellation, helper Ready on a protected native path, or B6–B8. Broader cold 20+20, Plan A, G4/G5, P5/P6, T4/vendor JFR, issues 338/752, and issue 842 remain open or unqualified. Needed checkboxes remain unchanged.

## Attempt history retained

- The structural RED for a missing method and its lint failure are not behavioral evidence.
- GREEN002 had five passing tests but its packet failed lint.
- History GREEN001 covered runtime closure and lint; history GREEN002 passed the synthetic history-only case.
- Static001–003 failed their ratchet; unit001 failed the 82% threshold. These outcomes remain failed and are not relabeled.
- Unit002 and static004 results above are the later successful focused evidence.

See the [Pause/resume handoff](tier-e-pause-handoff-20261114.md#b5-history-only-helper-retirement).


### Workspace-only receipt provenance

The following local ignored artifacts are retained as plain-text provenance; these paths are not portable links. Their bytes were present in this workspace at handoff preparation time.

- controller decision: `.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/b5-history-only-helper-retirement-controller-decision-20261113-001.md`; SHA-256 `b74e287dc2755986ffa28a950253d7207ff17dbd9edbd4d0b2a40261217dd45d`.
