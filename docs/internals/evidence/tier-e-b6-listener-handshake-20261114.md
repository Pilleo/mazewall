# B6 bounded listener handshake — accepted first increment — 2026-11-14

Root accepted `ACCEPT_BOUNDED_LISTENER_HANDSHAKE_WITH_PRODUCTION_GUARDS_CLOSED`. This accepts only issue 094's first increment: authenticated original-domain/worker connection binding, Live prelude, one-shot listener admission association, and bounded retirement. Both production Live refusal guards remain closed. B6 is not complete or activated.

See the [Pause/resume handoff](tier-e-pause-handoff-20261114.md#b6-listener-handshake) and [Pause/resume handoff](tier-e-pause-handoff-20261114.md#b6-listener-handshake). [Issue 094](../backlog/issue-20261114-023757-094-b6-bind-authenticated-listener-authorization-to-ex.md) remains open for the later task-observation and effects/lifetime gates.

## Accepted source and protocol boundary

The original worker mints one receipt. The coordinator authenticates the actual validation socket before descriptor transfer. Admission binds the exact accepted Setup, socket, daemon record and received descriptor; request-carried identifiers are comparison values, not authority. Live admission becomes Ready only after successful transmission of the Ready frame. Stop, failure and retirement revoke admission before blocking cleanup. Each Setup retains at most 200 spent attempts with no eviction; exhaustion refuses.

Live denial uses the existing `SupervisorReplyInterpreter` and original reactor reply lease. It cannot become Legacy inspection, `CONTINUE`, `READ`, or `ADDFD`. The architecture rule rejecting direct `UserNotifReply.send` was preserved and passed after routing through the canonical interpreter; only `Error(EPERM)` is permitted by the reviewed denial branch. No resource READ/ADDFD, public Native API, dependency, or production guard movement is part of this increment.

Retirement waits boundedly for the actual reactor. Socket-close failure and timeout remain recorded; all-Throwable rollback retains ownership and interruption. The handshake preserves the first observed failure. The tracee's merged cleanup outcome may differ from an earlier observer cause; a later coordinator fatal propagates on its originating thread. No identical-observer-cause guarantee is claimed. See the [Pause/resume handoff](tier-e-pause-handoff-20261114.md#b6-listener-handshake) for this observer asymmetry and the [Pause/resume handoff](tier-e-pause-handoff-20261114.md#b6-listener-handshake).

## Verification

The unfiltered Full003 gate completed in 40m54s: runner, Gradle and pipeline exits were zero; physical retirement passed. Independent XML audit counted 697 XML files, 4,784 tests, 45 skips and zero failures/errors across 19 outer test tasks. Gradle reported 248 tasks (107 executed, 141 up-to-date); 96 fresh task-owned child executions were collected. Unit instruction coverage was 86,718/105,749; combined coverage was 87,161/105,749.

Unit008 separately executed the owning enforcer lint, Detekt, SpotBugs, warning ratchet and unfiltered unit suite: 1,596 tests, two skips, zero failures, coverage 86,725/105,749. Enforcer/platform ABI checks executed. Static004 executed 73 tests, with separate static, ABI, and warning-ratchet checks. Up-to-date full-build lint/Detekt/SpotBugs analysis is backed by the source-matched Unit008 checks. The compiled accessor comparison found the same set of 18 `access$` signatures before and after; existing getters remain and are acknowledged.

The full gate used the 15-second override only for its owning FreshJvm test worker. Ordinary five-second behavior and global reliability remain unqualified. The capped 4 MiB log visibly contains at least three primary profiler-socket and three suppressed directory `Function not implemented` exceptions. Later counts and causes are unknown; issue 752 remains `UNKNOWN`. No harmless classification is made.

The frozen source inventory matched before/after and has SHA-256 `17ca226e277007ec5b80406327b6007476375dfff44c28f2db78982f3f594faf`; physical retirement and within-run index checks passed. Earlier raw-index discrepancy remains of unknown cause. Preserve prior failed attempts, the intermediate preimage incident, and Static003 `SOURCE_CHANGED` nonqualification; none is relabeled as a qualifying run. Full report SHA-256 is `3ebe84aaed08045999e0b5f37a757fb8c6ad37b48916daf8e75e755ab8574729`.

## Evidence limits and next gates

Tests compose a synthetic domain/mock native worker, actual owned sockets, and a regular `/dev/null` descriptor as an SCM transport surrogate. Daemon factory and direct notification handling were tested separately; the latter reached the actual denial handler at the factory boundary. Focused controls include fresh unsent-drain, unsent-worker-exit, after-SO_PEERCRED-drain, and full 149-byte Prelude-before-Drain cases; the prelude case uses test-only NativeEngine delegation. Failure assertions are exact and no production test hooks were added. The peer-pending case is not proof of no I/O; the post-write case sends actual bytes. Healthy worker-exit behavior was restored independently. These tests do not establish a real kernel `USER_NOTIF`, native bootstrap, production custody, helper support closure, or binding-to-raw-syscall route qualification.

The [Pause/resume handoff](tier-e-pause-handoff-20261114.md#b6-listener-handshake) (SHA-256 `e11464f2467e75680d785f8e8f19062e335891e818edd93f266cb18a42712d5d`) remains review-only and has not been released for implementation. It separates native lease publication from endpoint authority, preserves the D4 no-endpoint fixture, specifies that `NotInstalled` yields no observation, and that `Installed`→`Lost` closes new body admission. Issue 094 Needed 2 (exact task observation/non-effect decision) and Needed 3 (READ/FD effects, lifetime and any guard review) remain open. B7, B8, raw-syscall route, production custody/cold closure, broader Plan A, original 20+20, T4/vendor JFR, and existing qualification issues remain open or unqualified. This checkpoint accepts no NativeEngine or public API change and does not activate either production guard.


### Workspace-only receipt provenance

The following local ignored artifacts are retained as plain-text provenance; these paths are not portable links. Their bytes were present in this workspace at handoff preparation time.

- controller decision: `.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/b6-listener-handshake-controller-decision-20261114-001.md`; SHA-256 `5944a3b28cf4c0d24882df77fdec52494bbd2e79212f1182dcf9558066750ee4`.
- final verification report: `.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-manager-retirement-preparation/confined-handoff-step2-001/b6-listener-handshake-full-gate-proposal003/final-verification-20261114.md`; SHA-256 `3ebe84aaed08045999e0b5f37a757fb8c6ad37b48916daf8e75e755ab8574729`.
- preimage incident record: `.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/b6-handshake-preimage-incident-20261114-001.md`; SHA-256 `d6c5feb5e30164e66438ccd7f012d0a72a98d485c244224de38a08e289e35e89`.
- task-observation successor plan: `.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/b6-task-decision-preparation001/plan-observation-003.md`; SHA-256 `e11464f2467e75680d785f8e8f19062e335891e818edd93f266cb18a42712d5d`.
