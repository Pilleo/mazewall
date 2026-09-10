# Post-review hardening evidence

This is the reviewer-facing index for the 30 accepted post-review hardening findings in
`fast-master..codex/complete-architecture-repair`.  A row is complete only when it names an
implementing commit and executable regression evidence; task names are Gradle module gates.

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
