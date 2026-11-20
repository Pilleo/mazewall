# Repository review — detailed finding register

This register supports the [twenty-package fix order](2026-11-04-repository-review.md).
[Tracking](2026-11-04-repository-review-tracking.md) gives the exact existing or new
issue owner for every entry; new issues retain the longer source traces and regression
requirements. [Evidence](2026-11-04-repository-review-evidence.md) distinguishes actual
runs, reused outputs, historical receipts and unperformed qualification.

The [recursive follow-up](2026-11-04-repository-review-recursive.md#additional-findings-and-exact-ownership)
adds **PN-DEDUP and PRT-08–12** under existing owners910/816/888 and reconciles later
implementation/build/native evidence. Original IDs, aliases and dated probes below
are preserved; these six residuals do not create duplicate issues.

**Evidence labels:** **reproduced** means the specifically described host/component
behavior, not a native escape or passing production regression. **Source-confirmed**
means a connected input/interleaving and consequence were traced. **Gap/question**
requires qualification or a contract decision. Confidence is high for the stated
source conclusions unless explicitly limited. P1–P3 are relative to the named
artifact, not a claim every optional finding blocks a core-only release. No P0 was
established. Findings are open unless explicitly marked implemented or historical.

For readable exact source references, these literal prefixes are used below:

| Prefix | Source directory |
|---|---|
| `E/` | `enforcer/src/main/kotlin/io/mazewall/` |
| `P/` | `platform/src/main/kotlin/io/mazewall/` |
| `F/` | `profiler/src/main/kotlin/io/mazewall/profiler/` |
| `R/` | `portals/runtime/src/main/kotlin/io/mazewall/portal/` |
| `G/` | `portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/` |
| `H/` | `profiler/hotspot-agentless/` |
| `UP/` | Locked external module `github.com/inspektor-gadget/opentelemetry-ebpf-profiler@v0.0.202611-0.20260715114040-ef6cf29492c8` (revision `ef6cf29492c8a980f889052903fa2dd0c0765cc0`, from `H/dependencies.lock.json`) |

Line ranges describe inspected working-tree bytes, including pre-existing changes.
Aliases below share one finding/owner and must not be counted twice.

## W01 — shared platform boundaries

### PLAT-01

**P1, reproduced metadata/compiler defect; alias BPF-02.**
`P/platform/syscall/Arch.kt:312–314` marks AArch64 `renameat` unavailable; the resolver
and policy conversion omit its rule. Linux arm64 enables `renameat`, number **38**.
A default-ALLOW policy explicitly denying RENAMEAT still compiles to ALLOW for 38.
The independent 224-entry table audit found this mismatch; no native arm64 rename ran.
Correct the shared metadata, assert independently sourced numbers and compiled preset
verdicts, then qualify an owned AArch64 rename positive/denial control. Existing
recvmmsg/statx and x32 corrections remain credited.

### PLAT-02

**P2, reproduced arithmetic defect.** `P/platform/lifecycle/valueClasses.kt`'s
`Deadline` saturates addition in a way that treats legal negative `nanoTime` origins
inconsistently with subtraction-based consumers. A finite deadline can become
effectively unbounded or expire incorrectly. Use one wrap-safe monotonic arithmetic
contract; test negative origins, wrap boundaries, zero and bounded durations through
both construction and actual remaining-time consumers. This is an expanded residual,
not proof that ordinary current-host timeouts all fail.

### PLAT-03

**P1, source-confirmed descriptor identity loss.** `P/platform/socket/SocketManager.kt:98–103`
forgets both typed generations; `P/ffi/networking/SupervisorSocketUtils.kt:139–170`
sends the raw payload integer and remints the socket's current generation. After
close/reuse, a stale token can select a replacement resource without a simultaneous
race. Preserve original identities and owner/borrow protection through every attempt.
Regress stale payload/socket and retry races with zero dispatch, plus owned SCM_RIGHTS
success. Portal's existing borrow protection remains relevant; no real wrong-resource
transfer or guest-controlled exploit was executed.

### PLAT-04

**P2, source-confirmed cleanup contract defect.** `P/platform/fd/FileDescriptor.kt`
`closeResource` discards a native Error result and marks ownership closed even when
failure occurs before native dispatch. Cleanup can appear successful or lose its
retry owner. Preserve actual errno and distinguish pre-dispatch failure from an
attempted Linux close; **do not blindly retry a dispatched close**, whose integer may
already be reused. Inject both boundaries, assert exact failure and retained ownership
where valid, then check owned native success. Historical exception-only tests do not
cover the ignored result branch.

### PLAT-05

**P2, source-confirmed managed-memory validation gap.** The managed `prctl` path in
`P/ffi/internal/RealNativeEngine.kt` extracts segment addresses without the extent,
lifetime, accessibility and output-writability checks applied at other downcalls.
The JDK-only probe confirms address extraction itself supplies no such validation.
Validate command-specific managed buffers before dispatch, retaining explicitly raw
pointer contracts. Regress closed/short/read-only/wrong-thread segments with zero
native calls and valid controls. No native corruption experiment ran.

### PLAT-06

**P2, source-confirmed acquisition leak.**
`P/platform/seccomp/daemon/SeccompSessionHandler.kt` acquires an eventfd/shared arena
before fallible listener fcntl/buffer initialization. Constructor failure returns no
owner to close them. Make acquisition transactional, including eventfd-factory arena
rollback; retain primary/fatal cleanup ordering. Inject each later acquisition failure
and assert independent exactly-once cleanup with the existing wakeup seam.

### PLAT-07

**P2, source-confirmed race; native consequence unqualified.**
`P/platform/seccomp/daemon/EventFdCompletionWakeup.kt:47–59,78–88` checks an atomic
closed flag but does not serialize an admitted write with fd/arena close. An exceptional
profiler-completion callback can overlap retirement outside its job monitor. Closed
segment/EBADF is possible; a reused-resource write is a source-supported schedule,
**not observed corruption**. Serialize short signal/drain operations with close or
retain an admitted-operation lease. Use barriers before/after native validation and
an owned eventfd positive control; do not block the reactor joining arbitrary helpers.

### PLAT-08

**P1, freshly reproduced action-order defect.**
`P/platform/syscall/SeccompAction.kt:21–34` ranks TRACE above NOTIFY, while Linux gives
USER_NOTIF precedence over TRACE. `E/enforcer/api/PolicyDefinition.kt:55–89` consequently
loses the requested notification decision in both intersection orders. A permitted
ptracer may continue TRACE; without one TRACE normally returns ENOSYS. This is not a
bypass of independent filters. Correct the full shared order, then table-test every
UAPI action pair, KILL_PROCESS's sign bit, payload ties and explicit/default composition.
Existing rank tests encode the wrong winner. BPF-03 must consume the corrected order.

## W02 — effective policy and evidence construction

### BPF-01

**P1, freshly reproduced policy-planning defect.**
`E/enforcer/engine/FilterInstallationPlanner.kt:58–103` and
`E/enforcer/state/ContainerState.kt:68–95` lose initial explicit ALLOW exceptions under
a restrictive default. Real plan→publication evolution then skips a narrower allow-list;
unchanged ALLOW sets also hide LOG→ERRNO or stronger default escalation.
`E/enforcer/api/ContainedExecutors.kt:377–388` returns successful installation without
the missing filter. Track effective defaults/exceptions faithfully; skip only proved
equivalence. Regress actual state evolution, identical/superset controls and process/thread
merges, then an acquired benign native denial. Preserve archived stacking fixes; this
is their newly scoped residual, not a kernel-executed bypass.

### BPF-02

Alias of [PLAT-01](#plat-01), independently reproduced by the policy lane. One issue.

### BPF-03

**P1, freshly reproduced cumulative action-order defect.**
`E/enforcer/engine/FilterInstallationPlanner.kt:50–84`, `E/enforcer/state/ContainerState.kt:78–82` and
`E/enforcer/state/ContainmentStateRegistry.kt:100–108` use coarse `.priority`.
TRACE(7)→ERRNO(13) installs nothing because both are priority 4. Use the corrected
PLAT-08 canonical order everywhere, including default/state merges; preserve the
documented payload tie contract. Test explicit/default pairs through real publication,
both merge orders and unchanged controls. Successful syscall impact requires an
applicable ptracer; it was not exercised. Fixing composition alone leaves this defect.

### BPF-04

**P1, known-live value-boundary defect; raw mutation reproduced.**
`E/enforcer/api/PolicyDefinition.kt:23–40,183–190` stores mutable raw constructor/copy
inputs unless a particular caller freezes them. `E/enforcer/api/PolicyCompilationCache.kt:17–57`
retains those collections in keys. Mutating a map changes published policy answers and
key stability. Own all collections at the value boundary under the existing API migration
decision. Test raw/copy/Java views, all collection fields and cache identity. Normal
builder/canonical/owned snapshots are already frozen; installed kernel filters do not mutate.

### BPF-05

**P1, known raw-scope defect; accepted input reproduced, native consequence conditional.**
`E/enforcer/api/PolicyDefinition.kt:23–40`, `E/enforcer/api/ProcessPolicyBuilder.kt:30–52` and
`E/enforcer/api/PolicyBuilder.kt:217–246` permit process-typed raw/copy/base values with
worker filesystem intent. Validate before **any** process Landlock effect, not only
canonical import. Astra traced an ABI8 parked-sibling sequence where caller-domain
TSYNC can replace a narrower local Landlock domain while stale Seccomp/landlock state
deduplicates reinstallation. This uses invalid raw scope; **no additional valid-scope
supported-path bypass was established or executed**. Regress Kotlin/Java raw, copy,
active-empty and base rejection plus valid controls; qualify the no-new-Seccomp sequence
separately. ManagedRuntime already rejects a filesystem-bearing host baseline.

### BPF-06

**P2, source-confirmed manual-grant/error defect; mask corroborated.**
`E/landlock/Landlock.kt:491–518,585–595` leaves MAKE_REG/REMOVE_FILE in a regular-file
write grant, which Linux rejects; EINVAL is logged and installation can still report
Applied. Explicit path-open failures, including EPERM/EACCES, are also logged away
at `:424–430,480–488`. Derive rights from the acquired object and propagate required
grant failures with original errno. Test exact-file write success, forbidden sibling,
and open/add errors under every fallback setting. This causes lost grants/false success,
not broader access; prepared-resource grants already use the correct semantics.

### BPF-08

**P2, known-live fragment-contract defect; fresh host reproduction.**
`E/seccomp/BpfBuilder.kt:95–105,203–210` accepts a termination token even when a matched
path jumps to an outer ALLOW and its denial is unreachable. Validate fragment-local
control flow/label scope; test escaping labels and valid multi-branch fragments.
Current built-in inspections were not shown to exploit this; immutable structural
program verification is a separate, implemented safeguard.

### LL-01

**P2, documentation/qualification gap.** `E/enforcer/api/ContainedExecutors.kt:192–214`
overstates universal monotonic/order-independent Landlock behavior, and
`docs/internals/designs/enforcer/containment-design.md:517–523` says flags are always zero.
Current process enforcement uses TSYNC. Document baseline-before-local-filesystem-domain
requirements, including parked manually restricted siblings; qualify ABI8 controls.
Reuse BPF-05 and the platform-envelope owner. No separate TSYNC vulnerability issue.

### LIF-04

**P2, known-live receipt contradiction; host behavior reproduced.**
`E/enforcer/api/InstallationReceipt.kt:20–31,49–60` accepts
`installed=true, failure=sentinel` in `requireInstalled`. Reject recorded failure first;
keep irreversible stage progress distinct from overall outcome and preserve registration
cleanup. Test contradiction, inherited success, partial failure and exact error identity.
Inspected ordinary producers set failed receipts consistently; this is not a live
installer bypass or proof that public receipt construction attests kernel state.

### EVID-01

**P1, known-live imported-completeness defect; current-source reproduction.**
`F/api/ProfileDocument.kt:120–144` and `F/api/ProfilingCoverage.kt:94–106,185–192` accept
`complete=true` alongside dropped events/incomplete drain. Flipping only that bit permits
dedicated-worker policy conversion. Validate all persisted supporting facts in one
construction boundary; retain conservative incomplete/HotSpot rules. Mutate loss,
drain, path-quality and collector compatibility separately and test valid round trips.
This is internal contradiction acceptance, not a missing-signature allegation or a
demonstrated defeat of Portal's static baseline. It precedes EVID-03.

### EVID-03

**P2, known-live measured-evidence ownership gap; reproduced.**
`F/api/ProfilingResult.kt:28–45,71–90` and `F/api/BillOfBehavior.kt:87–166` pair independently
supplied behavior and coverage. Adding an unobserved path while copying complete coverage
produces a new grant. Bind immutable observed behavior/coverage and controlled transforms;
behavior-changing merges lose measured authority. Regress constructor/copy/direct BoB
conversion/export normalization and Java/Kotlin compatibility. Explicit operator policy
authoring remains supported; this is not authentication against hostile same-process code.

## W03 — owned execution and retirement

### LIF-01

**P1, source-confirmed and existing-output host-reproduced result loss.**
`E/enforcer/diagnostics/ContainmentViolationDetector.kt:115–117,205–246` can throw from
a matcher or exception accessor. `E/enforcer/internal/execution/OwnedInvocation.kt:144–163`
classifies inside its catch before settling the result; finally removes pending ownership
and completes `Finished(failure=null)`. Observed: unresolved result, FAILED domain,
Complete drain. `ManagedRuntime.callContained` can then wait forever after physical end.
Contain secondary diagnosis failure using fatal-first aggregation and settle delivery/end
before releasing ownership. Regress ordinary/fatal bodies, throwing matcher/accessor,
exact failures, stopped reuse and no stranded admission through the public owned runtime.

### LIF-02

**P2, source-confirmed and existing-output host-reproduced fatal loss.**
`E/enforcer/api/execution/ManagedRuntime.kt:313–337` retains nested containment exceptions
but not plain inline Errors. Catching an uncaptured inner LinkageError lets the outer
return success and the worker accept another body. Captured-context nesting is already
fixed. Apply the same retained fatal-first/quarantine path to every library-observed
inline fatal. Test uncaptured inner callables, caught-return/later-failure and a prior
ordinary retained violation. Arbitrary Errors swallowed before a library boundary remain
outside the claim. Expand nesting986, preserving historical captured-context acceptance.

### LIF-03

**P2, source-confirmed retention; existing-output registry growth reproduced.**
`E/enforcer/api/execution/ManagedRuntime.kt:55,208–224,351–378` keeps every fully retired generation in its strong
map. Each `OwnedDomain` retains policy/callback/classloader/resource/executor graphs.
Four joined failed generations remain four after Complete drain. Release only physically
retired, cleanup-successful heavy owners; preserve stale-token semantics separately.
Test repeated explicit recovery and callback/classloader reachability. Never LRU-evict
live or cleanup-failed generations. No heap-size or throughput result was measured.

### LIF-Q1

**P2 contract/qualification question, not a demonstrated native leak.**
`E/enforcer/api/execution/ManagedRuntime.kt:205–243` can fail rollback before domain registration; the preparation
exception retains cleanup failure, but later drain only aggregates registered domains
(`:351–378`). The mock probe reproduced Complete drain after that exception; it did not
own a real descriptor. Prepared-resource documentation expects retained rollback outcomes,
so the existing prepared-resource owner must qualify whether manager drain includes this
branch. Hold a genuinely acquired rollback owner before registration, then inject ordinary
and fatal cleanup failures; specify the observable terminal/retained owner before changing
the contract. Do not conflate this with the fixed registered-domain late-snapshot race.

### LIF-Q2

**P2 unresolved retirement contract.** Worker receipt closure at
`E/enforcer/internal/execution/OwnedDomain.kt:120–129` does not join the independent
`E/enforcer/internal/supervisor/SupervisorInstallationLifetime.kt:40–67` watcher or validation listener.
`E/enforcer/internal/supervisor/SupervisorInstaller.kt:128–138,193–250` requests stop;
the reactor closes later. Worker-death predicates can therefore precede listener cleanup.
Coordinator source review confirms this separation, but no native leak was proved and
the supervisor follow-up did not complete. Define either inclusion in owned drain or a
separate required application owner with observable completion. Barrier-hold watcher/listener
retirement after worker death and inject cleanup outcomes. Until qualified, Complete drain
must not be advertised as proof of every supervisor-owned thread's physical retirement.

## W04 — supervisor

### SUP-01

**P1, fresh current-channel reproduction with mocked I/O.**
`E/ffi/networking/SupervisorValidationChannel.kt:78–96,164–224` applies its installation
deadline to idle waiting for the next request. The listener loop closes a healthy
installation after the default 30-second gap, even with no pending notification.
Separate idle polling from handshake/partial-frame deadlines. Test repeated idle expiries
followed by valid requests on the same installation, owner stop and bounded incomplete
frames. Preserve the notification watchdog; permanent filters cannot simply be removed.

### SUP-02

**P1, source-confirmed composed-route defect.**
`E/enforcer/internal/supervisor/SupervisorNotificationMachine.kt:105–124` maps approved accept verdicts to Continue;
`E/enforcer/internal/supervisor/TraceeAuthorityExecution.kt:80–88` accepts Continue only
for Spawn. Approved accept/accept4 thus becomes EPERM. The forced InjectFd test bypasses
the failing composition. Align the existing kernel-mediated accept route, preserving
peer-output/EFAULT behavior and pointer-based open/connect refusals. Test actual verdict→
decision→execution composition and a fresh native positive connection/peer-output matrix.
Archived accept430 remains historical; current stale docs must not simply claim support.

### SUP-03

**P1, fresh pure extraction/planning reproduction; native effects unexecuted.**
`E/enforcer/internal/supervisor/SupervisorArgumentExtractor.kt:30–40` ignores openat2
size and narrows 64-bit flags. Size 0 or an unknown high flag combined with O_TRUNC becomes
a valid helper open. `E/enforcer/internal/supervisor/TraceeAuthorityExecution.kt:91–97,160–175` reconstructs a 24-byte
request, removing Linux's pre-effect rejection. Preserve full flags and validate caller
size/extensions before authorization. Test zero/short/extended input, high flags and zero
effect dispatch; later compare native errno and unchanged owned sentinel contents. This
is potential approved-file truncation, not a demonstrated path escape or actual write.

### SUP-04

**P2, current-pool/stub reproduction.** `E/enforcer/internal/supervisor/SocketHelperPool.kt:182–225`
keys capacity by Java thread. Fatal work plus failed reap retains the old helper, but the
executor's replacement thread gets a new helper. With active=1, the probe retained two
owners. Reserve capacity independently of thread identity until reap, or fail pool
admission after failed retirement. Regress the combined fatal/failed-close/replacement
case and successful retry. No actual uninterruptible kernel helper was created.

### SUP-05

**P2, source-confirmed cumulative retention.** `E/enforcer/internal/supervisor/SupervisorInstaller.kt:387–396,563–564`
appends every stack to an undrained global queue; `E/enforcer/internal/supervisor/SupervisorDaemonManager.kt:91,136–145,212–218`
retains/copies all output. Bounded concurrency does not bound historical heap growth.
Remove test-only production retention or bound an owned diagnostic sink by count/bytes,
including individual lines and drop accounting. Drive beyond the bound and verify a
useful recent failure tail across retirement. No time-to-OOM claim is made.

### SUP-06

**P3, source-confirmed typed-diagnostic defect.**
`E/enforcer/internal/supervisor/SupervisorReplyInterpreter.kt:126–134,174–183` discards ADDFD errno;
`E/enforcer/internal/supervisor/SupervisorReplyEffect.kt:63–70` supplies generic EIO. Preserve ENOENT as NotificationGone
and other errors as their actual native outcome. Inject ENOENT/EACCES/EINTR exhaustion;
assert one descriptor disposal and no fallback SEND. Reply handling remains fail-closed;
this is not a duplicate-response bypass.

### BUILD-RUN

**P1 current qualification blocker; reproduced failure, initiating cause unresolved.**
The only full build failed `OwnedScopingNativeTest` during `prepareSupervisor`, before
authority assertions. `E/enforcer/internal/supervisor/SocketHelperPool.kt:61–74` exceeded a shared five-second
job budget; `SocketCleanup.await` threw, daemon exited, and readiness failure followed.
The method lasted 13.3s; the generic “within 30s” diagnostic is not elapsed-time evidence.
Retain per-helper completion/cleanup/scheduling evidence in a focused owned investigation.
Neither timeout inflation, retry-until-green nor a permission guess is a correction.
The earlier admission-capacity test issue is source-fixed and did not cause this failure.

## W05 — USER_NOTIF profiler

### PN05-01

**P2, source-confirmed kernel/error classification; alias PN-E1.**
`P/platform/seccomp/daemon/UserNotifReceive.kt:50–56` treats canceled RECV ENOENT as fatal;
`P/platform/seccomp/daemon/SeccompSessionHandler.kt:143–153` retires the listener still needed by other filter users.
Both Astra reviews agree on benign repoll with one reader, fresh POLLIN and one RECV per
observation. O_NONBLOCK alone is not the guarantee, but its absence from ioctl behavior
does not establish a blocking defect here. Regress poll→cancellation→ENOENT→fresh poll→
surviving request and hard-error controls. No native schedule or historical752 causality
was established.

### PN05-02

**P1, current-source/session reproduction; alias EVID-02.**
`F/api/Profiler.kt:94–127` intercepts only three executor methods. `submit(Runnable,T)`
and both forms of invokeAll/invokeAny delegate directly, bypassing collection; a fresh
session then reports a complete empty snapshot (`F/api/ProfilerSession.kt:159–179`). All five
omitted variants ran in the isolated probe. Route every admission through one installation
boundary while preserving timeout/cancellation/results. Test a fresh executor per overload,
installer failure preventing body entry, positive observations and Java callers. Warming
the worker first hides the defect.

### EVID-02

Alias of [PN05-02](#pn05-02), independently checked by the evidence lane. One issue.

### PN05-03

**P1, known-live interrupted-workload ownership defect.** `F/api/ProfilerService.kt`
does not apply its timeout cancellation/retirement discipline when the calling thread
is interrupted during its wait. The live workload can outlast the lost caller handle.
Extend existing981: retain the original workload owner, request cancellation and report
physical non-retirement honestly. Test caller interruption with an interrupt-resistant
held body and same-owner cleanup. A notification's 30-second deadline does not retire an
arbitrary Java workload.

### PN05-04

**P2, source-confirmed ACK-state defect.** `F/internal/engine/ProfilerAckOutcome.kt`
lets a concurrent pass-through request clear an already published event's pending ACK
state. That unrelated request is not the published event's acknowledgement. Preserve
the pending event through pass-through completion; regress event→pass-through→ACK and
late/failure/terminal interleavings, with exactly one authorized reply per notification.

### PN05-05

**P2, known-live native-arena cleanup gap.** `F/internal/ProfilerTraceListener.kt`
startup and fatal cleanup can exit before independent arena disposal; existing401 owns
it. Use transactional startup and independent terminal cleanup, preserving original
fatal identity/suppression. Inject first-thread/start and cleanup failures, assert every
acquired resource is either closed or retained. The earlier session-level fatal ownership
fix remains present; it does not cover this listener acquisition boundary.

### PN05-06

**P2, source-confirmed readiness propagation defect.** The same listener's rejected or
failed handshake releases its startup latch without delivering failure to the installer.
Reader termination alone is not successful installation readiness. Publish a typed
startup outcome through the existing internal seam; test rejected handshake, invalid
TID/read failure and successful readiness with zero body entry on failure. Coordinate
with401's resource rollback rather than adding a public callback API.

### PN-E2

**P2, conditional source-confirmed pre-ACK cycle; alias PN05-07.** On Linux HotSpot,
an unwarmed, non-archived bootstrap-only class in an appended **directory** outside the
daemon's canonical java.home/classpath noise roots is opened in `_thread_in_vm`.
`F/internal/ProfilerTraceListener.kt:436–480,523–529` waits for that registered target's
stack before ACK; the target needs the daemon reply before becoming handshake-safe.
OpenJDK25+36's directory loader and handshake chain establish this schedule; 22+36's
directory/handshake boundary was also checked. ZIP loading and ordinary FFM use different
transitions and do not establish the same cycle.

Two Astra reviews independently reconciled this condition. The separate daemon deadline
can settle it as failed class loading/incomplete profiling: **not a proven permanent
JVM hang or guaranteed exactly-30-second bound**. Reject an unsupported configuration
before irreversible install, or explicitly support/qualify its noise/stack contract.
Use a fresh JVM/unfiltered success control, stack-enabled/disabled comparison and the
registered-TID/publication/ACK/reply timeline. No runtime reproduction ran; this is not
the cause of issue752's current stack-disabled cold-network case. A Future timeout or
prewarming known listener classes does not remove the target-state cycle.

### PN-RETENTION

**P2, known session-wide retention debt.** `F/api/ProfilerSession.kt:32–36` retains
unbounded event/stack/cache stores, including cumulative CopyOnWriteArrayList copying.
Existing910 owns bounded retention and honest loss. Queue bounds and Tier E budgets do
not solve this separate owner. Qualify overflow/drop accounting and retirement under a
long-lived bounded session; no measured throughput regression was produced.

## W06–07 — Portal process and generated contracts

### PRT-01

**P1, source-confirmed baseline gap; independent compiled-policy corroboration.**
`portals/worker/src/main/kotlin/io/mazewall/portal/worker/PortalWorkerMain.kt` installs a
static process baseline that allows kill/tkill/tgkill, both queued-signal calls and
pidfd_send_signal on AMD64/AArch64; there is no signal-target inspector, and Landlock's
scoped field is zero. A compromised same-credential worker can attempt cross-process
signals subject to PID visibility/DAC/outer controls. Add a dedicated worker IPC-signal
boundary while preserving JVM coordination and required intra-process signals. Qualify
harmless owned-target signals, self/JVM positives and unsupported-kernel rejection.
No signals were delivered; generic ptrace/parent-memory access was not inferred.

### PRT-02

**P1, source-confirmed replacement ownership loss.** A broker-close race after replacement
acquisition in `R/ProcessBroker.kt` can fail cleanup before publishing the replacement
into any retryable owner. The ordinary startup registry cannot recover a local owner
already discarded. Keep replacement ownership continuously reachable across rejection,
publication and failed close. Barrier-control close versus spawn and failed first reap;
require no owner loss, bounded incomplete close and successful same-owner retry. This
is independent of the planned restart-budget feature and does not prove a live orphan.

### PRT-03

**P2, source-confirmed pre-validation blocking.** `R/PortalChannel.kt`'s
`openGrantedRead` opens an allowed-root FIFO for blocking read before verifying regular
file type. With no writer it never reaches rejection. Acquire nonblocking/no-follow
ownership appropriate to the existing contract, inspect the acquired object and reject
nonregular types before reading. Test a no-writer owned FIFO with bounded completion,
regular-file success and cleanup. No native FIFO experiment ran in this review.

### PRT-04

**P2, expanded known deadline defect.** `R/ProcessBroker.kt:481–488` uses at least five
seconds for maintenance even with a 100ms close budget; `:799–817,920–962` adds fresh
per-worker reap/pump allowances. Thread one absolute deadline through all waits,
retaining unfinished owners on expiry. Test held maintenance and multiple delayed slots,
then release/retry. Existing646's short-budget late-launch fixture misses these waits.

### PRT-05

**P1 for the generated-contract release, reproduced existing-handler defect; alias PCG-11.**
`R/PortalRuntime.kt:61–75` handles methods by name rather than Object identity/signature.
Accepted `hashCode(int)`, `toString(int)`, `equals(String/int)` never reach the guest:
null-unboxing failure/null/identity-false result respectively. Codegen emitted compilable
overloads; actual handler bytecode reproduced zero delegate entries. Special-case actual
Object methods only. Test runtime-produced proxies and worker/delegate counts, original
Object controls, closed state and singleton gates. No end-to-end worker run was performed.

### CG01

**P1, known-live, freshly reproduced generated compilation failure; alias PCG-01.**
`G/PortalStubGenerator.kt:265–291,440–445` emits byte[] as Array<Byte> and mutable List
declarations with read-only decoding. Align declaration/codec types once. Compile and
round-trip unmodified Java/Kotlin emitted array/list contracts; do not hand-patch benchmark
generated code. Foundation for CG02/04/06.

### CG02

**P1, known-live, fresh accepted-shape reproductions; alias PCG-02.**
`G/BoundaryTypes.kt:103–107,171–180,218,245` omits complete generic/nullability/constructibility
information. Abstract DTOs and nullable members can be accepted then fail emitted compilation;
generic members lose type arguments. Validate the full position-aware shape before output,
including constructors/access/result-only Unit/capability placement. Accepted examples
must compile/round-trip; rejected shapes fail with field paths. Trusted getters/constructors
are not thereby proven pure. CG03 consumes this model.

### CG03

**P1, known-live schema identity defect; fresh collision reproduction; alias PCG-03.**
`G/PortalManifestGenerator.kt:46–63` fingerprints top-level names, not complete DTO/wire
shape. Same-named int-versus-long DTOs yield differing codecs with identical schema ID.
Hash one versioned canonical complete schema from CG02; preserve method IDs/valid bytes
and plan migration explicitly. Regress same-name shape changes/equal-schema controls
and actual mixed host/worker refusal. Artifact measurement is a separate boundary.

### CG04

**P1, known-live, fresh malformed-dispatch reproduction; alias PCG-04.**
`G/PortalStubGenerator.kt:148–153,200–228,440–451` omits full body/capability consumption
and collapses duplicate map keys; `R/PortalCodec.kt:74,108–116` accepts noncanonical Boolean
and malformed UTF-8 replacement. Invalid/trailing/empty-shape input reaches service entry.
Reject before invocation/result delivery with distinct malformed-wire classification.
Run compiled malformed request/result matrices with entry counters, including Unit and
extra capabilities. Do not label every trusted constructor exception wire corruption.

### CG05

**P1, known-live source ordering defect; aliases PCG-05/PRT-06.**
`R/ProcessBroker.kt:297–311` returns the worker before generated decode; ordinary malformed
reply failure then misses checkout retirement and singleton state loss. Hold the exact
checkout through CG04 validation. Use a real child with matching header/malformed body,
held decoder and competing checkout, and prove identity/reaping/non-reuse. Source review
has not supplied that native reproduction. P03 must reuse this owner rather than replay work.

### CG06

**P1 generated resource bound, known-live and freshly reproduced; aliases PCG-06/PRT-07.**
`G/PortalStubGenerator.kt:336–363` and `R/PortalCodec.kt:48–66` allocate/traverse before
transport limits. Emitted responses included 16,385 list elements and a string above the
8MiB frame limit. Use the planned shared bounded writer with checked aggregate counts/
arithmetic before growth. Small-budget/counting-iterator, boundary and golden-byte tests
must cover requests/replies and scratch peaks. Do not deliberately OOM; optimize only
the corrected writer under P10.

### CG07

**P2, known-live output-loss defect; fresh reproduction; alias PCG-07.**
`G/PortalCodegenPlugin.kt:157–183` rejects equal destinations but permits ancestor overlap.
Publishing child then parent deletes the child's successful output. Cleanup at `:111–112`
can also mask the primary failure. Validate disjoint destination/staging ownership and
aliases before mutation; regress both orders, symlinks and rollback failures using old-output
sentinels. Default sibling outputs are unaffected.

### PCG-08

**P2, new bundle cache-contract defect attached to existing portable-packaging162.**
`G/PortalWorkerBundleTask.kt:19–20,31–61` declares @Classpath but observes raw ZIP bytes
and basenames. Rename/timestamp-only repack produces different bundles despite classpath
equivalence; direct task-action output sensitivity was reproduced, not a live cache hit.
Declare basename-sensitive raw inputs, preserving relocation and signed JAR bytes.
Test real TestKit repeated/relocated builds for rename/repack and byte-identical restoration.

### PCG-09

**P2, reproduced producer/runtime incompatibility; expand P02.** The same task accepts
257 distinct tiny JARs; `R/WorkerBundleMaterializer.kt:125–135` correctly rejects the actual
verified bundle at its 256-entry limit. Aggregate 64MiB/compressed limits likewise are
missing at the producer. Reuse intrinsic format bounds before copying/publication and
stream hashes. Actual portalCheck must reject over-limit inputs and accept consumable
exact-limit controls. This is not runtime integrity bypass or a heap/cgroup guarantee.

### PCG-10

**P2, fresh fatal-identity reproduction.** `R/PortalDeclaredExceptionMapper.kt:22–30`
uses broad runCatching/recoverCatching; both allowed constructors can throw a sentinel
Error which disappears behind the remote exception. The generated/stateful error path
then cannot observe the host fatal. Fall back only for unsupported constructors; unwrap
and propagate actual constructor failures with remote context. Test exact fatal identity,
no second constructor after fatal and a generated call through the stateful gate.
The class allow-list remains intact; arbitrary remote class loading is not claimed.

## W08–09 — Tier E

### TE-01

**P1 optional-native-agent source defect.** `profiler-native/src/agent.cpp:754–774,1020–1055,1256–1260`
calls Java methods during scope destruction with an original native exception pending,
and does not check between JNI calls. Preserve the pending application exception and
avoid prohibited calls on exceptional exit; invalidate uncertain attribution instead.
Use -Xcheck:jni success/failure matrices across supported bindings, exact errno/exception
and inactive-marker assertions. JNI contract violation is established; crash/replacement
was not executed. Required before Plan B capture reuse.

### TE-02

**P1 source-confirmed C++ exception-safety defect.** `profiler-native/src/agent.cpp:754,904,1109` declares
allocating capture/context paths noexcept; map/vector growth at `:725–789,1185–1201` can
throw bad_alloc and terminate the target. Budget refusal does not make STL allocation
infallible. Establish an exception-safe admission/rollback boundary reachable without
crossing uncaught noexcept; call the original native operation exactly once. Inject
allocation failures and verify capture loss, unchanged application result and no ref/budget
leaks. No resource exhaustion was attempted.

### TE-03

**P1 source defect, production-header state witnesses reproduced.**
`profiler-native/src/agent.cpp:1396–1397,1904–1912` and
`profiler-native/include/symbolization_generation.hpp:36–51` let unrelated ClassPrepare
or a failed second begin finish another replacement; begin's load/store is not exclusive.
Keep capture unproven until its actual owner completes, retaining overlapping external
invalidation. Regress paused transform/unrelated class/competing callers before/during/after
capture. Wrong real JVM frames were not reproduced. New residual follow-up preserves
resolved834 rather than erasing its historical acceptance.

### TE-04

**P2, source-confirmed producer ordering.** Invocation N+1 can publish before N;
`profiler-native/src/agent.cpp:1669` uses the last appended ID as watermark, causing
`F/internal/attribution/TierENativeSnapshotAdmission.kt:40–43` to reject valid opt-in live
data and latch failure. Define maximum-versus-contiguous-prefix semantics and use the
correct cumulative owner. Reverse-order native publications must pass the real Kotlin
consumer. Terminal-only rejection and historical71/72 causality are not established.

### TE-05

**P1, known-live enclosing cleanup loss.**
`F/tierE/daemon/TierEKotlinDaemon.kt:452–485` clears active ownership before fallible
finish and suppresses failure; `F/internal/attribution/TierECollectorLoop.kt:136–142`
disables retry before session.close. Retain a closing owner, independent cleanup and
truthful incomplete terminal; block replacement until physical retirement. Inject
detach/counter/watch/unmap/join failures through the whole chain. The ring reader's
existing retryable unmap is sound but insufficient when outer owners discard it.

### TE-06

**P2, source-confirmed end-to-end deadline failure.** `F/internal/attribution/TierECollectorService.kt:71–99`
returns after bounded join, but `F/internal/attribution/TierECollectorRuntime.kt:113,145` then calls metrics
under the loop monitor held across interrupt-resistant poll (`F/internal/attribution/TierECollectorLoop.kt:40–42`).
Expose a last completed snapshot without awaiting that worker; retain cleanup ownership.
Test runtime finish against a held poll that ignores interruption, before releasing it,
then exact retirement. Expand cleanup405; a service-only interruptible-latch test misses it.

### TE-07

**P2, known-live shutdown admission defect.** `F/tierE/daemon/TierEKotlinDaemon.kt:439–443` sets a stop
flag without closing command-state admission; `F/tierE/daemon/ControlSessionMachine.kt:82–96` can revive
Terminated. Close admission under the service lock before cleanup and make terminal
events absorbing. A queued ATTACH after direct shutdown must never acquire an engine;
test cleanup failure and every terminal reducer event. Depends on TE-05 retained ownership.

### TE-08

**P2, known-live retry identity defect.** `F/internal/attribution/TierECollectorRuntime.kt:129–137,173–176`
keys in-memory finish by null plus closure, returning cached A for changed/wrong-session B
before B validation. Bind immutable snapshot/session/closure identity before cache hit;
reject incompatible retries without redoing cleanup. Test same/changed/mutated/cross-overload
requests. First finalization does validate identity; no external policy bypass is asserted.

## W10–12 — HotSpot, assurance and reproducibility

### HS-01

**P1 optional decoder defect; bounded component reproduction.** Locked upstream
`UP/interpreter/hotspot/instance.go:340–377`, `UP/interpreter/hotspot/recordingreader.go:27–37`
and `UP/interpreter/hotspot/unsigned5.go:61–84`
read an unterminated line table with unbounded 256-byte refills and full-buffer copies.
`H/patches/profiler-epoch-memory.patch:52–101` retains that path; a per-read 1MiB cap
does not bound cumulative work. The finite probe decoded262,144 entries with134,348,800
implied allocated bytes. Bound total extent/entries and refill work, propagate exhaustion
without partial cache success, and check cancellation where supported. Test the real
method reader and retirement. No live target/OOM/native attach ran.

### HS-02

**P2, expanded required-test-selection gap.** `H/Makefile:14–15` and CI root `go test ./...`
do not execute dependency-overlay tests. The experiment's unselected manual target also
misses label-bound/observed families. A tiny replacement-module canary proved root success
with a failing dependency test. Extend existing293's canonical required lane to select
actual modified dependency packages; fail the real workload with an authored canary and
record names/skips. Root race/scanner integration is already implemented and remains credited.

### HS-03

**P2, source-confirmed binary/source provenance mismatch.**
`H/internal/tooling/assets.go:158–167,198–244` stamps current identity on an arbitrary
executable; `H/internal/tooling/distribution.go:354–431,473–497` selects module sources by path without matching
linked versions/sums/replacements. Bind actual executable/build/source identities before
publication, using HS-04 for local replacements. Reject stale/wrong-profile binaries and
same-path different module graphs; accept a genuine matching stripped release. Checksums
alone prove bytes, not which source built them. Historical matching bundle173 stays valid
for its recorded scope; no current packaging acceptance ran here.

### HS-04

**P2, source-confirmed preparation verification gap; incidence unmeasured.**
`H/internal/tooling/prepare.go:88–103,260–282,332–399` reuses markers plus three native
artifacts, not copied Go source/module content. A changed replacement tree can retain old
provenance. Verify a relevant content inventory or regenerate an owned fresh tree; preserve
unrelated cache work. Isolated changed/extra/missing source/go.mod inputs must reject reuse,
with unchanged positive controls. Existing native-artifact inventory715 was correctly fixed.

### HS09-01

**P1, fresh cross-language replay defect.** `hotspot-bridge/wire/decoder.go:158–162`
rejects legal event/enrichment rows before ready, although collector startup and Kotlin
permit them after start. An active target can therefore fail attachment. Admit bounded
validated pre-ready occurrences while withholding Ready and requiring it before success.
One strict2/observed3 corpus must cover early unavailable/recovery, no-ready summaries and
terminal-before-event negatives. Replay establishes disagreement, not fresh native timing.

### HS09-02

**P2, fresh strict-Kotlin identity reproductions.** `F/internal/hotspot/HotSpotProtocol.kt:40–76`
and `F/internal/hotspot/HotSpotEvidenceAssembler.kt:110–145` omit strict enrichment provider/epoch binding.
Wrong patch or checked epoch still completes with correlated evidence; Go rejects it.
Validate negotiated provider and bounded per-occurrence strict identity, keeping valid
zero even epoch and observed/schema1 differences. Share wrong/absent/odd/mismatch cases
across both consumers. Public policy eligibility remains false.

### HS09-03

**P2, fresh false-drain/completion reproductions.** `F/internal/hotspot/HotSpotWireMapper.kt:65–84` and
`F/internal/hotspot/HotSpotProtocol.kt:80–86` accept strict summaries missing selected accounting or explicitly
recording a nested native finalization error. Apply common strict2/observed3 finality,
binding and overflow-safe conservation validation before healthy completion. Mutate
one field at a time through real lifecycle EOF/exit/joins. This does not bypass the
public incomplete-policy guard or establish complete all-source coverage.

### HS09-04

**P2, fresh current-source fake-process lifecycle reproduction.**
`F/internal/hotspot/HotSpotSessionDriver.kt:51–55` stops the timer in Finishing, so
`F/internal/hotspot/HotSpotCollectorWorkers.kt:50–55` cannot enforce the automatic stop deadline. A silent
child remains alive until a later caller pulse/close. Keep autonomous ticks through
Finishing and invoke existing bounded abort. Test session/stop expiry without caller
finish/snapshot activity, then exact retained cleanup. No OS child/native capture was used.

### HS09-05

**P2, fresh wire-conformance reproduction.** `F/internal/hotspot/HotSpotWireReader.kt:88–138` accepts last-value
duplicate JSON keys, whereas Go rejects decoded duplicates. Contradictory policy/provider/
PID fields disappear from Kotlin's canonical diagnostics. Reject duplicates before object
construction within existing byte/depth limits. Share escaped/nested/both-order negatives
and unknown-extension positives. No new parser dependency or policy-promotion claim.

### BUILD-01

**P1 optional/publication closure defect, source-confirmed.** Catalog Kotlin2.4.21 conflicts
with Koog's strict ordinary compiler/test locks still at2.4.20-Beta1; Spring retains old
stdlib locks, and four independent consumer metadata sets approve no2.4.21 artifacts.
Default build excludes those modules. Complete the scoped upgrade across actual producer
locks then reviewed consumer hashes without weakening strict verification. Run the exact
optional CI producer/consumer gates and missing-checksum negatives. No fresh optional
Gradle resolution ran, and not every old transitive Spring lock necessarily fails identically.

### BUILD-02

**P2, source-confirmed CI edge omission.** Root `build.gradle.kts:1080–1178` attaches
supported-surface, Kotlin ABI and topology checks to check; required CI runs unitCheck,
kernelCheck/assemble and narrower fixtures, not these current-product gates. Select the
existing owners in a required host lane. A copied-workflow selection check and intentional
ABI mismatch must fail before publication. The local full build did execute these actions;
that does not repair CI selection. Preserve the six-literal-success publication join.

### TEST-01

**P2, source-confirmed intended-test inventory omission; alias BUILD-03.**
`enforcer/build.gradle.kts:236–270` and `profiler/build.gradle.kts:848–879` retain retired
package globs, excluding intended policy/BPF/evidence classes and tests. Update exact
patterns/exclusions without widening into kernel/property workloads or reducing floors.
Require an unmatched-intended-class negative and inspect actual serial PIT class inventory.
No PIT run occurred; a percentage over another slice is not evidence for this slice.
Platform's earlier selector fix is present.

### TEST-02

**P2, reproduced copied assertion-envelope defect; alias BPF-07.**
`enforcer/src/integrationTest/kotlin/io/mazewall/seccomp/SeccompDifferentialVerdictTest.kt:308–320`
throws “child survived” inside the catch that accepts IllegalStateException. Normal
return, setup failure or timeout can therefore count as kill success. Separate unexpected
success from expected outcome handling; require owned child readiness and discriminating
exit evidence. Deliberately surviving, pre-install-failing and timing-out controls must
fail qualification. No KILL_THREAD native experiment ran; timeout is not physical death.

### TEST-05

**P2, source-confirmed ABI-oracle cache gap, not an observed ABI mismatch.**
`platform/build.gradle.kts:57–85` declares only the C source, not compiler/system headers/
environment, and the comparator lacks an explicit binary input. Old host observations
can survive changed ABI inputs. For this tiny oracle, rebuild and bind the binary to Test,
or fingerprint effective native inputs. A disposable header/compiler change must invalidate
both observations. Today's independently rebuilt C comparison passed all62 values.

### BPF-09

**P2, known-live missing-verdict oracle omission; alias TEST-06.**
`enforcer/src/integrationTest/kotlin/io/mazewall/seccomp/SeccompDifferentialVerdictTest.kt:72–90` puts simulator null in neither allow nor deny.
A source-equivalent no-RET partition reproduced the omission. Require a verdict with
input/disassembly before classification; verify no-RET fails and valid benign controls
still reach native assertions. Structural validity narrows normal triggers but cannot
justify silent disappearance from a differential oracle.

### BUILD-04

**P2, source-confirmed buildSrc selection gap.** Root buildLogicCheck selects Java
verificationActionsTest, while the three Kotlin JUnit convention/lifecycle cases remain
on buildSrc:test (`buildSrc/build.gradle.kts:65–81,240–246`). The dependency points test→
Java verifier, not the reverse. Make the explicit exhaustive bridge reach all existing
tests once, preserving bootstrap isolation. A failing copied Kotlin case must fail the
bridge and all three observations must appear. Extend140/181; the proposed smoke/full
optimization is not approved implementation and must preserve this coverage.

### BUILD-PROVENANCE

**P2 release reproducibility debt, not compromise evidence.** All52 workflow actions use
mutable tags; the release image uses a mutable JDK tag and unpinned apt resolution.
Record/pin approved action/image/toolchain inputs before reproducible-release claims.
Attach the requirement to ARCH-04/05's exact-artifact promotion contract. No credential
handling, trust changes or hosted publication was performed. Intentional no-scan security
jobs/JitPack package availability are not vulnerability-scan or native-test evidence.

## W13–15 — adapters, auxiliary profiling and demos

### SPR-01

**P1 affected Spring fatal contract, current-source framework reproduction.**
The JDBC transaction failure bridge in `integrations/spring/mazewall-spring-boot` preserves
an Error after successful rollback, but a rollback exception replaces it before owned
generation classification. Retain original fatal identity with rollback failure suppressed
and UNKNOWN transaction outcome. Test sentinel Error plus failed rollback through the
actual transaction template/owned boundary, non-reuse and retirement. Expand558; its
earlier successful-rollback repair is present.

### SPR-02

**P2, source-confirmed SmartLifecycle completion mismatch.**
`integrations/spring/mazewall-spring-boot/src/main/java/io/mazewall/spring/DomainLifecycle.java`
reports stopped/runs callback after incomplete domain drain. Spring can destroy dependencies
still used by workers; even withholding the callback cannot defeat the framework's phase
timeout. Define a retained application owner for **runtime and dependencies** after budget
expiry, then align callbacks/state. Test a real context with a held worker and observable
dependency destruction across timeout/release. Manual external-owner demos are a separate,
already narrower contract; no forced thread termination is proposed.

### SPR-03

**P2, source-confirmed retained-S0 manager-selection gap.** S0 validates the configured
default manager, but Spring may choose a qualified/named one for the actual advised method.
Validate effective selection before execution and keep single-manager/readiness rules.
Test qualified manager substitution through actual advisor dispatch. Expand838; S2's
qualified-manager correction and classloader repair remain credited.

### KD-E3

**P2, reproduced current-source structured cancellation error defect.**
`integrations/koog/src/main/kotlin/io/mazewall/koog/PortalToolAdapter.kt:39–53` invokes
throw-capable cancellation from invokeOnCancellation. A real OwnedPortalInvocation on a
virtual thread inside an interruptible channel can throw ordinary/Error during interrupt.
Coroutines1.10.2 sends that failure to an uncaught handler instead of the structured caller;
ThreadInvocationAwait preserves it correctly. Move request/merge handling to the wait
boundary, request once, retain fatal identity and result/end independence. Regress no
uncaught-handler event plus before/during-submit/late-result races. Ordinary cancellation
races were sound. Expand P04; no new adapter framework or native bypass claim.

### EVID-04

**P2, reproduced generated-DSL injection/escaping defect.** `F/api/BillOfBehavior.kt:155–185`
inserts observed paths directly into Kotlin quotes. A valid filename containing `$`, quotes,
backslash/newline produces interpolation or invalid/different code when the operator compiles
the snippet. Emit Kotlin-aware literals, including escaped `$`. Compile special-character
outputs and compare exact grants with toPolicy, asserting no interpolation side effect.
Collection itself does not execute filenames; subsequent compilation/execution is required.

### EVID-05

**P2, ordinary-javac reproduced test-seam exposure.** `F/api/BillOfBehavior.kt:94–100,147–153`
and `F/api/ProfilingResult.kt:92–110` assign unmangled JVM names to internal incomplete-policy
overrides. Java can call them without reflection despite the documented absence of a public
override. Hide/move test-only seams subject to supported-caller compatibility, migrate tests
and require an independent Java negative-compilation control. This is API hygiene, not a
security boundary against reflection or explicit policy authoring.

### EVID-06

**P2, current helper reproduced late-descendant leak.** `F/collector/StraceCollector.kt:139–155`
snapshots descendants, signals, then looks again through an exited/reparented root.
A TERM hook's new child survived termination and was separately reaped by the probe owner.
Establish an owned inherited cancellation domain before launch; retain/report incomplete
retirement. Test hook-created/TERM-resistant children, parent-first exit and interruption.
Do not signal ambient groups or invent PID ownership. Earlier captured-child cleanup is fixed.

### EVID-07

**P2, reproduced bounded classifier/completeness defect.** `F/api/UringOp.kt:49–105`
maps unknown operations to Read; clean FALLOCATE evidence becomes complete read-only behavior.
Unknown/new opcodes must default incomplete/unenforceable until operands and enforcement
are representable. Test FALLOCATE/FTRUNCATE, unsupported network and future tokens, preserving
known writes. Public recorded logs already force incomplete drain and generic live eBPF is
unimplemented; no current public recorded-log policy bypass was demonstrated.

### EVID-08

**P2, reproduced internal/deprecated iterative outcome defect.**
`F/iterative/IterativeProfiler.kt:63–92` returns policy identically for Converged and Exceeded.
Twenty failed iterations produced ordinary success-shaped policy without any successful
workload. Return/throw distinct exhaustion with partial diagnostics; test no-progress,
exhaustion, convergence and unrelated errors. This is not a fallback tracer or core release
blocker, and no external production caller was established.

### EVID-09

**P2, reproduced diagnostic failure loss.** `F/triage/DiagnosticTriageRunner.kt:87–105`
ignores dmesg exit/stderr then filters all text. An EPERM exit becomes empty “evidence.”
Keep typed probe failure distinct from successful empty output; bound status/stderr and
retain fatal identity. Test denial, read failure/timeout, empty and matching success.
No containment authorization changes follow from this diagnostic correction.

### KD-01

**P2, known qualification gap.** The agent SSRF comparison depends on actual metadata
routing rather than an acquired deterministic local control. Existing053 owns a repeatable
success/refusal pair and honest offline presentation. Qualify the exact supervised route
with a fixture endpoint; do not interpret unavailable external infrastructure as denial.
Current HTTP compatibility corrections remain credited.

### KD-02

**P3, known presentation drift.** Agent demo README describes model/prompt-injection
behavior while the executable feeds deterministic mock tool calls. Label those inputs
synthetic and state the actual boundary exercised. Update existing944's presentation
acceptance; this is no evidence about model robustness or a new security defect.

### KD-E1

**P2, confirmed sibling-demo cleanup defect; logical-owner sequencing reproduced.**
PortalExample, PortalDocumentAgent, DocumentAgent, ThreadReadExample and two presentation
fixtures delete synthetic roots in outer finally after exceptional/incomplete close,
skipping physical-retirement checks and losing application retry access. Keep the owner
reachable; delete only after canonical and physical completion on both success/failure.
Reuse OutlineFileApplication's corrected pattern. Test held cleanup/root retention then
same-owner release/deletion and original-error suppression. Existing actual-file cleanup
is fixed; no operator-document loss/live-worker JAR failure was reproduced.

### KD-E2

**P2, source-confirmed post-start acquisition window; synchronous submission failure demonstrated.**
`demos/agent-sandbox-demo/src/main/kotlin/io/mazewall/demo/agent/OwnedProcessRunner.kt:52–68`
starts a process then creates/submits its pump outside rollback. Thread-factory ordinary/
fatal failures can escape before a future or process owner is returned. Guard provisional
ownership immediately after start; independently close streams/executor and boundedly
reap owned children, preserving fatal-first errors. Regress injected construction/first
submission failures with a real bounded child and independent test cleanup. No native-thread
exhaustion or process leak experiment was needed to establish the acquisition gap.

## W16–19 — support contracts, documentation and measurement

### ARCH-01

**P2, support-vocabulary decision/gap.** `docs/public-api.md:5–42` promises hidden platform
types/nontransitive dependency, but enforcer's api dependency and supported Java fixture
require Syscall/SeccompAction/Arch/SandboxedPath; public HotSpot targets require Pid.
Choose a bounded supported value vocabulary and enforce that inventory in the existing
surface guard. Preserve typed identities and October's necessary api dependency. Validate
independent Java/Kotlin and unchanged compiled consumers. A facade migration needs explicit
approval; treating all platform implementation classes as stable is not the only choice.

### ARCH-02

**P2, Portal binary-compatibility gate gap, not a reproduced linkage failure.** Published
runtime/worker/plugin lack ABI snapshots while their config and DX plan promise constructor/
default-bridge compatibility. Classify supported application/generated bridges, extend the
existing ABI mechanism and run unchanged compiled Java/Kotlin consumers against candidates.
Wire those gates into applicable publication. CG03 protects wire schema, not JVM linkage.

### ARCH-03

**P2, known packaging debt, expanded to plugin closure.** Worker depends on profiler for
optional --complete-profile conversion; codegen depends on worker. Static broker startup
rejects that flag yet inherits diagnostic artifacts/BTF production dependencies. Separate
the approved production closure with an explicit CLI migration; qualify packaged readiness,
denial and physical cleanup without profiler, retaining diagnostic completeness refusals.
Downloaded JAR use does not itself run a collector or require local clang. Final measurements
must use the chosen artifact; no dependency deletion is authorized by this review.

### ARCH-04

**P2, known release-graph debt, partially improved.** Core CI now validates independently,
but `.github/workflows/ci.yml:886–940` joins all products and publishes the broad graph.
Approve an exact artifact→qualification matrix, then select closure-specific publication
tasks with shared-change propagation and failure/skip negatives. A common version is enough
initially. Preserve present all-artifact requirements until that decision; optional defects
need not inherently block an independently qualified core artifact.

### ARCH-05

**P2, known deployment-envelope qualification gap.** JDK22 bytecode/runtime floor, JDK25
build, Landlock feature minimums, Portal ABI8 and strict/observed HotSpot pins are different
claims. Historical P617 host compatibility is not current Kotlin2.4.21 native qualification.
Select one exact artifact/JDK/vendor/architecture/kernel/ABI/outer-policy tuple per promoted
mode, with real startup/denial/cancellation/drain evidence and explicit unsupported cases.
No new failure on every untested platform is asserted. Operator chooses the first target.

### ARCH-06

**P2, known maintenance/outcome debt.** `H/patches/README.md:12–34,133–160` inventories nine
coupled local review families but not owners/upgrade budgets/stop criteria. Keep one collector
and separate product/recovery outcomes; assign maintenance and conformance obligations to
the existing inventory. Qualify a chosen upstream/JDK tuple and actual adapter outcome.
Protocol replay is not native or Kubernetes persistence proof; broader capture may validly
remain no-go. Native safety ownership mechanisms are not removable “bloat.”

### TEST-04

**P2, source-confirmed consumer-matrix deadline conflict.**
`src/consumerVerification/kotlin/io/mazewall/verification/PackagedConsumerMatrixTest.kt:13–95`
inherits a two-minute JUnit default around sequential15-minute commands and a15-minute
lock. Use a scoped same-thread aggregate budget and the shared owned-command cleanup,
retaining each child bound. Test delayed-phase progress and true expiry/descendant retirement,
then a cold/strict-repeat matrix. IndependentConsumerCacheTest's40-minute repair is present
and does not cover this sibling. No slow matrix was run.

### DOCS-01

**P2, current unsafe/conflicting recipe.** `GETTING_STARTED.md:390` and
`docs/internals/designs/core/security-considerations.md:129–132` recommend raw NO_EXEC
for a running HotSpot baseline, contrary to explicit runtime-safe guidance. Align secondary
recipes and exact sibling/shared-resource limits. Review all current Tier1 examples;
do not treat this wording defect as missing preset/compiler repair. Adoption718 owns it.

### DOCS-02

**P2, retired API guidance.** `docs/index.md:8`, `enforcer/README.md:90–156` and
`docs/internals/designs/core/architectural-map.md:66`
still advertise arbitrary borrowed-executor wrapping. Public APIs intentionally removed it;
document current ManagedRuntime/domain/call and separate advanced manual install. Remove stale
source-tree prose and mark old migration prohibitions historical. Compare examples with
current ABI/packaged fixtures; no compatibility shim should be invented.

### DOCS-03

**P2, inaccurate supported packages/dependencies.** `docs/public-api.md:5–42` omits owned
execution/actual package families; README/CONTRIBUTING claim no JVM runtime dependencies
despite platform/serialization. Reconcile with ARCH-01 and real outgoing metadata, distinguishing
native C library independence from JVM dependency absence. ABI-public does not automatically
mean supported extension point; no visibility/wiring change is part of this documentation work.

### DOCS-04

**P2, confirmed missing command path.** `CONTRIBUTING.md:57–58` and
`docs/REPRODUCING_RESULTS.md:19–23` call absent scripts/run_tests.sh. Reuse maintained
testing-strategy/unitCheck/kernelCheck/container commands. Existing first-install003 owns
these secondary routes; its earlier GETTING_STARTED correction remains implemented.
Validate references/executable paths rather than adding a launcher.

### DOCS-05

**P2, reproduced authority-link/provenance drift.** Active notification/supervisor docs
link moved accept430/secure-exec909; JVM-floor research links moved075; October reviews
link moved528. Retarget historical records without rewriting dated outcomes. Accept's current
route defect is SUP-02; denied secure-exec FD cleanup is source-fixed. CI owners759/363 link
absent approved memory/security-data spec/plan files: recover their actual provenance,
not a fabricated replacement approval. Local link checks cannot recover missing authority.

### DOCS-06

Alias of TOOL-02 for current procedural fallout. Starts/merges need an explicitly named
replacement or suspended route; no authority is inferred from staged deletion.

### DOCS-07

**P3, index-summary drift corrected by this review's dated amendment.** Issue528 is resolved
with November3 scoped receipts despite the October summary saying912/528 stay open. Preserve
the dated review, amend the present index and navigable links;912/native/release/persistence
remain open. No runtime implementation or new Detekt execution follows from this correction.

### TOOL-01

**P2, expanded container locality/context defect.** `.dockerignore` and
`scripts/run_containerized_tests.sh` permit a broad checkout build context before validating
the effective default endpoint. This is eligibility/ordering evidence, **not an observed
secret transfer or image-layer leak**. Extend ownership005: source-free/minimal context,
effective endpoint check before transfer, disposable local/remote/default-endpoint negatives.
Existing writable-bind/UID protection remains sound; no credentials were read or changed.

### TOOL-02

**P2, source-confirmed removed-controller fallout; alias DOCS-06.**
`scripts/run_paperclip_loop.sh` and adjacent wrappers can perform ingestion/work before
failing on intentionally removed tools/orchestrator. Retire absent routes before side
effects and reconcile active docs/state/reducer tasks. Operator must choose retained
standalone tooling and replacement start/merge authority; preserve scoped/serial review.
Test refusal with zero board/build/dispatch effects. Restoration or direct default-branch
publication is not an inferred remedy.

### TOOL-03

**P2, conditional retained-ingestion identity defect.**
`scripts/paperclip_backlog_sync.kts:115–124,449–474` truncates modern issue IDs and loses
dependency edges. If TOOL-02 retains this route, reuse canonical complete identity/parser
semantics and test colliding prefixes plus dependency resolution on current fixtures.
Deletion resolves the unused route instead. No live board write or exhaustive parser audit ran.

### TOOL-04

**P2, conditional retained-ingestion failure-status defect.** The same script at
`:519–569` can finish successfully after failed/ambiguous writes. Preserve partial receipts,
return nonzero and distinguish uncertain create outcomes before any retry. Test partial
failure/ambiguous POST without duplicate replay. Depends on TOOL-02 retention decision;
this is not a shipped-library blocker or approval to operate the control plane.

### TEST-03

**P2, existing-validator reproduction of impossible throughput acceptance.**
`src/consumerVerification/kotlin/io/mazewall/verification/BoundaryEvidence.kt:189–197`
accepts1ns batch duration enclosing240ns of ordinary sample clocks; it reports3e9/s and
the80-corruption self-check still passes. Check batch enclosure and promised serial
ordering; add a single-field impossible-duration negative and repair the invalid synthetic
baseline. Keep valid retained datasets and source/artifact identity. P01/adoption805 consume
this correction; this finding supplies no actual library speed or HTTP comparison.

## W20 — paused retained recovery

These findings concern ignored-but-plan-owned Python under
`.superpowers/sdd/2026-10-27-hotspot-archive-requalification/remaining-images-3/recovery-bootstrap-correctness-1/`
(prefix `T/`). No retained modules/sessions/providers were executed. The original four
reproductions have source corrections; three connected residuals expand existing owners.

### RET-01

**P1 within recovery, source-confirmed; pure Python semantics reproduced.**
`T/bootstrap_entry.py:2060–2075,2137–2146` validates dictionary objects/values before raw
attribute/member keys. In-place colliding foreign keys can execute equality and preserve
accepted value identity. Check finite raw string/opaque associations before lookup or
attribute traversal using existing identity-first patterns. Extend029 with provider-attrs
and original Member-registry mutations: zero trap calls/debit/open, unchanged custody.
Original parent/ledger/role key fixes remain present. No unauthorized native effect ran.

### RET-02

**P2, source-confirmed failure post-validation gap.** After actual shared-call failure,
`T/bootstrap_entry.py:2045–2048,2399–2457` revalidates binding but not the authenticated
Member's charged witness. A charged-flag mutation plus primary failure yields no second
custody failure. Retain and revalidate authenticated milestones, preserving primary-plus-post
group, same owner/Member/partial provider/spent charge and attempted-once barrier. Add the
failing-call seam mutation after RET-01. Expand877; readiness remains fail-closed and no
refund/reinstall succeeded.

### RET-03

**P2, source-confirmed setup-phase gap; runtime qualification outstanding.**
`T/bootstrap_entry.py:2001–2032` checks phase only before constructor/shared seams;
guarded advance at `:1858–1862` can occur inside installation before parent reservation.
Distinguish in-progress install from later use, prohibit phase advance then, and validate
before effects/readiness. Add constructor/shared-call seam negatives with no reservation,
preserving future-parent/later-use positives and custody. Expand695; a final-only check is
insufficient. Keep the route paused. Context428's requested authentication slice is
source-fixed; it is not foundation/native/publication approval or administrative closure.

## Preserved fixes, bounded debt and excluded claims

- **Implemented, not reopened:** November3 immutable BPF proof/JA/x32/action-payload/safe-prctl
  work; HotSpot custom-label bounds; scoped OCI/provider-alias repairs; secure-exec denied
  rewrite FD cleanup; captured native backend; captured-context fatal handling; registered
  prepared-retirement late-failure sampling; controlled CLI refusals; actual-file outline
  cleanup; SpotBugs auxiliary truncation; IndependentConsumerCacheTest timeout. Some active
  trackers still require integration/qualification; current root build is failed.
- **Platform bounded debt:** missing-symbol downcall fallback builds the wrong captured-state/
  return signature (`P/ffi/internal/RealNativeEngine.kt:843–856`); qualify an injected absent
  symbol under ARCH-05 if that libc tuple is supported. Platform-thread global errno arenas
  remain lifetime retention debt, not the fixed virtual-thread leak. NativeBpf.openDescriptor
  should reject non-FD-return commands before future expansion; only MAP_CREATE production
  use was found. Raw SandboxedPath.contains permits unnormalized paths, but inspected security
  callers normalize first; no current escape. FD ownership docs still describe obsolete
  remint/copy receipt behavior; update wording, preserving atomic single-use receipt ownership.
- **Evidence/parser debt:** EbpfEventParser accepts an unterminated quoted field; recorded
  sessions remain incomplete. Missing open flags fail closed during aggregation rather than
  providing an inspectable result; do not guess flags. Unknown names dropped by BoB import
  belong in EVID-01's lossy-import matrix. IO_URING_PROFILING's Netty leak-detector flag is
  not a transport-disable mechanism; require framework-specific verified disabling in adoption
  guidance. No Netty setup was executed.
- **Native/ownership qualification:** PLAT-07 real reuse, checked-JNI/allocation/replacement
  matrices, LIF-Q1/Q2, native Portal signals/replacement/FIFO, HotSpot original-attachment final
  sampling and actual current graph remain unqualified. Issue042 already owns final readout
  authority; synthetic drain tests cannot establish that no original writer remains. Historical
  Tier E71/72 miss and profiler752 cold-network failure remain causally unresolved.
- **Intentional limits:** manual missing writable-leaf parent widening is an existing manual
  contract, not a prepared-resource regression. Active filesystem grants now intersect; old
  union warnings are stale. Clone inspectability wording differs from policy-controlled
  non-thread branches; no explicit CLONE denial bypass was found. Simulator instruction-pointer
  loads are unmodeled but unused by current built-ins. Thread containment is not an ACE boundary;
  heap/preopened descriptors/helpers remain separate authority. Stack capture alone does not
  qualify helper resource/LSM authority or base-policy intersection.
- **Architecture/complexity:** retain native ABI/ownership, permanent installation, separate
  result/end, process broker and adapter boundaries. Correlated nullable-worker/phase and
  broker-close shapes are known design debt without demonstrated races. Prefer bounded local
  corrections, one collector and fewer supported operator paths over generic lifecycle frameworks
  or a second profiler engine. Performance and native-memory ceilings need actual matched data.

No application correction, issue closure, release approval, merge, publication, worktree or
live recovery operation is implied by this register.
