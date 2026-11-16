# B5 registered worker admission with B6 refusal — bounded evidence, 2026-11-13

## Accepted scope

Root accepted this bounded increment as **ACCEPT_SCOPED_REGISTERED_WORKER_ADMISSION_WITH_B6_REFUSAL** after the independent full-run audit. This checkpoint does not close issue 842 or complete B5/D4.

The original runtime `OPEN` phase and exact domain identity gate a private admission that reserves only the worker slot, linearized with drain. The original worker then performs preparation and stores its exact `WorkerRegistrationReceipt` before invoking `Register`. After physical join, the retirement owner recovers the exact `WorkerOwner` if needed and retires that exact `WorkerOwner` before retirement of the installation, helper session, pins, or original resources. The worker has terminated and does not execute recovery or retirement; helper startup has already occurred before `Register`. No `TaskLease`/`Begin` is used. The synthetic path reaches actual Ready, then calls Register and is refused unconditionally at B6 before worker restrictions or body execution. `Pending`, `Refused`, and ambiguous outcomes remain retained and are never replayed; an indefinite `Pending` is not proof of liveness. See the [actor correction supplement](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/b5-registered-worker-admission-actor-correction-supplement-20261113-001.md), which leaves immutable decision001 unchanged.

The current Runtime→domain lock order is safe only because B6 is closed and Live cannot reach Ready. Moving the guard or enabling a Live/Ready path requires a separate lock-order audit. The privacy test passed against actual private worker admission state and has no synthetic registry getter. The six focused protocol cases remain synthetic native/protocol fixtures; they do not establish protected-native or actual helper-process qualification.

## Focused and full-gate evidence

Unit003 recorded 1,541 tests, two skips, zero failures/errors, and coverage of 83,184/101,398 (82.03712%). Its private-admission privacy test passed. Actual focused lint and SpotBugs reported zero issues; SpotBugs had zero errors, missing classes, or bugs. Static004 independently exercised 68 analysis/ABI cases, but its whole task did not pass because of lint; whitespace was fixed before successful unit003. Preserve that distinction.

Full001 manifest SHA-256: `9d17d6c274303d8a3269e5c68c67af73a65dc875092a5d7c97b44bb47356d0bf`. Gradle and runner exited 0. The run took 40m51s across 248 tasks (108 executed, 140 up-to-date); its 694 XML files record 4,729 invocations, 45 skips, zero failures, and zero errors, with 19 outer Test tasks. Enforcer unit coverage was 83,184/101,398 (82.03712%); combined coverage was 83,611/101,398 (82.45823%). Full Detekt and warning ratchet executed; SpotBugs was UP-TO-DATE, backed by actual clean unit003. Source comparison was empty, the index was unchanged, and physical container retirement passed.

The run recorded 65 fresh child executions, establishing freshness/identity only; no per-case or class-probe claim is made. Only the owning FreshJvm task used 15 seconds; default five seconds and full-run propagation remain `NOT_SAMPLED`.

## Failed and incomplete attempts retained literally

- RED proposal001 was an expected behavioral failure: `exact Ready must reach current-worker registration ==> expected: <1> but was: <0>`; Gradle exited 1 and the expected-failure pipeline/runner exited 0.
- GREEN proposal001 exited 95: `drain before live worker prestart prevents registration()` failed, with main and test KtLint failures. GREEN proposals002 and 003 exited 95 with KtLint failures. The later successful unit/full results do not relabel them.
- Unit proposal001 exited 95 with `:enforcer:spotbugsMain` failed and its report incomplete. Unit proposal002 exited 95 with `owner remains pending while its worker lives and registry is private()` failing; that privacy result is not the later actual passing private-admission test.
- Static proposals001/002 exited 95 with `detektWarningRatchet` incomplete/failed; proposal002 also had a KtLint failure. Static004's 68 analysis/ABI cases were checked, but the packet failed as a whole on main-source KtLint; whitespace was corrected before unit003.

## Remaining limits

This is not successful worker execution, actual protected-native qualification, or actual helper-process qualification; B6 remains closed. Retained `Pending` has no liveness guarantee. Begin/End/cancel ownership and broader qualification remain open. Preserve B5 worker-lease closure, B6/B7/B8, original cold 20+20, Plan A and broader workloads, and issues 338/752 as unresolved or unqualified. The run had three profiler socket `FileSystemException`s with message `Function not implemented` at lines 22109, 22224, and 22272, plus three suppressed parent-directory exceptions at lines 22130, 22245, and 22293. Issue 752 retirement therefore remains `Unknown`; these exceptions are not harmless. All 45 skipped invocations remain unqualified. Issue 842 remains open and its existing Needed items are unchanged.
