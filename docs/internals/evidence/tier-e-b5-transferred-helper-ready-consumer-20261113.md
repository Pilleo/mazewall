# B5 transferred-helper Ready consumer with B6 refusal — bounded evidence, 2026-11-13

## Accepted scope

Root accepted this bounded increment as **ACCEPT_SCOPED_TRANSFERRED_HELPER_READY_CONSUMER_WITH_B6_REFUSAL** after the independent full-run audit. This checkpoint does not close issue 842 or complete B5/D4.

The synthetic protocol exercises the path from original context/private capture, through the exact transferred plan and retained actual session before connect, to exact Ready validation and unconditional B6 refusal before worker startup/restrictions. Preparation is counted before publication; callback completion/failure and `finally` settle the transferred obligation, and a never-started worker path aborts it. No successful B6 worker execution or Live activation is claimed.

The six covered cases are: exact Ready reached before B6 refusal; Ready for another binding rejected before worker startup; Ready revalidation failure quarantines before worker startup; blocked connect remains counted and retains resources through the same retirement; post-commit drain retains the helper session before refusing connection; and post-commit callback failure settles the helper preparation obligation. These tests use synthetic relay/protocol and native fixtures. They do not qualify protected-native behavior or an actual helper process.

## Focused and full-gate evidence

Focused unit004 recorded 1,534 tests, two skips, zero failures/errors, and coverage of 82,553/100,654 (82.01661%). Focused lint and SpotBugs reported zero issues. The full main Detekt and SpotBugs tasks were UP-TO-DATE, backed by successful actual static001 and unit004 runs.

Fullproposal001 manifest SHA-256: `3e0ba13420c55a07256a70d06354a8eac668f4eaa1202fc86e50c4e8e63431b8`. Gradle and runner exited 0. The run took 37m16s across 248 tasks (102 executed, 146 up-to-date), with 19 outer Test tasks. Its 694 XML files record 4,722 invocations, 45 skips, zero failures, and zero errors. Enforcer unit coverage was 82,546/100,654 (82.00966%); combined coverage was 82,978/100,654 (82.43885%). Physical container retirement passed. Source comparison was empty, postflight matched, and the index was unchanged.

The run recorded 61 fresh child executions, establishing freshness/identity only; no per-case or class-probe claim is made. Only `:enforcer:integrationTestFreshJvm` used 15 seconds; the default five seconds and full-run propagation remain `NOT_SAMPLED`.

## Failed and incomplete attempts retained literally

- `b5-transferred-helper-red-proposal001` was an expected behavioral RED: the assertion failed with `B6 refused before helper SETUP: ... UnsupportedOperationException: Live stack scoping dispatcher is not available`; Gradle exited 1 while the expected-failure pipeline/runner exited 0. It is not a green test result.
- `b5-transferred-helper-diagnostic-proposal001` exited Gradle 1/runner 0: KtLint reported chain-continuation violations and `transferred live setup remains counted while connect is blocked` failed at line 552.
- `b5-transferred-helper-green-proposal002` exited 95: KtLint violations and the blocked-connect case failed at line 523. `b5-transferred-helper-green-proposal004` exited 95: the blocked-connect case failed at line 536. `b5-transferred-helper-green-proposal008` exited 95: KtLint chain-continuation violations at lines 637 and 663.
- `b5-transferred-helper-unit-proposal001`, `002`, and `003` exited 95 after 1,531, 1,532, and 1,533 tests respectively (two skips each); JaCoCo coverage verification failed, so those packets are incomplete. They are not replaced or relabeled by the later successful unit004.

Successful intermediate green receipts `005`, `007`, and `009` remain their own bounded results (three, four, and six test cases respectively); none is the full-gate record. The final unit004 and fullproposal001 evidence above supersede no failed history.

## Remaining limits

This is a synthetic session/domain protocol result. It establishes neither actual helper-process qualification nor protected-native qualification, successful B6 activation, or whole-B5/D4 completion. B5 worker-lease closure, native qualification, B6/B7/B8, original cold 20+20, Plan A and broader workloads, and issues 338 and 752 remain open or unqualified. The full run also had three primary profiler socket `FileSystemException`s with message `Function not implemented` at log lines 21269, 21384, and 21432, plus three suppressed parent-directory exceptions at 21290, 21405, and 21453. Issue 752 retirement therefore remains `Unknown`; these are not harmless. All 45 skipped invocations remain unqualified. Issue 842 remains open and its existing Needed items remain unchanged.
