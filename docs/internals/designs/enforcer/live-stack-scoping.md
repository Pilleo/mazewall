# Proposed live stack scoping contract

**PROPOSED / GUARDED CANDIDATE EVIDENCE / NOT QUALIFIED.** This is the canonical B1 contract frozen for implementation review, not an available API or support qualification. Independent T2/T3 reviews passed; T4 qualification and later implementation/integration remain required, and B3/B4 can find the mechanism unsupported. The original B1 publication executed no tests; the bounded implementation evidence below does not qualify the proposal. It does not unblock or qualify A4. Earlier checkpoint descriptions below are historical; the G1 section records the current prerequisite correction.

### Latest bounded main-producer checkpoint (2026-11-11)

The [controller decision](../../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/main-producer-controller-decision-20261111-001.md) and [full report](../../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/main-producer-full-report001.md) bind eligible source `c16a9cfe…`. Under controlled test custody and explicit binding setup, the real main-JAR/Only-DSO producer reached counted Ready after real enrollment and seal. Its existing member call claimed resources, prepared Landlock and committed the domain before the B6 guard refused. Four original domain-local release steps completed; the acquisition status remained `Unknown`, not Complete. The full build passed in 30m08s with 4,646 tests, no failures/errors and 45 skips; 14 focused tests and scoped static checks passed. Three profiler shutdown `ENOSYS` exceptions remain under issue 752. This does not qualify D4, all B5, B6, production custody or activation. A subsequent three-file typed-boundary candidate is active and unverified.

### Historical protected startup and verifier slice (2026-11-11)

This update records a bounded implementation slice; it does not qualify the live-stack contract below. The native agent starts in `DiagnosticOnly`. It reads the protected binding only after an explicit live-mode selection. The fixed files are `/etc/mazewall/live/startup-binding.v1` and `/etc/mazewall/live/distribution.v1`; directory traversal refuses symlinks, and final files are opened no-follow/nonblocking and checked as stable regular files. A clean `ENOENT` while resolving the fixed binding path yields `Absent`. A present binding with a missing manifest, bad digest, malformed packet, unsupported launch, or any other read/access/stat/close error is invalid and fails `Agent_OnLoad` before native-bind event setup. A valid protected packet is accepted only with `live-stack=only`; a valid packet with `shared` is refused. Absence leaves the prior diagnostic or explicit-mode behavior in place and creates no protected snapshot. See [startup capture](../../../../profiler-native/src/live_startup_capture.cpp), [agent mode selection](../../../../profiler-native/src/agent.cpp), and [packet parser](../../../../profiler-native/src/live_distribution_manifest.cpp).

The protected launch check is a narrow structural grammar: the captured Java executable, nine ordered JVM arguments, literal `-cp`, seven ordered JAR paths, one syntactically valid ASCII Java main name, and bounded application arguments. It does not hardcode the fixture or any application main class. Extra JVM options/agents, alternate classpath forms, wildcard paths, and loader overrides are refused. The package-private [`ProductionDeploymentVerifier`](../../../../platform/src/main/java/io/mazewall/ffi/internal/ProductionDeploymentVerifier.java) observes the actual runtime properties and JVM arguments, ordered classpath files and digests, duplicate effective classes, and the twelve expected system-loader/unnamed-module/CodeSource witnesses. Native code independently checks captured process arguments, artifact hashes, mapped images/build IDs, and actual binding/dependency targets before a checked preparation can reach `Reserve`. A matching manifest or argv alone does not establish trusted deployment custody. The complete launch shape and its boundary are recorded in the [protected launch grammar](../../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/verifier-launch-grammar-20261111-001.md).

[`LiveStackBootstrap`](../../../../platform/src/main/java/io/mazewall/ffi/internal/LiveStackBootstrap.java) remains a package-private helper with no declared fields and five private native methods. Registration and verification do not themselves grant live readiness. The ordinary engine still selects [`UnsupportedNativeStackContexts`](../../../../platform/src/main/kotlin/io/mazewall/platform/native/NativeStackContexts.kt); the agent default remains diagnostic-only, and legacy/default behavior is preserved. This evidence does not activate the production helper/filter/live capability or establish `copy`/capture readiness.

A controlled read-only namespace run used the actual packaged helper/native path with test-prepared binding and manifest files mounted at the fixed paths. [`protected-positive-run003`](../../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/protected-positive-run003/child.log) reached `PROTECTED_ORIGINAL_OWNER_RETIRED` after the prepared reservation was retired through the same original owner. That run used source snapshot `71406c476c6b8a829c8c153b22bca3b648b19201e1a867f0c5c65a20f9b2ccb1` and agent DSO `0f549c2b43989e28c0717c87f00662c38dadfa96c7a25c67fa66f606da0c672e`; the test-owned packet is retained [here](../../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/protected-positive-packet003/input.json). Current source was subsequently restored to default refusal, snapshot `854580334b048699660ae53a20bc11930f9db0128e4b363bcd5bef440422e52d`; the fresh focused checks passed platform **50/50**, enforcer **14/14**, and native **16/16**, but the protected positive has not been repeated on those bytes ([platform](../../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-manager-retirement-preparation/confined-handoff-step2-001/verifier-focused-platform003/build.log), [enforcer](../../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-manager-retirement-preparation/confined-handoff-step2-001/verifier-focused-enforcer003/build.log), [native CTest](../../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/verifier-native-regressions002/ctest.xml)). The native regression receipt includes actual target-mismatch controls through a test-only DSO, not production deployment qualification. `protected-no-setup-run003` reports “replacement during checking or missing actual binding”; it does not isolate a missing-binding result ([receipt](../../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/protected-no-setup-run003/child.log)).

Actual operator custody, unprepared readiness, `captureCopy`, production helper/filter/live activation, and the B5 counted lifecycle remain unqualified. The controlled run proves only this bounded Reserve-to-same-owner-Retire path for its test-owned read-only namespace and historical source/DSO pair.

### Current private fixture evidence (2026-11-10)

A synthetic `CLOSED_V1` / private `mazewall-t4/2` fixture exercised the checked original owner, held native lease and worker path. Its selected worker run passed six cases, including 15 negative scenarios and pipe/reap receipts for 16 distinct children. Descendant observations are snapshots, not a complete lifetime proof. The three default JNI/context CTests are retained from the preceding native build, not a new run. Changed-PID and changed-class-serial Java-owner scenarios remain unexecuted. This controlled fixture and its derivative helper do not supply production artifact authority or custody; Task 2 counted preparation, public/native Live activation and broader qualification remain held. Earlier checkpoint descriptions below retain their historical scope. The private test is registered only with `-PmazewallManagedT4Fixture=true`; it still requires the reviewed expectation manifest, agent and VM-argument inputs.

### B2.1 implementation evidence (2026-11-04)

The native v1 records/three exports, inert fixed-lifetime cells, exact-ID lifecycle,
enrollment-ownership handoff and translated-token publication/copy core now exist in
`profiler-native/include/live_stack_context_abi.h`,
`profiler-native/include/live_stack_context.hpp` and
`profiler-native/src/live_stack_context.cpp`. The separately compiled
`profiler-native/tests/live_stack_context_test.cpp` uses explicitly synthetic
enrollment owners and test-only deterministic pause/fault controls. It establishes
core behavior, not JNI/Class identity or actual agent startup/proxy authority.

Final focused CTest: **13/13**; normal native suite: **62/62**, zero skips/failures.
The focused concurrency case also passed separately under native TSan (**1/1**)
and ASan/UBSan with lifetime/owner cleanup checks (**1/1**). Behavioral RED and
GREEN logs, final source/artifact hashes and the detailed handoff are retained as
`b2-core-*` artifacts in the roadmap execution workspace. These are host-native
core/regression results, not target-runtime, route or enforcement qualification.

The ordinary agent does not initialize this core: production exports stay
Unsupported, and default diagnostic behavior/protocols are unchanged. T1 startup
and helper setup evidence is recorded below; transactional JNI/Class enrollment,
worker integration, acquisition, guarded proxies, FFM/bridge compatibility and the
genuine live-only/shared external-retransformation fixture remain pending.
Candidate JDK originals have source-visible
error-path Java exception construction; only the guarded, audited envelope below
can admit them, and that guard and envelope are not yet implemented or qualified.
Exact installed-artifact/route provenance and B4 resource gates remain open.

### T1 / B2.2a startup/helper evidence (2026-11-04)

Explicit `live-stack=only` / `live-stack=shared` startup now selects the existing
single JVMTI environment, requests and verifies the required capabilities before
callbacks/events, and initializes/pins/registers the actual package-private Java
helper at VMInit. Only mode suppresses diagnostic owners/capture/publication;
omitted mode remains diagnostic-only. MethodEntry is not globally enabled.
ThreadEnd/VMDeath/Unload call the existing inert lifecycle invalidation functions;
helper pins remain process-lifetime owned. No core initialize, deployment agreement,
enrollment, worker registration or Available is supplied. Probe returns false;
all preparation JNI bodies explicitly throw Unsupported pending T2/T3.

Compiled options/startup REDs and the genuine missing-helper JVM RED were retained.
Final focused host CTest passed **8/8**; the normal native suite passed **70/70**,
zero skips/failures, including existing diagnostic/MZOB/ABI/export regressions.
Actual platform Java/Kotlin compilation, SpotBugs and owning KtLint checks passed.
The helper/workload use JVM 22 class files. Host startup subprocesses establish
only/shared setup plus missing-helper, wrong-loader, registration-signature and
annotated-probe refusal before workload admission; they do not qualify a target,
deployment, route, guard or containment boundary. Evidence and self-review are
retained in roadmap `b2-startup-001/` and `b2-startup-report.md`; independent task
review and the controller's serial-integration completion gate remain pending.
The frozen requirement/ABI/lifecycle text below is unchanged by this status update.

### T2 / B2.2b checked setup/enrollment evidence (2026-11-04)

Explicit live startup now has checked deployment agreement, native process
identity, actual Class/Executable enrollment and private Seal prerequisite checks.
Closed deployment states consume initialization once; move-only pending owners
transfer actual strong JNI references only after successful core commit. Bounded
scalar Seal associations do not expose owner pointers or participate in copy.
Observed selected rebinding is ordered through the actual original-address store;
the initialized helper cache is published atomically. Worker preparation and probe
remain refusing: no guard, live publication, Available or application permission.

Current hardened-source target run014 passes focused CTest **23/23** and unfiltered
native CTest **93/93**, zero failures/skips, on the pinned JDK/image. Tests include
real only/shared info→Reserve→enroll→Seal receipts, distinct actual loaders,
constructors/bootstrap identity, exact OOME rollback, GC retention, retryable and
unattached cleanup, retained reservations, actual rebind/replacement races and
incompatible launch/target refusals. Fresh host startup/refusal checks pass **9/9**;
the platform compile/static command succeeds using unchanged up-to-date inputs.

