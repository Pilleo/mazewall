# Post-review hardening evidence

This is the reviewer-facing index for the 30 accepted post-review hardening findings in
the historical `fast-master..codex/complete-architecture-repair` range. A row is complete only when it names an
implementing commit and executable regression evidence; task names are Gradle module gates.

The subsequent `be425e98..codex/review-hardening` work has its own stable [RH finding IDs](#liveness-and-boundary-review-rh).
Historical PRH IDs are retained so earlier reviews and backlog links remain meaningful.

| ID | Invariant | Implementing commit | Regression evidence | Gate | Status |
|---|---|---|---|---|---|
| PRH-01 | USER_NOTIF reply outcomes are explicit. | `51d7eadd` | `UserNotifSharedTest#send retries EINTR before reporting a sent reply` | `:platform:test` | complete |
| PRH-02 | Reply retries `EINTR` before classification. | `51d7eadd` | `UserNotifSharedTest#send retries EINTR before reporting a sent reply` | `:platform:test` | complete |
| PRH-03 | Expired replies are `NotificationGone`, never `Sent`. | `51d7eadd` | `UserNotifSharedTest#send classifies an expired notification without treating it as sent` | `:platform:test` | complete |
| PRH-04 | An expired pass-through notification terminates its daemon session exactly once. | `53e8071f` | `SeccompSessionHandlerTest#expired pass-through notification terminates the session without another reply` | `:platform:test` | complete |
| PRH-05 | Hard reply failures remain terminal. | `51d7eadd` | `UserNotifSharedTest#send classifies a hard kernel failure` | `:platform:test` | complete |
| PRH-06 | Permanent installations fail closed when their reactor dies. | `94053204` | `SupervisorInstallationLifetimeTest#reactor failure retires runtime and rejects another task lease` | `:enforcer:test` | complete |
| PRH-07 | Owner termination retires runtime and listener exactly once. | `94053204` | `SupervisorInstallationLifetimeTest#owner termination retires runtime and listener exactly once` | `:enforcer:test` | complete |
| PRH-08 | Accepted helper descriptors are closed when route construction fails. | `7a1068a4` | `PoolSocketRouteExecutorTest#invalid accepted helper result closes descriptor when effect construction fails` | `:enforcer:test` | complete |
| PRH-09 | Accepted routes defer tracee writes until sole reply authority. | `7a1068a4` | `PoolSocketRouteExecutorTest#accept completion defers tracee memory writes until terminal reply authority` | `:enforcer:test` | complete |
| PRH-10 | Typed open-flag evidence rejects contradictory legacy values. | `2e07ec60` | `ProfileObservationCompatibilityTest#typed flag evidence is canonical and rejects contradictory legacy values` | `:profiler:test` | complete |
| PRH-11 | Missing or incomplete flags cannot compile as zero. | `2e07ec60` | `StraceLogParserTest#incomplete open flags are not compiled as numeric zero` | `:profiler:test` | complete |
| PRH-12 | Quoted strace arguments cannot alter the flags operand. | `2e07ec60` | `StraceLogParserTest#quoted commas escapes and flag-looking path text cannot change the flags operand` | `:profiler:test` | complete |
| PRH-13 | Public profiler copies retain typed evidence and immutable ownership. | `2e07ec60` | `ProfileObservationCompatibilityTest#generated copies and components retain all typed outcomes on every observation variant` | `:profiler:test` | complete |
| PRH-14 | Profiler state/event combinations are exhaustive and violations are terminal. | `1ce834ef` | `ProfilerSessionMachineTest#verify non-applicable profiler events terminate as protocol violations` | `:profiler:test` | complete |
| PRH-15 | Post-terminal profiler observation is explicit and benign. | `1ce834ef` | `ProfilerSessionMachineTest#post terminal observation is explicitly benign` | `:profiler:test` | complete |
| PRH-16 | Invalid seccomp handshakes cannot continue. | `2817fb0a` | `SeccompConnectionMachineTest#verify seccomp connection transition matrix` | `:platform:test` | complete |
| PRH-17 | Daemon startup publishes only a ready, committed lifecycle. | `8c0479c3` | `UnixListenDaemonMachineTest#verify daemon transition matrix` | `:platform:test` | complete |
| PRH-18 | Broker startup is all-or-nothing. | `4c2a68c0` | `ProcessBrokerLifecycleMachineTest#startup failure is terminal and cannot be published` | `:portal:test` | complete |
| PRH-19 | Broker responses match the active method and request. | `4c2a68c0` | `PortalBrokerCallMachineTest#rejects a response for a different method` | `:portal:test` | complete |
| PRH-20 | Broker post-terminal events are modeled observations. | `70056f78` | `PortalBrokerCallMachineTest#post terminal events are explicit observations` | `:portal:test` | complete |
| PRH-21 | Worker fatal receive failures stop before propagation. | `65c99790` | `PortalWorkerDriverTest#fatal receive failure transitions worker to stopped before propagation` | `:portal-worker:test` | complete |
| PRH-22 | Worker requests complete only after reply transmission succeeds. | `e0caa339` | `PortalWorkerMachineTest#a request is not complete until reply transmission succeeds` | `:portal-worker:test` | complete |
| PRH-23 | Partial reply writes are terminal and never append an error frame. | `65c99790` | `PortalWorkerDriverTest#failed response write is terminal and never appends an error frame` | `:portal-worker:test` | complete |
| PRH-24 | Generated schemas resolve inherited overrides once. | `9f1c72c7` | `PortalStubGeneratorTest#effective inherited methods and override are generated once` | `:portal-codegen:test` | complete |
| PRH-25 | Kotlin JVM records use valid component accessors. | `9f1c72c7` | `PortalStubGeneratorTest#Kotlin JVM records use record component accessors` | `:portal-codegen:test` | complete |
| PRH-26 | CLI containment claims require authenticated evidence. | `5a3a890a` | `ProfileAndEnforceDemoTest#profile demo does not claim success from inferred containment` | `:demos:cli-demo:test` | complete |
| PRH-27 | CLI fixtures stay private and preserve pre-existing symlinks. | `420846f5` | `DemoOutcomeTest#demo fixture is private and cannot replace a preexisting path or symlink` | `:demos:cli-demo:test` | complete |
| PRH-28 | Agent scenarios distinguish authenticated denials from infrastructure failure. | `5c5bd74b` | `AgentDemoOutcomeTest#only observed policy denials count as authenticated containment` | `:demos:agent-sandbox-demo:test` | complete |
| PRH-29 | Web compose binds host ports privately while serving the container network. | `14a21479` | `DeploymentContractTest#compose keeps host ports private while binding container servers for networking` | `:demos:vulnerable-web-app:test` | complete |
| PRH-30 | Closed lifecycle machines have no silent fallback transition. | `343f2ba5` | `PortalWorkerMachineTest#non-request frame is a protocol violation and peer closure stops the worker` | `unitCheck` | complete |

The resolved backlog records remain the detailed historical implementation evidence. This table is
the compact review entry point; it does not replace their constraints, design documentation, or
fresh-JVM verification where a row changes kernel-facing behavior.

## Liveness and boundary review (RH)

The following 30 rows partition the approved whole-project liveness plan into reviewable
invariants. They do not assert that every kernel operation can be cancelled or that every
test was freshly executed by every aggregate gate. Implementation commits and regression
methods are independently identifiable; [verification evidence](review-hardening-verification.md)
records task execution, cache reuse, kernel limitations and the final build result.
The [liveness contract](notification-liveness-contract.md) describes the guarantees and limits.

| ID | Invariant | Implementing commit(s) | Regression class / method | Gate | Status |
|---|---|---|---|---|---|
| RH-01 | Managed native transfer lengths reject negative, overflowed and oversized ranges before dispatch. | `3036c58`, `f79eabfb`, `4d914ad2` | `NativeTransferValidationTest#read and write reject negative and oversized counts before native dispatch`; `RawManagedBoundaryValidationTest` | `:platform:test` | implemented; scoped tests passed |
| RH-02 | Descriptor streams validate the entire Java array range, including zero length. | `3036c58` | `NativeTransferValidationTest#bulk stream validates every array range before reading including zero length` | `:platform:test` | implemented; scoped tests passed |
| RH-03 | Only acquisition receipts create owned descriptors; raw numeric success cannot fabricate ownership. | `3036c58` | `DescriptorIdentitySafetyTest#ordinary numeric success is not an acquisition receipt`; `#raw integer factories cannot mint owned close capabilities` | `:platform:test` | implemented; scoped tests passed |
| RH-04 | Concurrent close dispatches once and retires identity even when native close fails. | `3036c58` | `DescriptorIdentitySafetyTest#concurrent closes dispatch exactly once and retire even when close throws` | `:platform:test` | implemented; scoped tests passed |
| RH-05 | The reactor expires pending notification work at 30 seconds and discards late completion. | `e7b832d5` | `NotificationDeadlineTest#idle reactor expires pending work at thirty seconds and ignores late completion`; `#deadline wins over a completion returned at its boundary` | `:platform:test` | implemented; scoped tests passed |
| RH-06 | Gone, failed or throwing replies cannot acquire a second terminal reply. | `e7b832d5` | `NotificationDeadlineTest#notification gone and hard failure are terminal without denial retry`; `#throwing terminal operation cannot enter denial recovery` | `:platform:test` | implemented; scoped tests passed |
| RH-07 | Native interruption cannot renew the notification deadline or retry forever. | `e7b832d5` | `NotificationDeadlineTest#timeout attempts denial once even when send is interrupted`; `UserNotifSharedTest#persistent EINTR exhausts the reply budget` | `:platform:test` | implemented; scoped tests passed |
| RH-08 | Control writes and verdict reads yield under the original deadline. | `e7b832d5` | `ControlSocketExchangeTest`; `SupervisorSessionHandlerTest#reactor adapter yields across control IO and leaves reply to the terminal lease` | `:platform:test`, `:enforcer:test` | implemented; scoped tests passed |
| RH-09 | Unresolved pointer paths cannot gain authority through classloading suffixes. | `d72b577a` | `SupervisorNotificationMachineTest#unresolved open path cannot acquire authority from a classloading suffix` | `:enforcer:test` | implemented; scoped tests passed |
| RH-10 | Unsupported supervised operations and handlerless default notify are rejected. | `d72b577a` | `SupervisorBpfCompilationTest#unsupported supervised operations are rejected during compilation`; `#default notify cannot dispatch unhandled operations` | `:enforcer:test` | implemented; scoped tests passed |
| RH-11 | Installation preserves irreversible partial state and waits for sole applied-stage publication. | `e06de4cf`, `24c5f5cb` | `SupervisorInstallationDeadlineTest`; `SeccompInstallPublicationTest#timeout cleanup observes an active applied callback failure without invoking it twice` | `:enforcer:test`, `:enforcer:integrationTestFreshJvm` | implemented; scoped tests passed |
| RH-12 | Failed listener health rejects subsequent task registration and survives cleanup failure. | `e06de4cf` | `SupervisorInstallationLifetimeTest`; `SupervisorListenerHealthTest#socket close failure cannot hide an unexpected listener exit` | `:enforcer:test` | implemented; scoped tests passed |
| RH-13 | Admission is bounded at 128 total and 8 per TID, with one EAGAIN attempt on overload. | `8253fd21` | `SupervisorAdmissionTest#global limit is exactly 128 independently of per TID capacity`; `#ninth pending notification from one TID is rejected before preparation`; `#overload sends one EAGAIN through reactor even on EINTR or failure` | `:enforcer:test` | implemented; scoped tests passed |
| RH-14 | Blocking authority preparation owns tracee capabilities in helpers and disposes late results; root keys cannot alias caller dirfds. | `219392fd`, `8c725eff`, `bee9ffc3` | `TraceeAuthorityReactorTest#blocked authority preparation yields and cancellation disposes late effect without reply`; `TraceeAuthorityIntegrationTest#invalid relative open dirfd cannot select the captured root capability` | `:enforcer:test`, `:enforcer:integrationTestFreshJvm` | implemented; peer-memory accept limitation retained |
| RH-15 | Profiler structural failure offers one denial; failed noise replies cannot publish or retry. | `998993ae` | `ProfilerDaemonTest#reactor structural failure offers one terminal denial without publishing`; `#reactor noise reply failure is terminal without publication or another attempt` | `:profiler:test` | implemented; scoped tests passed |
| RH-16 | ACK write failure closes before the next event and preserves the original processing failure. | `998993ae`, `7f1e693b` | `ProfilerTraceListenerTest#failed ACK closes endpoint before reading another event`; `#processing failure remains primary when ACK and close also fail` | `:profiler:test` | implemented; scoped tests passed |
| RH-17 | Preparation preserves architecture and cannot replace an unreaped helper beyond capacity. | `7f1e693b`, `218ea8ff` | `ProfilerDaemonTest#notification parsing retains kernel architecture metadata`; `ProfilerPreparationPoolTest#unreaped child fails pool without admitting replacement helpers` | `:profiler:test`, `:profiler:integrationTestFreshJvm` | implemented; scoped tests passed |
| RH-18 | Broker child diagnostics are bounded and readiness requires an exact complete sentinel. | `34c5942c` | `PortalWorkerOutputTest#diagnostics retain bounded sanitized lines and count discarded output`; `#readiness requires a complete exact sentinel line` | `:portal:test` | implemented; scoped tests passed |
| RH-19 | Fatal checkout destroys/reaps its worker and retains original failure identity. | `34c5942c` | `ProcessBrokerIntegrationTest#fatal checkout retires child before propagating original error and suppresses cleanup` | `:portal:integrationTest` | implemented; scoped tests passed |
| RH-20 | Worker EOF, partial frames and transport failures have distinct terminal outcomes. | `3d44328f` | `PortalWorkerReceiveTest#only clean EOF becomes peer closed and only pre-frame timeout is idle`; `PortalWorkerDriverTest#failed response write is terminal and never appends an error frame` | `:portal-worker:test` | implemented; scoped tests passed |
| RH-21 | Worker readiness follows permanent process containment, validation and guest construction. | `3d44328f` | `PortalWorkerStartupTest#readiness follows containment and guest construction before requests`; `DedicatedProcessBaselineTest` | `:portal-worker:test`, `:enforcer:integrationTest` | implemented; scoped tests passed |
| RH-22 | Host-only codegen publishes neither worker dispatchers nor descriptors. | `a699ba37` | `PortalCodegenFunctionalTest#host only configuration publishes no worker code or descriptor` | `:portal-codegen:test` | implemented; scoped tests passed |
| RH-23 | An explicitly selected worker artifact compiles guest dispatchers during ordinary build. | `a699ba37`, `7e452639` | `PortalCodegenFunctionalTest#ordinary build compiles opted in worker sources`; `#main source set cannot be selected as worker output` | `:portal-codegen:test` | implemented; scoped tests passed |
| RH-24 | ReadFd is permitted only as a direct inbound parameter, and workers load their generated descriptor. | `a699ba37` | `PortalStubGeneratorTest`; `PortalGeneratedDescriptorTest` | `:portal-codegen:test`, `:portal-worker:test` | implemented; scoped tests passed |
| RH-25 | Isolated test cleanup covers successful exits and interruption; fresh selection includes inherited/nested invocations. | `abd65a9a`, `145cdb75` | `IsolatedProcessTesterLifecycleTest`; `VerificationActionsTest`; `TestHealthPluginFunctionalTest` | `:test`, `-p buildSrc check` | implemented; observed-descendant limit documented |
| RH-26 | CLI profiling is explicit, replays the positive filesystem operation and rejects incomplete evidence. | `1f73a57e`, `ac79af08` | `DemoAppTest#default all never invokes profiling`; `ProfileAndEnforceDemoTest#positive workload reads the file again instead of reusing profiled content`; `#incomplete observation cannot compile an enforcement policy` | `:demos:cli-demo:test` | implemented; scoped tests passed |
| RH-27 | Demo containment requires authenticated evidence, exact stack authority and real network controls. | `1f73a57e` | `ProfileAndEnforceDemoTest#profile stack authority requires exact class and method`; `DemoAppTest#network control reaches loopback uncontained and is blocked with NO_NETWORK`; `AgentScenarioFreshJvmTest` | CLI/agent `test` | implemented; scoped tests passed |
| RH-28 | Default web artifacts omit executable gadgets and profiling/enforcement share a private mounted artifact. | `62a3f7a5` | `DeploymentContractTest#default artifact excludes executable deserialization fixtures`; `#profiling and enforcement share one named artifact volume with distinct access` | `:demos:vulnerable-web-app:test` | implemented; compose contract tested |
| RH-29 | Successful drain alone cannot authorize enforcement without complete collector coverage. | `077e1f81` | `MazewallProfileManagerTest#orderly executor shutdown without collector coverage cannot authorize enforcement`; `#orderly shutdown retains lossy collector beside a complete collector and rejects enforcement` | `:profiler:test`, web `test` | implemented; scoped tests passed |
| RH-30 | Protocol violations remain incomplete, terminal observation is named, and cleanup/diagnostic evidence retains identity and escaping. | `17320187`, `d5fd310f` | `ProfilerTraceListenerTest#invalid tid during graceful drain remains incomplete without an ACK`; `CleanupFailuresTest`; `DiagnosticReportTest`; `PortalWorkerMachineTest` | `unitCheck` | implemented; scoped tests passed |

Scoped backlog evidence: [native boundaries](../../backlog/resolved/issue-20260910-225751-359-validate-native-transfers-and-descriptor-acquisiti.md),
[reactor deadline](../../backlog/resolved/issue-20260910-225751-953-bound-user-notif-reactor-work-and-terminal-reply-o.md),
[supervisor authority](../../backlog/resolved/issue-20260910-225752-609-record-fail-closed-supervisor-routing-and-installa.md),
[profiler protocol](../../backlog/resolved/issue-20260910-182202-258-make-profiler-notification-and-ack-failures-termin.md),
[broker](../../backlog/resolved/issue-20260910-184603-286-contain-portal-child-output-and-retire-fatal-check.md),
[worker](../../backlog/resolved/issue-20260910-185837-079-make-portal-worker-transport-and-startup-failure-t.md),
[codegen](../../backlog/resolved/issue-20260910-192121-101-compile-portal-dispatchers-in-an-explicit-worker-a.md),
[isolated tests](../../backlog/resolved/issue-20260910-225753-174-record-isolated-test-process-and-selector-hardenin.md),
[CLI/agent](../../backlog/resolved/issue-20260910-212626-611-make-cli-and-agent-containment-claims-verifiable.md),
[web evidence](../../backlog/resolved/issue-20260910-225753-764-record-web-artifact-and-complete-profile-deploymen.md), and
[failure taxonomy](../../backlog/resolved/issue-20260910-225754-299-record-explicit-lifecycle-and-cleanup-failure-evid.md).

## Post-merge remediation evidence (PMR)

This section is the hand-off checklist for the remediation that follows the
review-hardening range above. Unlike the historical PRH/RH tables, a row is not
marked complete until the package commit exists and the named regression has been
run in the current checkout. `pending package commit` is intentional evidence of
an unfinished row, not a claim that a review recommendation has been fixed.

| ID | Invariant | Implementing commit | Regression evidence | Gate | Status |
|---|---|---|---|---|---|
| PMR-01 | A ready notification performs at most one receive attempt; EINTR yields to the next reactor poll. | `pending package commit` | `NotificationDeadlineTest#eintr receive yields before another ioctl` | `:platform:test` | pending |
| PMR-02 | Notification cleanup preserves the primary reply/error and suppresses cleanup failures. | `pending package commit` | `NotificationDeadlineTest#reply and cleanup failures retain the primary throwable` | `:platform:test` | pending |
| PMR-03 | Unresolved pointer paths and unsupported supervised operations fail closed. | `6f876029` | `SupervisorSessionMatrixTest#unresolved pointer routes are denied` | `:enforcer:test` | implemented; package gate pending |
| PMR-04 | Thread-specific authority never falls back to a leader FD table. | `6f876029` | `TraceeProcAuthorityTest#non leader import requires thread pidfd` | `:enforcer:integrationTestFreshJvm` | implemented; package gate pending |
| PMR-05 | Installation readiness and listener health reject registrations after reactor failure. | `pending package commit` | `SupervisorInstallationLifetimeTest#reactor death rejects new leases` | `:enforcer:test` | pending |
| PMR-06 | Accepted descriptors have one owner through route, queue, cancellation and late-result failure. | `pending package commit` | `PoolSocketRouteExecutorTest#failed transfer closes accepted descriptor once` | `:enforcer:test` | pending |
| PMR-07 | Broker close is a shared truthful terminal result for concurrent callers. | `pending package commit` | `ProcessBrokerIntegrationTest#concurrent close callers receive the same cleanup failure` | `:portal:integrationTest` | pending |
| PMR-08 | Capability close failures retain ownership for retry rather than discarding descriptors. | `pending package commit` | `CapabilityOwnershipTest#failed close retains capability for retry` | `:portal:test` | pending |
| PMR-09 | Portal calls preserve interruption and never reuse a partially failed worker. | `pending package commit` | `ProcessBrokerIntegrationTest#interrupted capability admission preserves interruption` | `:portal:integrationTest` | pending |
| PMR-10 | Nested generated services use the same canonical binary name at generation and load time. | `pending package commit` | `PortalGeneratedNamesTest#nested service names are loadable` | `:portal-codegen:test` | pending |
| PMR-11 | Generated nullable overrides and hostile names compile without scope collisions. | `pending package commit` | `PortalStubGeneratorTest#nullable and Companion names compile` | `:portal-codegen:test` | pending |
| PMR-12 | OPENAT2 flags remain incomplete unless `open_how.flags` was inspected. | `pending package commit` | `TraceEventTest#openat2 retains incomplete flags evidence` | `:profiler:test` | pending |
| PMR-13 | Incomplete typed open-flag evidence cannot compile into an enforcement policy. | `pending package commit` | `ProfileObservationCompatibilityTest#incomplete evidence is rejected by compiler` | `:profiler:test` | pending |
| PMR-14 | Profiler structural, noise-reply and ACK failures are terminal with no later publication or reply. | `pending package commit` | `ProfilerSessionMachineTest#failure matrix permits one terminal reply` | `:profiler:test` | pending |
| PMR-15 | Profiler preparation is injectable in tests and cannot start real helpers unexpectedly. | `pending package commit` | `ProfilerPreparationFactoryTest#test preparation is in memory` | `:profiler:test` | pending |
| PMR-16 | Owned child runners drain both streams, terminate descendants, reap, and preserve interruption. | `pending package commit` | `OwnedProcessRunnerTest#timeout and interruption reap descendants` | `:demos:cli-demo:test`, `:demos:agent-sandbox-demo:test` | pending |
| PMR-17 | CLI and agent outcomes distinguish authenticated denial, inconclusive evidence and infrastructure failure. | `pending package commit` | `ProfileAndEnforceDemoTest#inferred denial is inconclusive`; `AgentScenarioFreshJvmTest#inconclusive attack fails verification` | demo fresh-JVM gates | pending |
| PMR-18 | The default web artifact cannot execute deserialization gadgets and rejects incomplete profiles. | `fbaf6dce` | `DeploymentContractTest#default artifact excludes executable fixtures` | `:demos:vulnerable-web-app:test` | implemented; package gate pending |
| PMR-19 | Web profile configuration is isolated per test context and temporary roots are owned. | `fbaf6dce` | `VulnAppApplicationTests#profile properties do not leak between contexts` | `:demos:vulnerable-web-app:test` | implemented; package gate pending |
| PMR-20 | Test-health fragments are tied to the current invocation and include failed/aborted containers. | `pending package commit` | `TestHealthPluginFunctionalTest#stale fragments are rejected`; `VerificationActionsTest#unchanged verification is up to date` | `-p buildSrc check` | pending |
| PMR-21 | Isolated test processes terminate descendants and restore interruption on every completion path. | `pending package commit` | `IsolatedProcessTesterLifecycleTest#successful parent interruption is restored` | `-p buildSrc check` | pending |
| PMR-22 | The reactor watchdog observes approaching deadlines without a second authority or reply. | `70d7dcf` | `NotificationDeadlineTest#deadline near metrics are emitted once and late completion cannot reply` | `:platform:test` | implemented; package gate pending |
| PMR-23 | Documentation and diagrams describe lock, deadline, ownership and protocol-violation guarantees. | `pending documentation commit` | `validate_review_evidence.py`; headless PlantUML render of canonical diagrams | docs/verification gates | pending |

The PMR table intentionally keeps unsupported peer-address `accept`, uninterruptible
kernel tasks, and non-atomic tracee mapping snapshots as documented fail-closed
capability limits. They must not be relabeled as fixed findings merely because a
test exercises the supported path.
# Post-merge liveness and boundary follow-ups

| ID | Invariant | Implementation | Regression | Gate | Status |
|---|---|---|---|---|---|
| PM-01 | ACK writes cannot spin past their deadline | `SeccompDaemonEngine` | bounded EINTR/expiry tests | `:platform:test` | Implemented |
| PM-02 | Profiler handshake uses one absolute deadline | `HandshakeSession` | handshake deadline tests | `:profiler:test` | Implemented |
| PM-03 | Memory reads use injected native adapters | `TraceeMemoryReader.real` | memory-reader seam tests | `:platform:test` | Implemented |
| PM-04 | Owned CLI descendants are retired on every outcome | `OwnedProcessRunner` | descendant-retirement tests | `:demos:cli-demo:test` | Implemented |
| PM-05 | Nested generated names are injective and canonical | `PortalGeneratedNames` | nested/collision fixtures | `:portal-codegen:test` | Implemented |
| PM-06 | Test-health identifiers are valid JSON | `TestSuiteHealthListener.escape` | hostile identifier fixture | `test` | Implemented |
