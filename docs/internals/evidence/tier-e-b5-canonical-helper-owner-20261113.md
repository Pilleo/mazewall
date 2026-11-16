# B5 canonical helper owner with proc-view limits — bounded evidence, 2026-11-13

## Accepted scope

Root accepted this bounded increment as **ACCEPT_SCOPED_CANONICAL_HELPER_OWNER_WITH_PROC_VIEW_LIMITS**. It changes no public API or native ABI and closes no issue. The implementation is uncommitted on top of HEAD `186aee7c7c81c73074be50201d9e1f3e6b8a7446`.

An optional checked-support acquisition retains the manager built from the captured engine while the original canonical owner is `STARTING`, before helper startup and Tier 1. The exact returned helper context is bound to the original gate, actor, and association. Unsupported, absent, non-real, and ordinary default acquisitions do not start a helper. Startup failure is handled by automatic rollback; any thrown failure remains `Unknown` on the same owner, without another stop attempt.

After successful startup, retirement stops the supervisor manager once only after the exact runtime/domain/native/session/pins/original resources have retired and manager termination is established, or when the original runtime is still null after a later construction failure. Manager stop is not proof that the helper process exited. Before `Complete`, the original helper PID/start identity is observed under a stable captured `/proc/self`, PID-namespace, and proc-mount view. The original identity being gone or replaced by a valid different incarnation permits completion; the same/live identity, zombie, denied, malformed, or uncertain observations remain `Unknown`. No PID-derived kill or synthetic closeable is used. Fatal and interrupt identity is preserved, and the in-flight marker is cleared in `finally`.

The checked helper path therefore requires a readable stable proc view without `hidepid`; this is a trusted-host premise, not universal proof against privileged mount changes. Legacy defaults are unchanged. Synthetic reflection, mock-launcher, and probe fixtures do not qualify protected-native behavior or an actual helper process. The retained context establishes ownership only, not Live/Ready attachment. Live/B6 remain closed; issues 338 and 752 and raw-VM limitations remain unresolved.

## Full canonical001 gate

Manifest SHA-256: `9a49b2fbe92add3b465393566a057babc0806a749b7ac707c7e1a87498103850`. The run took 36m50s across 248 tasks (102 executed, 146 up-to-date) and 19 outer Test tasks. Its 694 XML files record 4,716 invocations, 45 skips, zero failures, and zero errors. Gradle, pipeline, wrapper, and container exited 0; physical retirement passed. Full enforcer unit coverage was 82,432/100,520 and combined coverage was 82,864/100,520.

Focused unit006 recorded 1,528 tests, two skips, zero failures/errors, and coverage 82,439/100,520. Focused static/ABI002 recorded 68 unskipped checks and passed. Full main Detekt and SpotBugs were UP-TO-DATE; their backing focused static and unit runs passed. Coverage verification executed.

The run recorded 61 fresh child files, establishing freshness/identity only; no per-case or class-probe claim is made. The 15-second setting applied only to `:enforcer:integrationTestFreshJvm`; default five seconds and full-run propagation remain `NOT_SAMPLED`. Original cold 20+20, the 45 skips, broader G4/G5, P5/P6, T4, and vendor/JFR remain unqualified. Three profiler socket `FileSystemException`s with message `Function not implemented` and three suppressed directory exceptions leave issue 752 retirement `Unknown`; these are distinct from simulated Landlock `ENOSYS`. Prior RED classifier/lint/unit/static packets remain failed history and are not relabeled.

## Limits

This checkpoint does not establish actual protected-native or actual-helper-process qualification, final production producer/custody, cold-file closure, or Live/B6 activation. The old time-scoped checkpoints remain historical. Issue 842 remains open and its Needed items remain unchecked.