Actual mapped-object proof requires the specifically released UID0/only
CAP_CHECKPOINT_RESTORE test envelope here. Earlier non-root EPERM and Btrfs
identity failures remain retained; no pathname/hash fallback was added. Storage
exhaustion and the fresh-container temp-parent prerequisite were diagnosed before
later runs; earlier-source passes are not substituted for run014. Evidence,
hashes, all failures and self-review are in roadmap `b2-enrollment-002/`,
`b2-enrollment-target-002/` and appended `b2-enrollment-report.md`.
Independent T2 review, T3/T4 guard/worker/retransformation and later integration/
qualification gates remain pending. Ordinary unprivileged support is not proven.
The frozen requirements from §1 onward are unchanged.

The proposal restricts trusted synchronous JVM code processing untrusted inputs; it is not an ACE or hostile-plugin boundary. Native compromise can forge stack state, legitimate methods can receive attacker-controlled arguments, and threads share memory and file descriptors, including already-open or reused descriptors. Executable-memory restrictions do not make stacks unforgeable. Use a process/portal boundary for hostile code. Existing Tier E diagnostic metadata remains forgeable and policy-ineligible.

An available snapshot is not permission: static syscall action **AND** broker resource permission **AND** current task/generation **AND** the exact ordered corridor **AND** optional callback narrowing are all required. Runtime-support pinned reads are separately authorized. B4 must independently establish constrained resource acquisition, exact-object reopen and FD injection; unknown worker-only security differences refuse preparation. Do not CONTINUE the original pointer-bearing open. Revalidate each fresh notification/request identity before effects. Preserve the mandatory process-wide Tier 1 baseline, JVM coordination floor, Landlock-before-seccomp and the prohibition on installing seccomp from virtual threads. Cancellation revokes authority for the whole installation, not Java execution itself or already committed effects.

The existing `Thread.getStackTrace` path remains legacy/experimental; this proposed live path never silently falls back to it. Linux v6.12 source pins `__secure_computing()` before `trace_sys_enter()`; requalify ordering for each actual architecture/kernel path. A blocked syscall cannot wait for its own ring event. Diagnostic streams, ring, dictionary and markers have no live authorization authority.

### T3 / B2.3a per-worker guard and acquisition evidence (2026-11-04)

The hidden atomics-only `probe_eligible()` prerequisite, actual-thread preparation,
per-worker MethodEntry enablement, exhaustive irreversible activation guard and
exact native-helper witness now exist. The two selected proxies preserve pending
throwables, original call counts and errno; complete live translation uses the
retained enrollment map, not diagnostic identities. One local raw acquisition
feeds independently admitted live/diagnostic consumers. Shared preparation caches
only the existing diagnostic platform execution scalar before activation, avoiding
diagnostic Java identity queries inside an acknowledged guard; native core remains
the only task/installation/publication authority.

Final measured candidate run007 passes focused CTest **57/57** and unfiltered native
CTest **106/106**, zero failures/skips, in the pinned UID0/only-bit40 envelope. Actual
only/shared worker checks cover virtual refusal, event-enable failure, real helper
acknowledgment, single acquisition, Available before the real original, exact JNI
throwable identity in a separately compiled pre-body resource-fault entry, fresh
witnesses and physical retirement. Explicit component fakes cover the broader
guard/capture outcome matrix for both proxies; they do not establish JVM readiness. Host-safe checks
pass **32/32**; the unchanged final core passes focused TSan and ASan/UBSan **1/1**
each. Platform compilation/static tasks succeed **UP-TO-DATE**, not recompiled.
Evidence, failures and limits are in roadmap `b2-guard-report.md`, `b2-guard-001/`
and `b2-guard-target-001/`. Independent review, T4 final-chain/error-path/external
retransformation and B3/B4/B6/full integration remain pending. No ordinary support,
permission, route qualification or complete native/signal closure is claimed.
Requirements from §1 onward remain byte-identical.

### T4 G1 native-signature prerequisite correction (2026-11-04)

VMInit now invokes the exact registered cached native probe through JNI before
helper-cache publication, only with an inactive, unprepared, empty guard. It
requires false and no pending exception; incompatible state, true or an exception
refuses setup without resetting authority or replacing the throwable. This finishes
the known lazy native-signature path outside activation; it does not qualify native
closure by warming. Each later activation still needs its own fresh exact witness.

Compiled behavioral RED detected the missing invocation. The final host build
passes focused CTest **41/41** and all selected host-safe CTests **90/90**, zero
failures/skips. Actual Only/Shared startup and separately compiled true/throw
startup failures are covered. An embedded host JVM directly exercises the real
initializer and registered JNI entry, observing pre-publication invocation,
inactive/no-witness state, false/true/new-throw failures and exact pending throwable
identity. Existing guard/capture matrices remain explicitly component evidence,
not target worker qualification. No platform-module recompilation is claimed.

Independent T2 and T3 specification/code-security reviews passed, each with one
nonblocking minor finding retained for integrated T4 fixtures. This correction's
independent review is controller-owned. Report and preserved receipts are roadmap
`b2-probe-preparation-report.md` and `b2-probe-preparation-001/`. G2/G3/G4, final
pinned-target/native-chain qualification, external retransformation, B3/B4/B6 and
the full integration build remain pending; both broad issues stay open. Requirements
from §1 onward remain byte-identical. No hidden-upcall bypass is demonstrated.

### T5 / B3a captured capability and immutable types (2026-11-04)

The internal optional provider now resolves only against the captured engine;
`NativeEngine` source and interface bytecode obligations remain unchanged.
Validated incarnation/semantic IDs and counts, immutable enrollment/owner/task/frame
copies and closed read/setup/completion outcomes exist. Seal projects a fresh owner
without removing the staging owner's cleanup association. Actual Class/Executable
identities remain separate from display metadata. The real capability explicitly
returns Unsupported for every operation, including cleanup: this is not a native
adapter, Available evidence, permission or target qualification.

Compiled captured-provider RED is retained. Final focused host tests pass 12/12;
the unfiltered platform host suite passes 443/443, zero failures/skips. Platform
Detekt, main/test KtLint, main SpotBugs, both owning ABI checks and focused enforcer
architecture tests (37/37 across matching classes) pass. The requested platform
`check` was dry-run only: it schedules prohibited kernel integration suites; host
`unitCheck` plus explicit static checks were used, pending controller clarification.
Roadmap `b3-types-001/` and `b3-types-report.md` retain receipts and limitations.
T6 transport/legacy-implementer execution, T4 qualification and full integration
remain open. Requirements from §1 onward remain byte-identical.

### T6 / B3b host-only adapter evidence (2026-11-04)

The explicit `RealNativeStackContexts.bootstrap` factory now checks the existing JNI
deployment agreement before looking up the three v1 exports from that exact agent
path. It captures one supporting runtime and retains a private automatic-arena
binding; call buffers remain confined and copied before close. Typed owners retain
the explicit capability. Ordinary real snapshots and the default mock remain
Unsupported. No global provider setter, second lifecycle authority or readiness
bypass is introduced.

The host adapter validates operation-specific status pairs, every receipt/snapshot
join and unused field, actual enrollment tuples and complete known-token frames.
Structural corruption latches fresh authority off, retaining a known cleanup owner
and its invalidation result without inventing IDs from malformed output. Cleanup
continues through the original owners; Busy is Pending, never physical retirement.
The existing C oracle now includes the actual native ABI header and checks all five
record layouts, fields, alignments and frame stride.

Compiled behavioral RED/GREEN and fresh host evidence are retained in roadmap
`b3-adapter-host-001/`, with scope, failures and limitations in
`b3-adapter-host-report.md`. The adapter has 24 host tests; focused capability/legacy
checks pass 37/37 and the platform host suite passes 468/468, zero failures/skips.
Host unit/coverage, owning static/lint and ABI commands pass. The subsequent
five-path host-quality refactor replaces raw offsets with cached named-layout
metadata and uses named wire values, exact cached operation-pair tables and
cohesive snapshot/frame/receipt validators. All 203 adapter warnings are removed
without new suppressions, policy changes or test edits. Fresh focused 37/37,
platform host 468/468 and architecture 37/37 tests pass, with zero failures/skips.
The quality-stage root warning ratchet initially found the T6 SyscallInvoker
function-count change (32→34). The separately reviewed private errno-processor
relocation subsequently resolved it; the current host root gate passes with zero
added warnings in all six modules. That invoker source remains frozen here.
Independent host review found M1: a malformed cleanup could replay the first
corruption's cause. The shared poison result now preserves each caller's own
failure while keeping the first latch/owner and single automatic invalidation.
Compiled RED/GREEN covers distinct malformed snapshot/invalidation/retirement
causes and same-owner Pending→Complete cleanup. Fresh focused 38/38 and platform
host 469/469 tests pass; independent scoped re-review remains pending.
Original reports/evidence remain immutable. Quality-stage evidence is in roadmap
`b3-adapter-host-quality-001/`; this fix uses `b3-adapter-host-fix1-001/` and
`b3-adapter-host-fix1-report.md`.
Transport fixtures are not native deployment, task-state or capture positives. The strict private checker
is unchanged; real startup/capture, T4 qualification, external retransformation,
final distribution measurement and full integration/build gates remain pending.
This is not T6/B3 completion or a qualified profile. Requirements from §1 onward
remain byte-identical.

### B4 D1 private helper evidence (2026-11-05)

