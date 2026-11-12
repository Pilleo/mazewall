---
title: "B4 D1: implement real prestarted confined read helper with staged ownership and bounded retirement"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules: ["enforcer", "platform"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/PreparedHelperReadPlan.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/PreparedResourceSet.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/ConfinedHelperProtocol.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/ConfinedHelperMachine.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/ConfinedHelperConfinement.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/ConfinedHelperJob.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/ConfinedHelperRelay.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SocketOperation.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SocketHelperPool.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SocketHelperSlot.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SocketHelperProcess.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SocketHelperMain.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SocketHelperLifetime.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorOwnedFd.kt"
  - "enforcer/src/main/kotlin/io/mazewall/ffi/networking/ConfinedHelperChannel.kt"
  - "enforcer/src/main/kotlin/io/mazewall/ffi/internal/HelperSecurityObservation.kt"
  - "enforcer/src/main/kotlin/io/mazewall/ffi/internal/OwnedProcFdReopen.kt"
  - "enforcer/src/main/kotlin/io/mazewall/ffi/networking/SupervisorHelperCredentials.kt"
  - "platform/src/main/kotlin/io/mazewall/ffi/networking/SupervisorSocketUtils.kt"
  - "platform/src/test/kotlin/io/mazewall/ffi/networking/SupervisorSocketUtilsTest.kt"
  - "platform/src/test/kotlin/io/mazewall/core/FileDescriptorTest.kt"
  - "platform/src/test/kotlin/io/mazewall/core/SandboxedPathContainmentTest.kt"
  - "platform/src/test/kotlin/io/mazewall/ffi/memory/NativeScopeTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/ArchitectureTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/ffi/internal/OwnedProcFdReopenProbe.kt"
  - "enforcer/src/test/kotlin/io/mazewall/ffi/internal/OwnedProcFdReopenProbeTest.kt"
  - "enforcer/src/integrationTest/kotlin/io/mazewall/landlock/OwnedProcFdReopenKernelTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/PreparedHelperReadPlanTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/ConfinedHelperProtocolTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/ConfinedHelperMachineTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/ConfinedHelperChannelTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/ConfinedHelperConfinementTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/ConfinedHelperRelayTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/ConfinedHelperClockTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SocketHelperPrewarmTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SocketHelperPoolTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SocketHelperProcessTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SocketHelperLifetimeTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SocketHelperCancellationTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SocketHelperRequestCancellationTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SocketHelperOwnerDeathTest.kt"
  - "enforcer/src/integrationTest/kotlin/io/mazewall/enforcer/supervisor/ConfinedHelperProcessIntegrationTest.kt"
  - "docs/internals/designs/enforcer/containment-design.md"
  - "docs/internals/designs/enforcer/live-stack-scoping.md"
  - "docs/internals/backlog/README.md"
target_symbols:
  - "SocketHelperPool"
open_questions: false
---

# 🟡 [Severity: HIGH]: B4 D1: implement real prestarted confined read helper with staged ownership and bounded retirement

**Context:**
Implement accepted B4 fixed2 D1 and shared contracts after authenticated G5 INCOMPLETE preservation; one real prestarted helper, exact resource generation, bounded staged SCM_RIGHTS ownership, common time domain, Landlock before seccomp, and retained owners through physical retirement. D2 managed-runtime integration remains separate.

**Needed:**
1. Freeze the exact dirty-checkout D1 scope from the accepted intake, then implement compiling behavioral RED/GREEN through the existing Process/Main/channel/reducer paths and real owned-child allowed/deny/retire controls.
2. Follow accepted fixed2 D1 and its shared resource, wire, staged relay, clock and
   ownership contracts. Promote the accepted proc-FD probe once; retarget every
   existing caller and preserve its validation and rollback. Native effects stay
   behind existing NativeEngine/FFI boundaries without new public API or dependency.
3. Exercise real root-by-root acknowledgment, quarantined-result custody through
   upstream receipt/intermediate close/helper DONE, exact common clock-domain
   observations, nonrenewed deadlines and actual helper Landlock-before-seccomp.
   Failed quiescence/reap retains the same owners and spent slot. Legacy behavior
   and defaults stay intact; private D1 does not install a Live worker.
4. Preserve every failure and fresh owning XML/child receipt, run focused checks,
   and obtain independent specification/security-quality review against saved
   dirty-checkout copies. Final whole-B4 build follows its last executable change;
   no passing full build or qualification is implied by focused D1 checks.
5. Update only the D1 implementation/evidence boundaries in the two named design
   documents. D2 daemon/control/ManagedRuntime integration, B5/B6 activation and
   exact deployment/no-writer/cold-runtime qualification remain separate work.

**Implementation basis:**
`.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b4-confined-helper-plan-fixed2.md`
(SHA256 `946e93b0797fb4feb20daaa05921b99768ffad7198f06eaa182251c5668d6008`),
accepted scoped correction review
`b4-confined-helper-plan-fix2-review.md`, plus `b4-d1-implementation-preparation.md`
and the exact scope intake. The old kernel-probe issue remains separate and open;
this issue does not overwrite its evidence or completion status.

**Release state (2026-11-05):** Scope declared after authenticated G5 terminal
preservation/interpretation. G5 remains INCOMPLETE; G4 remains FAILED. Execution
requires the separate saved-baseline handoff; no ordinary/diagnostic retry follows.

**Inline continuation (2026-11-05; incomplete):** The private resource manifest,
shared staged helper/relay machine, bounded socket transport, retained pool job and
socket-only Process/Main branch are implemented under focused qualification.
Successor evidence is retained in roadmap `b4-d1-implementation-002/`; the prior
worker is settled. Last connected host gate at this checkpoint passed55/55 cases
(confinement10/process2/channel13/architecture30), with no skips. This is not D1
acceptance, final static/build verification or real installed-confinement evidence.

Actual host children reached pre-seal inventory. The operator authorized recognition
of genuine read-only Linux random/urandom preopens by character-device identity;
this grants no device path rights and does not close JDK-owned descriptors. Mapping
validation now opens each exact `map_files` object and compares its descriptor
identity, following the device-domain finding in
[issue084957-941](issue-20261104-084957-941-validate-tier-e-mapped-fingerprints-across-filesys.md).
The current host denies map-object access; permission failure remains fail-closed.
The operator separately authorized the existing disposable rootful test image with
only CAP_CHECKPOINT_RESTORE for B4 qualification. Host security settings and the
ordinary launch path are preserved.

Retained rootful failures include harness VM-option ordering, new-output-directory
permissions, absent Gradle NO-SOURCE classpath entries, and then an actual helper
map-range canonicalization defect. The latter also exposed Session overwriting a
typed child rejection; compiling regressions reproduce both. Successful child
readiness/read/denial/physical retirement, the full staged cancellation/EOF matrix,
startup failed-reap ownership, static checks and independent review remain open.

Rootful007 subsequently passed the first actual-child positive: Ready after real
Landlock→seccomp/health, matching allowed FD identity and15 bytes/EOF, symlink ELOOP40,
and physical retirement of the same single child. The preceding rootful006 failure
identified ProcessHandle.isAlive's proc read after Landlock: its live-parent result
became false. Main now captures the verified parent's onExit observation before seal
and samples completion from the existing trusted JDK reaper. No proc read grant was
added. The connected host confinement/legacy owner-death gate passed13/13; the full
confined owner-death, failure, bounded-transport and retirement matrices remain open.

Rootful008 passed12/12 actual-child cases (zero skips/aborts): allowed read, exact
runtime-support digest/EOF, wrong root/generation/purpose, legacy3/4 after seal,
partial header/payload and withheld COMPLETE/RESULT_EXPECTED/RESULT_RECEIVED.
Each retired the same single acquired child. The longest stalled operation was
5010129769ns against the5250000000ns bound; withheld RESULT_RECEIVED closed its
quarantined result exactly once. Partial-send EAGAIN is a controlled native effect,
not real kernel-backpressure evidence. Startup-retention regressions also passed
57/57 connected host cases: original/fatal cause preserved and the same owner retained
through two failed reaps before explicit successful cleanup, without replacement.
Actual forbidden pinned-reopen/readable-preopen, child GC/owner-death, kernel
backpressure, the connected relay failure matrix and final verification remain open.

The connected relay matrix subsequently passed21/21, including cancellation/EOF at
staged handoffs, root/intermediate-close failures, no DONE after helper EOF, failed
parent DONE send and retained counted capacity during a claimed-owner timeout.
Main's outer channel cleanup was found to mask its primary failure; an isolated
real-Main regression failed on lost Permission13, then passed after using the
existing fatal-first cleanup fold. The connected host gate passed44/44, zero skips.
These host controls do not replace actual child-side kernel/GC/owner-death evidence.

**Authorized narrow scope extension (2026-11-05):** The operator selected “Allow
narrow fix” for `SupervisorOwnedFd.kt`: preserve native-close errno as typed data,
retain the existing IllegalStateException compatibility/ownership, and handle/test
that data in the confined paths. The pre-edit file is separately saved in the D1
successor evidence; this adds one path to the original42-path scope.

**Authorized static-test scope extension (2026-11-05):** The operator selected
“Allow narrow fixes” for the three pre-existing platform Detekt errors in
`FileDescriptorTest.kt`, `SandboxedPathContainmentTest.kt` and `NativeScopeTest.kt`.
All three matched the original baseline and were separately snapshotted before
editing. Use specific test exceptions and surface unsupported symlink creation;
retain cleanup assertions and run their host tests plus Detekt. This brings the
authorized scope to46 paths, including this issue.

The typed-close/native classification successor passed171/171 host cases with
zero skips/errors/failures, including both architecture rules and all connected
resource/lifecycle controls. Its first159-case run retained one test-FFM package
violation; raw native fixtures now reside in `io.mazewall.ffi.networking` within
their original scoped files, with caller imports/qualification selector updated.
All18 integration cases compile under the new test package.

Rootful010 passed all six selected native-child controls, zero skips/aborts/failures;
container6b73852fce7de1c8e49ad7d4759f564a04cbcd7129fae59bb9839144b3092525 exited0
with OOMKilled=false. Result-close now rejects exact RuntimeClosure/5, after actual
childFD45 disposition; parent quarantineFD167 closes exactly once without exposure.
The acquired owner-death control waits for Ready/allowed bytes, verifies the same
descendant's start identity, kills its owned parent and observes helperPID293 exit.
Actual send-buffer EAGAIN remained bounded at5006150162ns. These six fixtures add
14624867bytes to the retained packet. A newly identified pre-seal health-read
errno-classification gap is under RED verification; final checks/review remain open.

Independent review then identified lost relay local deadline, swallowed confined-worker
fatal propagation, and untyped transfer-duplication errno. All three are controller-graded
Important and enter the targeted RED/GREEN pass before final-input qualification.
Idle EOF's additional null-cap suppressed diagnostic is a deferred Minor; original
EOF, terminal admission and physical retirement remain intact. The retained review
and every declined-review disposition are in the active D1 evidence ledger.

The review pass retained compiling REDs for all ten new cases, then passed the204-case
connected host selection with zero skips/errors/failures. Relay keeps the effective
local cap without altering offered wire deadlines; fatal-first quiescence publication
precedes retirement scheduling and uncaught propagation; partial direct duplication
preserves exact Permission1/13 and RuntimeClosure5. Failed reap retains real original
owners and forbids replacement. Final full enforcer host/static and frozen-input
18-case real-child qualification are the remaining D1 evidence gates; this issue stays
open pending the combined whole-B4 gate.

Those D1 gates now passed: `review-final-host-static-001` exit0, full enforcer suite
1192 passed/2 unchanged Landlock platform-assumption skips, no failures; all enforcer
lint/Detekt source sets, ABI and SpotBugs pass. D1's204 selected cases had zero skips.
Final-input rootful011 ran18/18 successful cases with no skips/aborts/failures, all
acquired helpers physically retired. Actual backpressure5044711829ns remained below
5250000000ns; native result-close rejected RuntimeClosure/5 with one parent quarantine
close; acquired owner-death helperPID825 exited. Container
5259b157c621a3fd7d681073804138a86ffeb44e304e1d8bc79fb03d10242519 exited0/OOMKilled=false;
source and compiled-input postflight hashes match. This qualifies only the recorded
authorized rootful envelope, not public/live-stack activation or broader native gates.

---
<!-- id: issue-20261105-074955-139-b4-d1-implement-real-prestarted-confined-read-help  file: issue-20261105-074955-139-b4-d1-implement-real-prestarted-confined-read-help.md -->