The existing helper owners now implement a private prestarted confined-read mode:
canonical policy/resource/ancestry binding, one-root-at-a-time SCM adoption, shared
direct/relay result quarantine, bounded common-domain monotonic deadlines and
same-owner retryable physical retirement. The actual child platform operation thread
installs Landlock before seccomp and health/read readiness. Exact reviewed runtime
files and the no-writer lifetime premise remain distinct from application authority.
See [containment §11](containment-design.md#private-confined-read-helper-b4-d1).

Roadmap evidence `b4-d1-implementation-002/` retains the failed attempts and subsequent
171/171 host gate. Isolated rootful009 passed 17/17 real-child controls; rootful010
passed six focused controls, including the new acquired-owner death case and exact
`RuntimeClosure/5` after real FD disposition. Forbidden pinned readable reopen returned
EACCES; actual socket backpressure expired within the five-second plus 250 ms bound.
All selected cases retired their acquired children, with zero skips/aborts. These
controls used the recorded disposable Temurin 25.0.5 container and explicitly
authorized `CAP_CHECKPOINT_RESTORE` for acquired map-files inspection; they do not
qualify unprivileged hosts whose map-files access is denied.

D1's [implementation issue](../../backlog/issue-20261105-074955-139-b4-d1-implement-real-prestarted-confined-read-help.md)
remains open through the combined whole-B4 gate.
D2 acceptance, B5/B6 listener/reply joins and T4 final-byte
qualification remain pending. This private component grants no live-stack or
notification authority and does not qualify any native profile or public route.

The independent D1 review's deadline, fatal-propagation and structured-errno findings
were reproduced by ten connected cases and corrected. The resulting host selection
passed 204/204 tests, including stalled relay effects/stage waits, cancellation-before-
fatal publication, callback-captured fatal errors, and partial duplication EPERM/EACCES.
The final full enforcer host/static gate passed: 1,192 tests passed and two unchanged
Landlock platform-assumption tests skipped; D1's selected cases had no skips.
Final-input rootful011 passed all18 real-child cases with zero skips/aborts/failures
and physical retirement of each acquired helper. Actual backpressure expired at
5,044,711,829 ns, below the5,250,000,000 ns bound. Scoped source and compiled-classpath
postflight hashes matched. Idle EOF's additional suppressed
null-cap diagnostic remains a recorded minor; the original EOF and retirement survive.

### B4 D2 managed readiness evidence (2026-11-05)

The private runtime path now connects pre-baseline daemon/helper acquisition to
post-baseline staged setup, actual helper Ready, worker ancestry checks and retained
domain retirement. Repeated actual `ManagedRuntime.drain` retries the original
session/lifetime through the same manager; failures retain originals and remain
visible after successful cleanup. See [the D2 lifecycle contract](containment-design.md#private-managed-helper-readiness-and-retirement-b4-d2).

Successor evidence `b4-d2-implementation-001/` records 228/228 connected host cases
and rootful006's eight successful isolated cases, zero skips/aborts/failures. Actual
root transfer and result quarantine enforce the shared receipt/close/DONE order;
negative readiness cases prevent planned worker installation, and every acquired
helper physically exits. Native receipt/permission and time-observation faults are
controlled effects; helper loss and the extra worker Landlock layer are real.
The recorded rootful capability/no-writer envelope remains required. NNP 0 startup,
arbitrary external LSM equivalence and later namespace mutation are not qualified.

The [D2 issue](../../backlog/issue-20261105-175119-373-b4-d2-integrate-pre-install-confined-helper-readin.md)
remains open for independent review, final-input qualification and the combined
whole-B4 gate. B5/B6 still must bind the exact Ready generation to listener/reply
authority; test-only driving of the idle parent channel is not a public read API.

Independent review's three Important lifecycle findings now have compiling
RED/GREEN regressions. The expanded enforcer gate passed268/268 and the warning
ratchet reports zero additions. Post-correction rootful007 passed8/8 D2 cases and
the D2 successor's helper-regression-rootful001 passed18/18 D1 cases, without skips,
aborts or failures; exact input postflight checks matched. The corrected combined
build passed its warning gate but failed18/232 enforcer integration cases (two
skips); no full-build acceptance is claimed. Published-owner close retry is tested separately from
pre-publication acquisition rollback; the latter remains explicitly unqualified
under [the separate scoped follow-up](../../backlog/issue-20261106-013649-338-retain-failed-prepared-acquisition-rollback-owners.md).

Full012 remains the last failed full gate; mapping014 passed 27/27 focused controls
and the shared-harness correction is separately verified. After an earlier failed
running-daemon policy equivalence audit, the operator selected official
`moby/profiles/seccomp` v0.2.4 commit `245180c51918481c0525424b3ee025d2b435d46c`
as a new declared test baseline, not as the daemon's proven built-in policy. A
reviewed candidate adds only `ALLOW pidfd_getfd` with argument 2 equal to zero;
paired probes and the owned socket test passed under it (1/1, no skips, aborts or
failures; container exited 0/PID 0/no OOM). No library/source permission bypass or
added capability was used. See [the socket issue](../../backlog/issue-20261106-081057-933-investigate-owned-worker-scoped-socket-denial-in-n.md)
for exact baseline hash and probe evidence. Full015 has not launched; B4 acceptance
remains pending, with no default-built-in or default-parallel pass claimed.

## 1. Evidence and first envelope

This contract builds on the [roadmap](../../../superpowers/plans/2026-11-03-tier-e-improvement-roadmap.md), [owned-stack plan](../../../superpowers/plans/2026-11-03-tier-e-owned-stack-scoping.md), containment §11, notification-liveness contract, existing native agent and owned-worker lifecycle. Important current facts:

- `InvocationScope::capture_stack` already requests 129 frames and retains at most 128; frame 129 is an overflow sentinel. It currently feeds diagnostic interning and weak-class guards, not live authority.
- Native bindings are intercepted through JVMTI `NativeMethodBind`. `on_class_prepare` has a test-only real `jclass` → `NewGlobalRef`/method lookup seam. **There is no existing `RegisterNatives` enrollment bridge.** Existing signature classification is not actual-Class authorization.
- `SymbolizationGeneration::resynchronize` can recover diagnostic identity. It must not recover live enrollment. Existing diagnostic invocation counters/IDs are not the new live IDs.
- `NativeEngine`, `LinuxNative` and `RealNativeEngine` are Kotlin `internal`; `LinuxNative.snapshot()` captures one engine identity. Cross-module internal access uses packaged-JAR friend grants; public protocol exceptions use `@MazewallInternal`. Platform enables explicit API and Kotlin ABI validation, targets JVM 22 with toolchain 25, and the inspected JVM convention does not explicitly choose a JVM-default mode.

**Proposed first target:** startup `-agentpath` on Linux x86_64, HotSpot Temurin **25.0.5+8-LTS**, trusted classpath deployment with platform JAR on the system/application loader, completed instrumentation before prepare, owned platform workers only, synchronous guarded NIO absolute regular-file reads. Named-module/custom-loader installation of the *bridge itself*, late attach, OpenJ9/native-image, other JDK builds, aarch64, virtual/async ownership and arbitrary worker-only LSM/namespace differences are unsupported initially. Application corridor classes may have distinct actual loaders/modules; enrollment records them, not names.

Prepared image receipt: `rootful-image-readiness.json`, amd64 image `sha256:8047b78d7af43aa5fa9dcfd61d5bbdcf47044e0a154221b2e8a3ba28c4fa02e1`, Temurin 25.0.5+8. This proves Java startup only. Host JDK 25.0.4.1 and kernel `7.2.11-x64v3-xanmod1` are separate, unqualified targets. No historical receipt qualifies either current runtime.

| Binding | Manifest signature observed in source | Live v1 use |
|---|---|---|
| `sun.nio.fs.UnixNativeDispatcher.open0` | `(JII)I`, static JNI `(JNIEnv*,jclass,jlong,jint,jint)->jint` | Candidate boundary, wire binding 1 |
| `sun.nio.fs.UnixNativeDispatcher.openat0` | `(IJII)I`, static JNI `(JNIEnv*,jclass,jint,jlong,jint,jint)->jint` | Candidate boundary, wire binding 2 |
| Other ten manifest bindings | Existing diagnostic proxies | No live authorization |

Candidate kernel routes are x86_64 `open` (2) and `openat` (257), native audit arch `0xc000003e`; no x32 ABI. **No binding→syscall route is presently qualified.** Preparation requires a frozen source/artifact-bound route receipt and actual proxy-binding readiness for every selected boundary; an unused/unobserved route is denied, not guessed from libc. Tracee `openat2` (437) remains denied. Broker-internal `openat2` is a distinct B4 operation.

Freeze requested open flags to `O_RDONLY` (zero), optional `O_CLOEXEC` (`0x80000`) and optional `O_LARGEFILE` (`0x8000` on this target); require `(flags & ~0x88000)==0` and mode zero. Reject every other bit, including nonzero access mode, creation, truncation, append, directory, no-follow, nonblocking, path-only and tmpfile. Absolute path only; for `openat`, additionally require `AT_FDCWD=-100` in v1. Symlink/magic-link/mount-crossing and nonregular targets are B4 denials, not properties a stack can establish. If the real JDK needs additional flag/route semantics, revise the frozen contract with evidence before admitting it.

Startup requires USER_NOTIF and safe atomic ADDFD+SEND, Landlock, constrained broker openat2, same established PID/mount/user namespace and credential envelope, and the Tier 1 baseline. All readiness checks precede irreversible worker installation. Source-pinned seccomp-before-`trace_sys_enter` ordering is a qualification prerequisite, not a ring handoff. No performance threshold is invented here.

## 2. Actual-Class enrollment: concrete JNI seam, no handle-shaped FFM arguments

Add one package-private final **Java** class `io.mazewall.ffi.internal.LiveStackBootstrap` in platform (stable JNI names, not Kotlin-mangled methods). In explicit live startup mode, `VMInit` uses JNI `FindClass` to load this helper from the system classpath, verifies its defining loader with `IsSameObject` against the system loader and its expected unnamed module, pins that exact helper Class, and calls `RegisterNatives` on it. Name lookup only locates the trusted bootstrap helper; it never enrolls application authority. Missing/wrong-loader helper or registration failure leaves live readiness unsupported. No `System.load` masquerading as agent startup, and no raw `jobject`, `JNIEnv*` or `jmethodID` passed through FFM. Add a narrowly callable internal Kotlin adapter for the package-private Java helper.

The one enrollment native method is conceptually:

```java
private static native long[] enroll0(long reservedInstallationId,
                                     Class<?>[] owners,
                                     java.lang.reflect.Executable[] methods);
```

Its Java wrapper is invoked only by trusted preparation, outside task/notification execution. The installation ID must identify a native-minted, still-staging reservation owned by that preparation; it is not a Class identity. This explicit association avoids a global "most recently prepared" enrollment race. It returns `[codeGeneration, ownerCount, methodCount, classToken[ownerCount], methodToken[methodCount]]` in input order, or throws a setup exception with preserved cause; counts and total array length are validated before conversion to domain types. Tokens are minted by native code, never supplied as purported Class identities. Enrollment remains staging/unusable until explicitly sealed by the lifecycle ABI. One frozen enrollment per installation; no append after seal.

Native steps, transactionally:

1. Validate nonnull bounded arrays, no duplicate owner objects (`IsSameObject`), prepared classes and reflected Method/Constructor objects. Use JNI `FromReflectedMethod`; JVMTI `GetMethodDeclaringClass` must be `IsSameObject` to one supplied owner. Reject inherited-owner mismatch, obsolete methods and duplicate methods. Method name/descriptor/modifiers are copied metadata, not the authorization key. No access override or `setAccessible` is needed.
2. Pin each actual owner with `NewGlobalRef`; obtain and pin the actual defining loader (bootstrap is a distinguished null-loader token) and actual module (`GetModule`). Intern loader/module identities by object identity, never `equals`, tag reuse or display names. JNI/JVMTI refs and raw method IDs remain native-private.
3. Mint nonzero process-lifetime monotonic class/loader/module/method tokens. A method-token entry contains its pinned actual declaring Class, loader/module tokens, descriptor, code generation and native-private method ID. Retain the corresponding actual objects and immutable metadata projection on the JVM side for corridor preparation and optional diagnostic `StackTraceElement` projection. Never use projection text to recover authority.
4. A capture translates `jvmtiFrameInfo.method` through the sealed generation's native-private map. Any unknown method anywhere in the full retained capture yields `Incomplete(UnknownMethod)`; all required JDK corridor and executor/tail methods must therefore be enrolled. The approved ordered corridor runs from boundary through declared operation entry; enrolled tail frames confer no authority. Bounded complete capture is still required.
5. Enrollment failure releases only newly acquired refs; partially published enrollment is impossible. Pending JNI/application exceptions remain pending, never silently cleared to continue admission. A preparation OOME is a preparation failure, not a live success with missing refs.

For JDK native binding readiness, JVMTI obtains the declaring Class and verifies bootstrap loader plus actual `java.base` module, then compares against the enrolled actual JDK Class/method ID. Existing owner/name/descriptor classification may select the correct proxy ABI, but cannot mark live readiness by itself. Warm the two candidate boundaries at trusted bootstrap against explicit support fixtures/resources; seal only after original-address interception and identity checks succeed. Rebinding after sealing invalidates live enrollment. No forced callback or filesystem read occurs in the notification reader.

**I1: exact shared-environment startup policy.** Use the existing single JVMTI environment for diagnostics and live capture; do not create another environment. Startup mode is selected once before capability setup: diagnostic-only (existing default), live-only, or live+diagnostic. Explicit live startup mode (live-only or live+diagnostic) must, during Agent_OnLoad and **before the FIRST enable of ClassFileLoadHook in this environment**, request `can_retransform_classes` together with the existing capabilities, check AddCapabilities, then call GetCapabilities and verify that bit and all other required event bits. Only then install callbacks/enable events and record a process-lifetime `live_hooks_ready` flag after successful enablement. Check the flag again at VMInit, enrollment and Seal. Never enable the hook first and acquire retransformation capability later. The existing `configure_native_bind_events:1440–1464` does not currently request this capability; this is required future source work, not an already present guarantee. Failure/unknown first-enable history makes live readiness permanently `Unsupported(RetransformUnavailable)` before any worker installation; no retry, second environment or fallback promotion. Required live startup fails initialization (Agent_OnLoad returns JNI_ERR) on this capability/ordering failure rather than continuing as diagnostics-only; the same Unsupported readiness category applies if a bridge detects missing/unproven initialization in an otherwise running process. A diagnostic-only startup keeps its existing mode/coverage and can never upgrade to live later. In live+diagnostic mode the shared environment is retransformation-capable from its first hook enable; retain the existing diagnostic hook handling/wire semantics, but qualify its newly observed external-retransform behavior with regressions rather than assuming identical coverage.

**Generation invalidation:** before other processing of *any* replacement hook with nonnull `class_being_redefined`, including cooperative diagnostic replacement, advance a process-lifetime monotonic atomic `replacement_serial`. An enrolled generation stores the serial sampled before enrollment; captures, sealing and reads require exact equality. A changed serial permanently invalidates every older live generation without dereferencing class or installation pointers in the hook. Overflow sets a permanent process-wide live-disabled latch; no wrap. ClassPrepare/diagnostic resynchronize cannot decrement/reset the serial or reauthorize an old generation. Fresh preparation is allowed only after trusted instrumentation/retransformation has completed and all old workers drained; sample serial before enrollment and recheck it at Seal. A replacement racing preparation rejects it; a hook callback returning is not proof of transformation completion, so the trusted deployment must join the instrumentation operation before reprepare. No policy swap on permanently supervised workers. Strong JNI refs/tables remain until all referencing workers physically retire; the native copy reader never dereferences those reclaimable objects, including after releasing its gate (§4).

Specification basis: [JVMTI 25 RetransformClasses](https://docs.oracle.com/en/java/javase/25/docs/specs/jvmti.html#RetransformClasses) states that `can_retransform_classes` must be set before ClassFileLoadHook is enabled for the first time in the environment; retransformation-incapable environments do not receive the new retransformation hook events. `can_generate_all_class_hook_events` alone is not sufficient. No need to request `can_retransform_any_class` merely to observe an external fixture's retransformation of an ordinary modifiable enrolled application class.

Feasibility boundary: JNI object transport and `RegisterNatives` are standard mechanisms and existing agent code already uses real jclass/global refs. The helper, registration, enrollment map and live-only startup split **do not exist yet**. Their classpath/VMInit behavior must be established by a real-agent smoke; this is implementable scope, not existing capability. Do not replace a failed bridge experiment with class-name matching or caller-supplied identity numbers.

## 3. Proposed C ABI v1

Add `include/live_stack_context_abi.h`, C-compatible `<stdint.h>` definitions, `extern "C"` exports, native-endian LP64, explicit 8-byte alignment for records containing uint64 fields. No `size_t`, C enum, bool, pointer, JNI handle or borrowed storage in records. Assert all sizes, alignments and offsets in C++ and FFM tests. All reserved fields must be zero. Unknown versions/opcodes/statuses/bits reject. ABI version is `1`; exports carry `_v1` suffix and never change layout in place.

**Bounds:** 128 retained frames, acquire 129, live native depth 1 (nested entry suppresses the entire outer chain). Global process-lifetime arrays of **256 worker cells and 256 installation cells**, 512 distinct pinned classes and 4096 enrolled methods per installation are explicit initial implementation choices, **not measured workload requirements**. Each installation reserves 1..256 worker cells, so at most 256 installations can be reserved concurrently. Loader/module counts cannot exceed class count. Preparation rejects larger plans before installation. Allocate each owner's fixed slot reservation from declared `DomainLimits.workers`; do not tie worker capacity to the unrelated 128-notification admission limit. Revocation/failed cleanup retains reservations until physical retirement and successful cleanup. Native arrays/table allocations happen at bootstrap, never copy/read. All minted semantic IDs are in **1..(2^60−1)** (a reduced bound in this revision), fit checked Kotlin/JNI longs, and come from one monotonic process-lifetime allocator; no rollback, wrap or reuse, even after failed preparation. Reserve IDs with saturating checked CAS outside copy; exhaustion permanently disables new admission/publication. Process cookie is a separately minted bootstrap incarnation discriminator; process start ticks remain kernel values, not allocator IDs. Replacement serial has the same finite bound and a permanent overflow latch. Installation/worker control words encode `(id << 3) | phase` in uint64; phases are never folded into the externally returned ID. Physical cell indices may be reused only after retirement with fresh IDs; no unbounded tombstone list.

### Records (offsets in bytes)

`mw_live_request_v1`, **size 88, align 8**:

| Offset | Field/type |
|---|---|
| 0,4 | `abi_version:uint32`, `struct_bytes:uint32` (=88) |
| 8,12 | `operation:uint32`, `reserved:uint32` |
| 16,24 | `process_cookie:uint64`, `process_start_ticks:uint64` |
| 32,40 | `installation_id:uint64`, `worker_id:uint64` |
| 48,56 | `task_id:uint64`, `code_generation:uint64` |
| 64 | `invocation_id:uint64` (copy: 0 for first read, exact ID for revalidation; lifecycle: zero) |
| 72,76 | `tgid:uint32`, `tid:uint32` |
| 80,84 | `worker_capacity:uint32`, `reserved:uint32` |

`mw_live_frame_v1`, **size 16, align 8**: offset 0 `method_token:uint64`; offset 8 `location:int64` (JVMTI native location -1 permitted, other JVMTI locations preserved; no line-number authority).

`mw_live_snapshot_v1`, **size 2152, align 8**:

| Offset | Field/type |
|---|---|
| 0,4,8,12 | `abi_version:uint32`, `struct_bytes:uint32` (=2152), `status:uint32`, `reason:uint32` |
| 16,24,32,40,48,56 | process cookie, process start ticks, installation, worker, task, code generation: `uint64` each |
| 64,72,80 | invocation ID, parent invocation ID, publication ID: `uint64` each |
| 88,92,96,100 | TGID, TID, binding, frame count: `uint32` each |
| 104 | `frames[128]`, 16-byte stride; unused entries zero |

Parent is always zero for Available v1; nested scopes are unavailable. Publication ID is fresh per successful publish, independent of invocation ID. Completeness is the status, not a caller-editable flag. No partial/truncated frames are returned as Available.

`mw_live_receipt_v1`, **size 88, align 8**: same offsets as request, except offset 8 is `status`, 12 is `reason`, and 84 is `allocated_slots:uint32`. Return the actually observed/minted identities; operation determines which are meaningful. On a failed call zero identity/count payload fields.

`mw_live_info_v1`, **size 64, align 8**: offsets 0/4/8/12 ABI/bytes/status/reason (`uint32`); 16 cookie, 24 process-start ticks (`uint64`); 32 TGID, 36 arch (=1 x86_64), 40 max workers (=256), 44 max classes (=512), 48 max methods (=4096), 52 max frames (=128), 56 binding mask (bit0=open0, bit1=openat0), 60 reserved (zero), all `uint32`. Build/JDK fingerprints are compared during bootstrap from trusted deployment inputs plus actual runtime identity; the info record is not an artifact attestation.

### Calls and statuses

```c
uint32_t mazewall_live_info_v1(void *out, uint32_t out_bytes);
uint32_t mazewall_live_lifecycle_v1(const void *request, uint32_t request_bytes,
                                   void *receipt, uint32_t receipt_bytes);
uint32_t mazewall_live_copy_v1(const void *request, uint32_t request_bytes,
                              void *snapshot, uint32_t snapshot_bytes);
```

Only argument buffers are pointers: FFM allocates them, their sizes are exact constants, C never returns or retains them. Null, alignment, exact lengths and version are checked before any struct interpretation; no pointer fields or caller addresses are followed. Arbitrary unmapped pointers cannot be made safe by C validation: this is a trusted in-process FFM boundary, not a hostile-pointer RPC. Invalid output buffers are not written; the return code is authoritative. Valid outputs are zero-initialized before filling and must agree with the returned code. No C++ exception crosses C/JNI.

Statuses `0 Available/OK`, `1 Absent`, `2 Incomplete`, `3 Busy`, `4 Stale`, `5 Unsupported`, `6 InvalidArgument`, `7 Exhausted`, `8 InternalFailure`. Fixed reasons: `0 None`, `1 NoTask`, `2 NoActiveInvocation`, `3 CaptureFailed`, `4 Overflow`, `5 UnknownMethod`, `6 NestedOrCallback`, `7 SlotContended`, `8 IdentityMismatch`, `9 GenerationInvalid`, `10 Shutdown`, `11 MissingBinding`, `12 RuntimeMismatch`, `13 AbiMismatch`, `14 Capacity`, `15 IdExhausted`, `16 Malformed`, `17 RegistrationMissing`, `18 NativeFailure`, **`19 RetransformUnavailable`, `20 InstallationRevoked`, `21 NotPhysicallyRetired`, `22 CleanupPending`**. InvalidArgument/Exhausted/InternalFailure become explicit bridge failure/unavailable outcomes, never Available. Unknown combinations fail closed. Busy permits no synchronous retry loop on the read path.

Lifecycle operations remain `1 ReserveInstallation`, `2 SealEnrollment`, `3 RegisterCurrentWorker`, `4 BeginCurrentTask`, `5 EndCurrentTask`, `6 InvalidateInstallation`, `7 RetireWorker`, `8 RetireInstallation`; copy uses operation 0. Reserve mints installation/reserves capacity; JNI enrollment associates its native-minted generation with that exact staged installation, and Seal verifies the association. Register/Begin/End run on the actual current platform worker, verify OS TGID/TID and native TLS owner; caller-supplied TID never registers another thread. Begin mints the task ID. End requires the exact task ID; stale End cannot clear a replacement task. Current-task state may be ended after installation invalidation, but no new task/worker can begin there.

**I2: cancellation deliberately invalidates the WHOLE installation.** The existing coordinating installation owner uses operation 6, not EndCurrentTask or a remote task-latch API. A task cancellation request is routed through that task's captured installation owner, carrying its immutable process/installation identity, even if cancellation is paused across task completion. At cancellation acceptance the coordinator closes that installation's JVM admission before the native call; it never relies on an interrupted worker to End. All tasks/workers in that installation lose live authority and future admission when the native revocation linearizes, including a task B already admitted there. This broader consequence is intentional: **there is no safe task-B continuation in that installation after any task cancellation**. Other installations are unaffected. Do not add a per-task remote revoke seam or claim task-local cancellation. Actual task End, joining, slot release and class cleanup remain distinct later steps.

Invalidate accesses only process-lifetime installation control words. Its exact-ID transition is `Staging(i)→Invalid(i)` or `Sealed(i)→Invalid(i)`; `Invalid(i)`/`Retired(i)` acknowledge already revoked without mutation. Use strong compare-exchange, never a check-then-store/fetch_or on a reusable cell. At most two CAS attempts are needed: Staging can only advance to Sealed (or an already invalid/retired state), never back. A changed ID returns Stale and must not modify the replacement. Thus a cancellation paused before its CAS cannot revoke a newly prepared installation reusing the physical cell. No payload/installation gate, worker cooperation, allocation, retry loop or capacity release occurs. Successful CAS is the revocation linearization point; already-invalid acknowledgment inherits the earlier point. A bounded scan uses atomic words only. A stale cancellation aimed at a physically retired/reused installation is an acknowledged old-owner event in the JVM coordinator; it never looks up a replacement by TID or current task. Exact-id idempotence lasts only while that cell still names the retired identity; after reuse return Stale, not an unbounded retired-ID cache.

### Per-operation contract (required fields, zero fields, receipts, outcomes)

Notation: `P` = exact cookie/startTicks/TGID from info; `I/W/T/G` = installation/worker/task/code generation; `D` = worker TID; `C` = requested worker capacity; `V` = expected invocation (optional for first copy). Version/size/opcode are always required, all reserved fields zero. **Every field not listed as required/optional is zero**; for receipts every unlisted payload field is zero. Native current-thread checks supplement, never replace, required identities. Process mismatch is Stale/IdentityMismatch. Successful receipts repeat `P` and listed identities/counts only. `allocated_slots` is the installation's retained reservation (0 after successful RetireInstallation), not a global count. Non-OK receipts/snapshots have zero payload; header alone reports failure. Info on failure likewise has header only.

Outcome abbreviations are exact status/reason pairs: `OK=0/0`; `B=3/7` gate contention (or bounded allocator CAS conflict); `SI=4/8` identity/phase mismatch including no matching registration; `SG=4/9` replacement-serial mismatch; `SR=4/20` installation invalid; `SH=4/10` shutdown; `CP=3/22` physical cleanup/other live workers still outstanding; `NP=3/21` worker has not physically exited; `EC=7/14` capacity; `EI=7/15` ID exhaustion. Common validated-call errors are `6/16` malformed input/unused fields, `5/13` ABI mismatch, `8/18` native failure. They are allowed for every row; `EI` also covers a latched serial/ID exhaustion. Setup/readiness `U` is only `5/11`, `5/12`, `5/19` (binding/runtime/retransformation unavailable). No other status/reason combinations are allowed for the row.

| Op / permitted caller | Required / optional input | Meaningful OK receipt | Other allowed pairs |
|---|---|---|---|
| info / trusted bootstrap | No request record; exact output bytes | Info fields as defined, no operation receipt | U, SH, EI; common buffer/native errors |
| 1 Reserve / trusted preparation owner | P,C (1..256) | P,I,C,allocated_slots=C | U, B, SH, EC, EI |
| 2 Seal / same preparation owner | P,I,G | P,I,G,C,allocated_slots=C (C native-observed, zero in input) | U, B, SI, SG, SR, SH, EI |
| 3 Register / current platform worker | P,I,G,D; D must equal current OS TID | P,I,W,G,D,C,allocated_slots=C | U, B, SI, SG, SR, SH, EC, EI |
| 4 Begin / same current worker | P,I,W,G,D | P,I,W,T(new),G,D | B, SI, SG, SR, SH, EI |
| 5 End / same current worker | P,I,W,T,G,D | P,I,W,T(ended),G,D | SI before any mutation; B only after exact native-owner/task prevalidation (may succeed after SG/SR/SH; cleanup) |
| 6 Invalidate / captured coordinating owner | P,I only (G/W/T/D/C/V zero) | P,I only; no capacity change | SI; succeeds even after shutdown/serial invalidation, no B |
| 7 RetireWorker / coordinating owner after join | P,I,W,G,D; T zero | P,I,W,G,D,C,allocated_slots=C | B, SI, NP (may succeed after SG/SR/SH) |
| 8 RetireInstallation / coordinating owner | P,I only | P,I,allocated_slots=0 | B, SI, CP (may succeed after SG/SR/SH) |
| 0 Copy / JVM validation listener | P,I,W,T,G,D; V=0 or exact invocation | Snapshot only, receipt not used | B, SI, SG, SR, SH, U, EI; `1/1` ended/no task, `1/2` no active leaf; `2/3,2/4,2/5,2/6` capture/overflow/unknown/nesting |

Wrong-state Seal (missing JNI enrollment), double Register and Begin with an active task return SI; no hidden reset. Repeated Seal of the exact already-sealed generation is OK only if still valid. Repeated End is OK only while ordinary under-gate `last_ended_task` and the unchanged worker identity match and no new task is active; otherwise SI. Retirement repeats are OK only for the same cell incarnation while its retired tombstone remains, otherwise SI after reuse. RetireInstallation first invalidates that exact installation, including a failed staged reservation; it never releases anything while any registered worker remains unretired. Seal and reserve may fail Busy rather than wait on their administrative gates; no synchronous native loop is required. End's gate contention defers task physical-completion admission bookkeeping: no task B starts until End succeeds (or the installation is invalidated/drained). No operation silently upgrades an invalid phase to valid.

**R1: exact End sequence — all mutations under gate.** Register records native-owned current-thread TLS scalar copies of P/I/W/G/D and the bounded worker/installation indices. Begin and successful End maintain a thread-private task state `NoTask`, `Active(T)` or `Ended(T)`; update this state only as part of their under-gate commit. `Ended(T)` is the TLS mirror of the bounded last-ended bookkeeping, not an additional historical registry. No remote owner writes this TLS, no caller can supply its contents, and no ordinary cell registration/last-ended field is read before gate acquisition.

1. Validate the ABI/zero fields, actual current platform thread/OS TGID/TID, exact P/I/W/G/D against native-owned TLS and process identity atomics, exact `Registered(W)` in the immortal worker control word, and the same installation ID in its immortal control word (Sealed or Invalid is allowed for cleanup). For `Active(T)` require the request's exact T and matching atomic active-task ID; for `Ended(T)` require the exact same T and atomic active-task ID zero; `NoTask` or any mismatch returns **SI before attempting the gate or changing any state**. Do not require current serial/live-readiness/shutdown validity for cleanup: G is the originally registered G, not a newly admitted generation. These checks use only private TLS and immortal atomics, never ordinary registration or a borrowed table pointer.
2. Try the worker gate once. Only an identity-prevalidated End can return Busy. On Busy **do not clear publication, task, payload or TLS state**; the still-active task and outstanding completion bookkeeping block next Begin. The owner retries completion outside the native call or invalidates/drains the installation; Busy is not physical completion. No native spin/retry or remote task-local cancellation is introduced.
3. Under the gate, revalidate exact ordinary P/I/W/G/D, worker/control/task identity and the TLS state before mutation. For exact Active(T), clear active-publication, clear active-task ID, set ordinary `last_ended_task=T` and TLS `Ended(T)`, then release and return OK; payload bytes need not be rewritten because publication is zero. For exact Ended(T), require ordinary last-ended T and no active task, release and return idempotent OK **without mutation**. Every mismatch releases and returns SI without publication/task/payload/TLS mutation. A later Begin replaces Ended(T) with Active(T') under the gate, so any End(T) becomes SI even if that gate is contended. Invalidation/serial change/shutdown does not prevent this exact-owner cleanup, but cannot authorize a new Begin. Op6's separate whole-installation cancellation semantics are unchanged.

**Existing lifecycle interleaving obligations:** Seal commits with exact `Staging(I)→Sealed(I)` CAS, never an ordinary store that overwrites Invalid(I), even while holding the administrative gate. Registration's acquisition of enrollment ownership and worker-accounting commit/rollback must stay covered by the existing installation administrative gate/accounting boundary; final retirement cannot free tables/reservations while registration is in flight. Op6 may invalidate without that gate: Register must check that exact installation/serial before making its worker usable, and an invalidation racing its commit leaves no usable authority. Final retirement invalidates first and retains its owner/returns Busy while that registration boundary is occupied. No usable worker or enrollment-table access may appear after final retirement/reuse. These are implementation obligations of the existing gates/ownership, not a new framework.

RetireWorker is called by the owner **after join/physical termination**, verifies native ThreadEnd/exited state, and tries the slot gate; Busy retains its counted owner. ThreadEnd marks that worker's process-lifetime control word Exited and clears publication, not refs or capacity; no new thread can overwrite TLS/ordinary registration of that worker. Never treat interruption/cancellation as exit. RetireInstallation requires every worker retired and successful reference/table cleanup; only then return all reserved cells/capacity. Ordinary class-ref/table cleanup occurs off the copy path under the installation's try-once administrative gate. Failed cleanup retains handles and retries outstanding steps. Existing enforcer physical-worker retirement is the coordinating owner, not a new process registry. Installation storage/authority words themselves are never freed, even after cleanup.

## 4. Stopped-writer-safe copy (ordinary payload, no racy seqlock)

**I3: stable storage, routing and lifetime.** The two fixed 256-cell arrays in §3, process identity atomics, replacement serial, shutdown/exhaustion latches and readiness state exist for the entire process lifetime. The startup agent/library remains mapped through the last possible call; late unload/dlclose is unsupported. Never free a cell, its gate or its atomic fields; retirement may only reuse its contents with a fresh ID. No cell contains a pointer that copy follows. Installation cells contain an atomic control word and an administrative gate; ordinary enrollment/cleanup pointers belong to that gate and are **not read by copy at all**. Worker cells contain an atomic control word, atomic active-task ID, atomic active-publication ID, atomic unavailable reason and a payload gate. Worker ordinary registration, including installation-cell index, expected installation ID, code generation, enrollment replacement serial, TGID/TID and last-ended task, is accessed **only under that worker gate** by copy/registration/retirement. Readiness/process prechecks use process-lifetime atomics only, not an ordinary cached installation/registration pointer.

Control-word phases: installation `Empty=0, Staging=1, Sealed=2, Invalid=3, Retired=4`; worker `Empty=0, Registered=1, Exited=2, Retired=3`. A zero word is Empty; nonempty words are `(id<<3)|phase`. A given installation ID never returns to Sealed after leaving it, nor a worker ID to Registered after exit. Reserved but unregistered worker cells remain owned by installation reservation bookkeeping under administrative control; copy cannot route to them. At successful retirement retain the retired control word until reuse, then install a fresh ID under the relevant gate. Only this bounded last-incarnation tombstone supports retirement idempotence. No reader pins a reclaimable object, increments a refcount, traverses a hash table or participates in deferred reclamation.

Require `atomic<uint64_t>::is_always_lock_free` on the admitted target; atomic_flag is lock-free. Choose **sequentially consistent loads/stores/strong CAS for all authority/routing words and latches** in v1, plus acquire test-and-set/release-clear for gates. Do not weaken this ordering as an unreviewed optimization. Gate-protected ordinary fields need no atomics. Copy scans at most 256 worker control words to find the requested exact worker ID (Registered phase); a remembered index is a bounded array index only. Never inspect ordinary registration during this scan. No spin, condition variable, mutex wait, allocation, JVMTI/JNI, symbol lookup, file read, marker/ring dependency, Java callback or reclamation inside copy/gate.

**Single worker writer:** before capture/agent work, entry stores active-publication=0; checks current TLS worker/task and live generation using the authority atomics (its prepared enrollment map remains owned through physical exit); increments a worker-local native depth even for unsupported entries. Any nesting clears authority and poisons the outer invocation until it exits; neither nested nor outer later restores a live snapshot. Before every possible Java callback, invalidate and poison likewise. Only the two selected open proxies under the guarded and audited envelope in §4.1 may publish; other callback-capable or unknown implementations remain outside v1. The guard is not an arbitrary hidden-callback detector.

Capture into writer-local storage with no slot gate held; translate methods using the immutable enrolled map and validate generation. Mint the invocation/publication IDs outside the slot gate (checked allocation may fail unavailable); no allocator CAS loop belongs inside a read or held payload gate. Try gate **once**. If busy, record unavailable atomically and do not touch ordinary registration/payload. If acquired, recheck exact worker/task/installation/serial, fill the complete payload, store the fresh active-publication ID, release gate. Only then call the original JNI function. **No writer gate spans the original function, JNI/JVMTI capture, diagnostics, marker calls or any syscall-producing test barrier.** Invalidation on proxy exit happens before any other exit work, including exception handling. Preserve the actual original return and pending exception when it runs; pre-original exceptions follow §4.1. Record incomplete reason atomically when capture/overflow/translation fails; old payload cannot become active. **End follows the R1 sequence in §3: validate before any mutation; only exact Active(T) under the acquired gate clears publication/task.** Valid End contention changes nothing and blocks next-task admission. This lifecycle request is distinct from a proxy's own entry/exit clear. Begin/End update ordinary bookkeeping and native-owned task TLS under the gate; Begin verifies installation/serial before and after binding, clearing the new task and refusing it if invalidation won the race. No new task uses an ended task's ID/publication.

**Reader, exact sequence:**

1. Validate input and atomic process/readiness/latches. Scan only the process-lifetime worker control atomics for `Registered(request.W)`; absent/mismatching incarnation is SI. Do not derive a pointer to enrollment or installation-owned memory. A pause here holds no acquisition and does not prevent reuse.
2. Try that fixed cell's gate once; on contention return B. **Recheck the full exact Registered(W) control word under the gate before reading any ordinary registration.** If reused/exited, release and return SI without inspecting the new owner's ordinary state. Otherwise read ordinary registration under the gate, compare P/I/W/G/D, and copy the installation cell's bounded index, expected sealed control word and enrolled replacement serial into reader-local scalar variables. Check index <256. No lookup-derived borrowed pointer survives.
3. Read task/publication atomics. With the exact registration but no active task, return Absent/NoTask; a nonzero different task or expected invocation mismatch is SI. A zero publication returns the defined absent/incomplete reason only, never payload. For nonzero publication, copy the bounded ordinary snapshot and validate all joins/counts under gate. Validate installation control equals `Sealed(I)`, replacement serial matches the local enrolled serial, and global latches permit live access. Generation ID comes from the under-gate ordinary registration and copied snapshot, not from a reclaimed generation object. Unknown tokens/counts still fail closed.
4. Release the payload gate. Through the **last** native authority read, use only local scalar copies, the two immortal arrays' atomic words at the captured bounded indices and process-lifetime atomics: check exact `Registered(W)`, exact task ID, exact nonzero publication ID, exact `Sealed(I)`, unchanged replacement serial, and clear shutdown/exhaustion latches. No enrollment/class/map/ref/ordinary registration access is allowed after release. Any mismatch produces Stale (SR/SG/SH for the specific latch, otherwise SI) with zero output payload. Success returns only the reader-owned copy.

Final lifetime does not rely on holding the payload gate: **all post-gate atomics themselves are immortal storage**, including installation authority. The worker and installation cell can both be retired/reused while a post-gate reader is stopped, but their new IDs cannot equal its local expected IDs. An old sealed control, task ID or publication never reappears. With the chosen ordering and irreversible departures from those exact expected values, successful final checks validate the copy at gate release; an invalidation after its corresponding final load can linearize after that read. B6 must still revalidate for verdict/effect freshness. Slot gates protect ordinary registration as well as payload; they do not pretend to retain pointers elsewhere. Status/reason races can only return unavailable, never an earlier Available payload.

**Stopped writer / reuse proof:** the worker published and unlocked before entering original `open*`; seccomp stops it there. Reader acquires the released gate and copies without needing the worker to run. If the worker instead pauses under its publication gate, copy returns Busy immediately. If reader stalls holding gate, writer invalidates first, skips publication and proceeds without context; selected opens deny, retirement cannot reuse that worker cell, and owner capacity remains counted. If reader stalls before gate acquisition or after gate release, retirement/reuse can progress; resumed reader checks the exact nonreused identities using immortal atomics and denies, with no UAF or ordinary-registration race. Installation-wide cancellation (§3), replacement serial and shutdown act without waiting on the payload gate or stopped worker. Publication racing an external invalidation cannot authorize because reader checks installation/serial/global latches as well as publication. Revocation releases no slots/classes; bounded cleanup happens off-read, after physical exit and successful gates.

Task exit clears before another task can bind; task IDs are never reused. Shutdown invalidates installations before publishers, hooks or refs are torn down. Fork/exec are outside owned-task admission; child `getpid` mismatch rejects, and a new JVM image requires a new process cookie/enrollment. The cookie is a bootstrap-minted process incarnation discriminator bound to actual TGID/start ticks, not a random authentication secret.

### 4.1 Reviewed re-entry amendment and qualification gate (2026-11-04)

The matching candidate [open0/openat0 source](https://github.com/adoptium/jdk25u/blob/7ec19fb1eda7e42498b7ee4ebde29f77aa961a1c/src/java.base/unix/native/libnio/fs/UnixNativeDispatcher.c#L502-L533) constructs Java exceptions on error. A MethodEntry event may be hidden by [HotSpot state](https://github.com/adoptium/jdk25u/blob/7ec19fb1eda7e42498b7ee4ebde29f77aa961a1c/src/hotspot/share/prims/jvmtiExport.cpp#L1849-L1886), and a native co-agent can create a [later hidden upcall](https://github.com/adoptium/jdk25u/blob/7ec19fb1eda7e42498b7ee4ebde29f77aa961a1c/src/hotspot/share/prims/jvmtiEnvBase.cpp#L861-L890). Neither original is unconditionally callback-free. This amendment permits only those two originals as candidates under the guard and closed deployment below; no profile or route is qualified today.

**Closed identity and order.** The fixed `temurin25-linux-x64-closed-v1` profile requires separately audited live-only and live+diagnostic variants, startup mazewall agent only, and the source-bound open0/openat0 route subset. Trusted preparation receives one immutable internal `LiveDeploymentIdentity` alongside the explicit already-started agent artifact: fixed profile/variant, source and artifact identities, effective VM arguments, ordered classpath and helper loader/module expectations, original/dependency mapping-relative targets and route subset. The fixed external-retransform extension adds its exact fixture and libinstrument identities. It is data, not an approval Boolean or proof against hostile native code; no public API, extra C ABI/export/status, generic launcher, registry, parser or per-launch approval is introduced.

Startup selects mode once, requests and verifies MethodEntry capability in the same existing JVMTI environment along with I1's capabilities **before the first ClassFileLoadHook enable**, installs/checks callbacks, and fails required live startup on missing capability or setup. VMInit initializes, pins, registers and prepares the exact helper before admission. Trusted preparation compares actual runtime, effective arguments, files, mappings, loader/module and resolved binding/dependency targets against the identity; missing proof or mismatch is Unsupported (retransformation failure uses existing RetransformUnavailable; other incompatible setup uses RuntimeMismatch). Native/bootstrap observes actual TGID, start ticks and cookie; a private JNI exchange may precede public readiness, but failed public info has zero payload. Establish checked agreement before usable info; Reserve is staging, enrollment binds the immutable checked identity to that reservation, Seal rechecks startup/binding/serial, and worker event enablement precedes Register and Begin. No profile switch or diagnostic resynchronization recovers old authority.

Controlled qualification may construct candidate identity data after measuring actual artifacts and reviewing source premises, then run the same real startup, JNI, guard, enrollment, lifecycle and ABI checks. An Available snapshot in that fixture is evidence under test, not supported use. Qualification remains pending until every positive and negative gate passes; ordinary preparation may use only a completed matching qualification, retained outside the hashed artifacts. No force-ready publisher, synthetic production JNI reference or bypass is allowed. Missing inputs fail identically in either use.

**Guard and source induction.** On each actual owned platform worker enable MethodEntry outside owner/payload gates before Begin and retain it through physical exit. The pinned initialized helper has one private static ordinary native, nonsynchronized, nonintrinsic `boolean probe0()`, registered and signature-prepared before admission. Its fixed native body reads only owned thread scalar state, without JNI/JVMTI, allocation, locks, logging or effects. For each outer activation clear publication and old witness, arm a fresh nonreused same-worker/activation attempt, and invoke that exact cached helper through JNI, not a direct C call. Require exactly one matching MethodEntry acknowledgment and affirmative body return with no exception; consume it once. Missing, duplicate, wrong or stale evidence poisons irreversibly without retry. Nested scopes do not probe or restore parent authority. After acknowledgment every delivered MethodEntry, including the helper, clears and poisons before publication or relevant Java bytecode. No gate spans probe, callback, capture or original.

The acknowledgment proves only absence of inherited hiding at that point. Final matching-source and installed-artifact audit must cover helper/event return, capture, diagnostic/shared callbacks, both originals, libjava/libnio/libc/loader and their dependencies, initialization, error, allocation, resource, signal and cancellation paths. It must exclude later hidden JNI/JVMTI upcalls and continuation/carrier transitions before an ordinary Java entry can poison, and establish notification before the first relevant constructor/initializer bytecode; it does not promise notification before all VM effects. A successful probe, warm-up, version or artifact digest alone is insufficient.

The trusted immutable deployment excludes unreviewed native loading, debugger/late attach, callback/JNI-table interference, helper mutation or rebinding, forced return/PopFrame, unload, unreviewed signal callbacks and native continuation/carrier entry. Controlled launch requires `-XX:+DisableAttachMechanism`, `-XX:-EnableDynamicAgentLoading`, `-Xshare:off`, explicit JVMCI disablement and no inherited agents. Operator-controlled preload, resolution and writable aliases are premises, not library sanitization. Known extra/substituted agents, unresolved targets and observed mismatches refuse before admission; unobservable foreign interference lies outside the trusted-JVM envelope, not within a universal mutation-detection promise.

**Proxy outcomes.** Clear and poison with native-owned state before failure handling; while an exception is already pending, `ExceptionCheck` is the only permitted probe-path JNI operation. Never clear or replace the throwable.

| Decision | Original calls | Result and authority |
|---|---:|---|
| Exception already pending before probe | 0 | Preserve exact throwable; return ignored `jint -1`; no helper, unpublished. |
| Fresh acknowledgment and affirmative helper, no exception | 1 | Preserve actual original result, exception and errno; publication still requires all ordinary checks. |
| Missing/wrong/duplicate/stale acknowledgment or false/refused helper, no exception | 1 | Delegate once unpublished; preserve actual result, exception and errno. |
| New helper entry/body/return exception, including pre-body failure | 0 | Preserve exact new throwable; return ignored `jint -1`, unpublished. |
| Cancellation, replacement, shutdown or nesting, no exception | 1 per invocation | Delegate once unpublished; outer remains poisoned. |
| Same invalidation/nesting with pending exception | 0 per invocation | Preserve exact throwable and return ignored `jint -1`. |

Restore entry errno before delegation and save actual original errno immediately on return; restore that value at proxy return. Early exceptional return restores entry errno, never fabricates a syscall error. Preserve actual original EACCES/EPERM and internal EINTR behavior; no per-proxy replay. A new probe exception is an explicit opt-in pre-original behavior, not full original equivalence. Unpublished delegation is not permission: B6 denies unavailable joined snapshots, never CONTINUE or legacy fallback; B4 resource gates remain independent.

**Mandatory external fixture and negatives.** The fixed `temurin25-linux-x64-retransform-test-v1` extension uses a separately built startup `LiveRetransformAgent` with its own Instrumentation, synchronous premain and no transformer, background thread, native helper or shutdown hook. A bounded non-owned platform controller retransforms the actual enrolled modifiable workload Class, never the helper/JDK binding or a cooperative latch. Audit actual JAR bytes and matching libinstrument paths: matching source installs hooks but leaves them disabled absent transformer registration; a delivered transformer callback is not native-only. [Source](https://github.com/adoptium/jdk25u/blob/7ec19fb1eda7e42498b7ee4ebde29f77aa961a1c/src/java.instrument/share/native/libinstrument/JPLISAgent.c) viability is not binary qualification. Require real Available before control, a real replacement hook, permanent old-generation Stale/GenerationInvalid including diagnostic resync, live-only and shared variants, no-op bytes, preparation race and join before reprepare; a ten-second deadline, timeout or error fails. An extra/renamed/unknown hidden-upcall co-agent must be refused against both fixed profiles; its separate public-JVMTI research reproduction is explicitly unsupported, not universal detection.

Before qualification test real JNI helper acknowledgment and body ordering, direct-C/missing capability/callback, stale/wrong/duplicate and inherited-hiding witnesses, same-task nonreuse, helper mutation/setup failures, actual exception constructors and pre-body failures, exact throwable identity/call counts/errno, original success/missing/EACCES/EPERM, callback before publication, nesting, cancellation/replacement, contention and physical exit/cleanup. On each actual original's error path observe zero publication and irreversible poison before the first real constructor/initializer bytecode, not merely after return; include actual JNI `NewObjectV`/`ThrowNew`/initialization paths and prewarmed compiled callbacks. Exercise inherited hiding through real public Java/JVMTI corridors including `GetThreadGroupChildren` and disable-suspend, not VM-private flag manipulation; a prior successful probe followed by hiding in the same task must not reuse its witness. Exercise the hidden-upcall counterexample during helper-event handling and later original/native-event handling in the separately unsupported research fixture, distinct from supported-profile launch refusal; poison is irreversible and authority is never restored. Audit exact installed bytes, binding/route linkage and final guard chain; retain B3 compatibility and B4/B6/B7 resource, denial and notification gates. No design review or full build substitutes for these controls.

## 5. Typed platform capability and joins

Avoid adding any method (even a Kotlin source-default getter) to the existing NativeEngine JVM interface. Add internal optional `NativeStackContextProvider { val stackContexts: NativeStackContexts }`; RealNativeEngine implements it. A small internal extension on **the captured engine** returns its provider or singleton Unsupported. This is one capability marker, not a plugin registry. Existing NativeEngine implementations retain identical bytecode obligations. Make the existing top-level `RuntimeSnapshot` in `LinuxNative.kt` implement that provider and forward to its captured engine's capability; do not resolve it later against the mutable global test engine. Follow existing friend access; no public user API or Gradle module/dependency is needed for B2/B3. Any unavoidable public cross-module bridge type requires `@MazewallInternal` and API dump review.

New `NativeStackContexts.kt`: typed installation/worker/task/enrollment owners, immutable copied frames, separate validated ID/count wrappers (reuse lifecycle `Pid`/`Tid`; never interchange generations, tokens and counts), and sealed `Available(snapshot)`, `Absent(reason)`, `Incomplete(reason)`, `Busy`, `Stale(reason)`, `Unsupported(reason)`. Malformed native results are `Incomplete(MalformedNativeResult)` and invalidate readiness/installation where structural; they are not parsed partially. Enrollment/setup errors remain explicit failures. Use immutable backing copies, not caller-mutable lists/arrays. Snapshot methods expose no segments, JNI refs, method handles or raw pointers.

`ffi/internal/RealNativeStackContexts.kt` owns confined arenas/layouts/downcalls, copies before close, validates all enums/counts/zero fields/positive IDs and exact output sizes. Resolve `_v1` exports once at trusted bootstrap against the explicitly supplied, already-started agent artifact; use library lookup only to locate exports, then require the Agent_OnLoad/VMInit initialized readiness marker and the same process cookie as JNI enrollment. Missing exports/incompatible build are Unsupported, not a late-load fallback. No runtime scan of arbitrary libraries. Calls use explicit statuses and do not depend on errno; any underlying native call that can fail with errno captures it immediately before other work.

Pure join input is expected process incarnation `(Pid,startTicks,cookie)`, installation, worker registration/Tid, current owned-task lease, sealed code generation, plus one immutable supervisor request identity. Compare every expected identity, complete frame count 1..128, nonzero method tokens, binding-route compatibility and exact ordered corridor. Reader does not perform `/proc` or namespace translation: reuse established supervisor process/task identity and require the first same-PID-namespace envelope. Numeric TID alone is insufficient; a reused TID cannot match a fresh nonreused worker registration. Thread names and profiler IDs have no role.

Notification identity is **not invented in the native snapshot**, because it does not exist until syscall stop. B6 binds `(listener/session, notificationId, copied-operation/requestId, process identity, installation, worker, task, code generation, invocation, publication)` in the existing verdict envelope, re-reads with expected invocation/publication (publication compared in the typed layer) before releasing a verdict, and applies existing ID_VALID checks before effects/terminal reply. Each syscall retry needs a fresh notification/request join even if invocation remains active. No cached verdict, parent restoration or CONTINUE for pathname operations. Every unavailable status denies the selected application operation; optional callback can only narrow an already valid stack/resource decision.

## 6. Exact implementation/test handoff

**Essential new files:** native `include/live_stack_context_abi.h`, `include/live_stack_context.hpp`, `src/live_stack_context.cpp`, `tests/live_stack_context_test.cpp`, `tests/LiveStackContextWorkload.java`, and a **separate** `tests/LiveRetransformAgent.java`/`tests/live-retransform-agent.mf` fixture JAR declaring `Can-Retransform-Classes: true`; platform `src/main/java/io/mazewall/ffi/internal/LiveStackBootstrap.java`, `src/main/kotlin/io/mazewall/platform/native/NativeStackContexts.kt`, `src/main/kotlin/io/mazewall/ffi/internal/RealNativeStackContexts.kt`, focused adapter/lifecycle tests and a real-agent integration fixture. No new module/dependency. Keep native implementation out of the already large agent file where possible. The separate fixture agent is a future automated test artifact, not a spawned development subagent.

**Existing modification scope for B2/B3:** agent callbacks/proxies/capture factorization/startup options; CMake target and fixture registration; NativeEngine capability seam without altering interface members; RealNativeEngine; the existing top-level RuntimeSnapshot declaration in `LinuxNative.kt` (not a presumed separate file); relevant mock capability provider, architecture closed-set and API/consumer checks only. Existing invocation/generation headers change only if shared capture factoring actually requires it; do not weaken diagnostic nesting/resynchronization. Later owned-domain/task wiring, supervisor/enforcer policy and resource authorization are B4–B6, not daemon/control edits in this delivery.

Mandatory future acceptance tests (not executed for this documentation publication):

1. **Native ABI:** compile-time sizeof/alignof/offsetof for every field; null/misaligned/short/long/version/reserved/opcode rejection; output zeroing; exact return/status agreement; unknown enums; maximum counts; last legal ID `(2^60−1)`, rejection of `2^60`/INT64_MAX as minted IDs and saturated allocator/serial with no wrap; all capacities remain occupied after failed retirement. Buffer guards detect overrun; no fabricated closeable FD needed. Exercise every per-operation required/zero/receipt field and allowed status/reason pair, especially cleanup after invalidation and no Busy/capacity change for op6.
2. **State/concurrency:** no-task absent; clear→capture→publish→exit; 128 frames complete/129 overflow; capture failure/unknown method; pause writer under gate yields immediate Busy; pause writer inside original function yields Available; reader-held gate makes writer skip and clear; generation/shutdown during capture/copy/publication denies; task reuse/stale End; physical retirement gating and retry. **I2 controls:** stop A inside original leaf call, cancel from its owner with op6 while A remains stopped, prove installation revoked/copy unavailable and capacity still counted; no B can Begin there afterward. If B began before a delayed A cancellation, B is also revoked (intentional installation-wide policy). Pause cancellation after discovering installation cell/before exact CAS, retire old workers/installation, reuse both cell types for fresh I'/W'/T', resume: old revoke returns SI and leaves new task/publication unchanged. Repeat invalidation acknowledges the same invalid/retired incarnation only. **I3 controls:** pause reader after atomic pre-gate discovery, physically retire/reuse worker slot, resume: exact-control mismatch denies before ordinary-registration access; run under native TSan. Pause reader immediately after gate release, retire final worker/installation, free old enrollment tables/refs, reuse both cells, resume: no old authority dereference, no Available, no invalid access under ASan/lifetime instrumentation. Also pause reader holding gate: retirement returns Busy and does not reuse/release; pause writer inside original call: reader still succeeds without worker cooperation. Test failed cleanup plus retry, last-incarnation tombstone expiry and injected ID/control/serial exhaustion with no wrap/ABA. Deterministic barriers are test-only; copy remains a fixed scan plus one gate attempt and fixed final atomic checks, not a reclamation/spin loop.
   **R1 exact lifecycle fixture:** on the actual registered current worker, complete task A, Begin B and install a synthetic valid Available snapshot for B. End(A) with the gate free returns SI with B task/publication/payload/TLS unchanged and Copy(B) Available. Repeat while a separate test thread holds the worker gate: End(A) still returns SI before attempting the gate; B remains unchanged and Copy(B) is Available after that test holder releases the gate (Copy during contention is Busy as specified). Exact End(B) while contended returns Busy with no mutation; Begin(C) cannot admit while B/completion remains outstanding, including after releasing the gate but before retrying End(B). Retry exact End(B) with a free gate clears publication/task and records Ended(B), Copy(B) is no longer Available; duplicate End(B) is idempotent only while no new task exists. After Begin(C), End(B) is SI/no-op, free or contended. Repeat exact End cleanup after installation invalidation, replacement serial change and shutdown: cleanup succeeds with the registered original G while new Begin remains denied. Wrong P/I/W/G/D, malformed fields or wrong current thread must not mutate B either. This synthetic ABI-state fixture does not assume JNI leaf code normally calls End while a snapshot remains active.
   **Existing commit-boundary fixtures:** pause Seal just before its exact Staging CAS, let op6 invalidate, resume and require SR/no sealed resurrection; reverse the winning CAS order and require final Invalid/no subsequent authority. Pause Register after acquiring enrollment ownership but before accounting/publishing the worker; invalidate and attempt final retirement, require retained capacity/table owner and Busy, then resume Register and require rollback or an unusable accounted worker that must physically retire before cleanup. Also retire/reuse the installation before a paused pre-acquisition Register resumes: SI, no stale table access or usable worker. Instrument tables to detect premature free; these tests cover existing ownership/CAS obligations, not a new synchronization subsystem.
3. **Native/JNI identity:** real Class and reflected methods enroll; same binary names and descriptors from two loaders produce distinct tokens and wrong-corridor rejection; inherited declaring-class mismatch; constructors; bootstrap-null loader and java.base module; duplicate/oversize/partial-OOME rollback; enrolled refs survive GC until retirement; redefinition/retransformation including cooperative resync permanently rejects the old generation; unexpected native rebinding invalidates.
   **I1 discriminating integration case:** startup the live agent and a distinct capable instrumentation fixture with completed premain, then seal a real enrolled generation and establish an Available control. The fixture calls its own `Instrumentation.retransformClasses(enrolledClass)` on a verified modifiable class, without the live agent's cooperative helper or direct latch call. Require a real replacement hook and permanently Stale/GenerationInvalid for the old generation afterward, including after diagnostic resynchronization; run live-only and shared live+diagnostic variants. A no-op-byte transformation still must invalidate. RedefineClasses-only and direct invalidation-unit tests do not substitute. Capability acquisition failure, GetCapabilities missing-bit/error and first-enable-before-capability startup fault fixtures must never seal readiness or install a worker. Pause external instrumentation before actual completion: reprepare is disallowed until it joins; replacement racing enrollment/Seal rejects that preparation. Existing diagnostic generation/redefinition regressions remain required under the shared capable environment.
4. **Agent behavior:** live-only startup and NIO fixture with no collector/ring/dictionary/USDT; excluded diagnostic capture profile still captures required live boundary; shared live+diagnostic capture invokes GetStackTrace once; executed originals preserve their actual success/error/exception, with explicit pre-original exceptions as in §4.1; nested proxy and callback poison outer context with no restore; ThreadEnd/shutdown invalidates before release. Missing helper, wrong bridge loader, missing startup agent, missing binding, host/wrong JDK/arch/version mismatch reject before worker installation. No marker or definition publication prerequisite.
5. **Bridge:** C layouts versus FFM oracle; malformed field/status/count/token combinations fail closed; owned copies survive arena close and input mutation; unsupported mock default; Busy/Stale handling; engine snapshot survives global engine swap; cleanup failure retains one owner; no profiler runtime dependency/raw FFM leakage to enforcer. Precompiled legacy NativeEngine implementer runs with new platform and returns Unsupported through the new extension; existing compiled consumer links. Verify Java/Kotlin bridge accessibility/method descriptors with the actual toolchain and inspect ABI dumps, rather than assuming compiler defaults.
6. **Later B6/B7 adversarial gate, not B2/B3 authority:** every pure outcome denies except fully joined Available; wrong process/start ticks/Tid/worker/task/generation/invocation/publication/binding/corridor; late/replayed notification; retry distinct ownership; zero target Thread.getStackTrace calls; GC/cold-loading liveness; original base denial and forbidden resource cannot be widened. Independently verify exact bytes/FD effects, not IOException alone. Preserve existing binding/invocation/generation/marker/redefinition/diagnostic regression tests.

**Additional future B4/B7 controls:** establish pinned read roots, helper confinement and
credential/namespace equivalence, exact-object acquisition/reopen and FD injection;
reject unknown worker-only restrictions. Exercise symlink, rename, mount escape,
`..`, hard-link alias, wrong dirfd, nonregular object, flag mutation, generation
mismatch and cancellation races without forbidden open or injected-FD effects.
Verify approved reads by exact bytes with positive/static-only controls, and deny
wrong corridor, forbidden resource/static syscall, unavailable capture and missing
agent/binding. Exercise listener/helper death, callback stalls, cleanup retry,
worker reuse, concurrency and GC pressure with bounded completion. Runtime-support
reads must use separately pinned resources. These are acceptance obligations, not
results; B4 remains a prerequisite even if B2/B3 succeed.

### Open gates / no disguised promises

- B2/B3 must implement and smoke the proposed VMInit/RegisterNatives classpath seam and live-only startup, establish actual binding readiness, and qualify the exact artifact/JDK route matrix. No live bridge/route qualification exists from this read-only work.
- I1–I3 and R1 are reviewed design requirements; architecture approval remains conditional. Frozen for implementation review means subsequent changes require review, not that implementation exists or runtime correctness is proved. Shared retransformation-capable startup, installation-wide cancellation, immortal authority storage and exact End/commit-boundary safety still require the discriminating evidence above.
- Initial capacities and strict unknown-tail denial need a real enrolled corridor corpus check; exceeding them is a preparation refusal, not truncation or a text-match fallback.
- The exact two callback-capable originals require §4.1's guarded, source/artifact-audited closed envelope; the witness cannot generically detect later hidden native→Java callbacks. No deployment is qualified today.
- B4 still must prove resource-safe regular-object acquisition/reopen, helper confinement/credential equivalence and copied-request FD injection. Live availability cannot bypass it. The separate B4 feasibility report is not implemented authority.
- No performance acceptance limits were chosen. Final production docs/support rows remain **proposed until actual B7 qualification**. All tests above are proposed future work, not tests run here.


## Bounded checked association checkpoint (2026-11-12)

The selected internal canonical acquisition captures one environment and a checked association before its baseline. Its concrete request carries data, not authority. At producer use, the original checked object verifies identity and captured snapshot references and revalidates the native deployment; the same runtime lock orders a one-time claim against retirement. A retirement win prevents Reserve. A claim win retains the counted original actor while the actual Reserve may finish after retirement is requested. A successful original owner is stored in the private volatile slot immediately after Reserve, before attachment or other fallible observation. The reducer may consume only that same published owner. Ambiguous attachment or notification keeps the original failure and blocks both drain completion and manager stop; no ambiguous effect is retried or reported Complete.

This is an internal/friend-module contract within the fixed two-module trusted JVM, not a hostile-JVM boundary or a universal capability non-escape guarantee. Existing authenticated adapters and capabilities remain JVM-callable within that envelope. Default acquisition remains on its existing path. The checked association does not make thread-scoped containment a complete arbitrary-code boundary or relax the process-wide baseline.

The current controlled test used explicit protected startup binding and explicit openat fixture preparation; it does not establish natural or unprepared readiness. The normal producer observed counted Ready and original local retirement after the B6 dispatcher refusal, while acquisition reported `Unknown` with that refusal. Both claim orderings and reentrant abandon passed the original-owner/count/future/resource assertions. The after-Reserve fatal case preserved the exact fatal and reported acquisition `Unknown`. Notification-fatal and old-abandon-after-Reserve retained the original owner and incomplete/unknown state; their dedicated test processes used intentional halt containment after a flushed token-bound receipt. Those halts are neither natural retirement nor runtime `Complete`.

Issues 335 and 569 remain open pending one combined full gate. Current focused results: 56/56 owning host tests and 32/32 Architecture tests; static/ABI and warning ratchet passed. Some checks were cached or up-to-date and both SpotBugs test tasks were skipped, not passes. The controlled evidence does not qualify all B5, B6+, helper activation, operator custody/no-writer lifetime, original 20+20, G4/G5, P5/P6, T4 or vendor/JFR gates. The 45 historical full-build skips and three issue 752 `ENOSYS` shutdown failures retain their existing limits. One earlier intermediate preimage capture was missed; the original was retained and the lapse is documented in the checkpoint evidence.
