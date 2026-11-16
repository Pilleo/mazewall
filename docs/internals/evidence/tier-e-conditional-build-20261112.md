# Conditional Tier E build checkpoint — 2026-11-12

The complete Gradle build succeeded in the reviewed container envelope: 4,672 test cases across 694 retained XML files, 45 skipped, no failures or errors. Gradle reported 248 actionable tasks: 89 executed and 159 up-to-date. Two task outcomes were FROM-CACHE. Unit instruction coverage was 80,145/97,726 (82.009905%); combined instruction coverage was 80,572/97,726 (82.446841%). Both configured enforcer coverage verification tasks succeeded. Fifty-two fresh task-owned unit child coverage files were retained; the refused offline feasibility data was not imported.

This is conditional qualification: only `:enforcer:integrationTestFreshJvm` was configured with `mazewall.supervisor.prewarmTimeoutMillis=15000`. The production default remains 5,000 ms; actual supervisor property propagation in this full run was **not sampled**. An earlier focused run sampled it. This result does not establish default startup reliability or the cause of issue 051. The reviewed run used the pinned image and security policy, non-root user, no-new-privileges, read-only container root, fixed resource limits and one Gradle worker.

The current build log records three `mazewall-child-shutdown` socket cleanup exceptions and three corresponding suppressed directory exceptions reporting `Function not implemented`. Individual resource retirement remains Unknown under issue 752. The build container exited with PID 0 and its physical retirement was verified. Issues 051, 338 and 569 remain open. Pre-typed raw/token ownership, VM and arena limits, canonical dispatcher and custody limits remain; B5 helper capture requires its next preimplementation review. This checkpoint does not claim completion of B5 or the wider roadmap.

The following are SHA-256 references to locally retained artifacts; those artifacts are not included in this checkpoint and their availability is local to the execution environment:

- Full-run prepared manifest: `ca86575234a04846afc61d3f6618088dc7018a8c25cae9fe41a818a8422edf2d`.
- Detailed report with retained skip identities: `4035e64de251b98f220148dc9fbb2b5b32d1c5f61a8688932caadd96cc8167bd`.
- Index after the run: `fe5bf32e949f3d70b8d0b934b062129044c569e8066e1c251499323ab68af53a`.

## Skipped test identities

- `io.mazewall.testing.containers.ContainerEndpointDiscoveryTest$ConditionFixture` — `condition injects the admitted endpoint(ContainerEndpoint)`
- `io.mazewall.ffi.cet.CetExploitDemoTest` — `exploit with CET enabled triggers hardware Control Protection Exception()`
- `io.mazewall.seccomp.IntelCetIntegrationTest` — `pre-enabled compatible runtime confirms current thread locking()`
- `io.mazewall.enforcer.ContainedExecutorsTest` — `test graceful degradation fallback()`
- `io.mazewall.landlock.LandlockFailureModeTest` — `test handleInitialOpenFailure with deleted file()`
- `io.mazewall.landlock.LandlockFailureModeTest` — `test restrictSelf failure()`
- `io.mazewall.portal.RequiredWorkerLaunchProfileTest` — `owned delegated cgroup applies finite controls before permit and retires real guest()`
- `io.mazewall.profiler.hotspot.HotSpotPublicIntegrationTest` — `installed public strategy attributes unique operations in exact application order(ContainerEndpoint, Path)`
- `io.mazewall.profiler.hotspot.HotSpotPublicIntegrationTest` — `retention pressure keeps all terminal accounting but discards excess retained payload(ContainerEndpoint, Path)`
- `io.mazewall.profiler.hotspot.HotSpotPublicIntegrationTest` — `matched public wrapper and standalone windows retain actual measurements(ContainerEndpoint, Path)`
- `io.mazewall.profiler.hotspot.HotSpotPublicIntegrationTest` — `collector command retirement is independent of fixture container teardown(ContainerEndpoint, Path)`
- `io.mazewall.profiler.hotspot.HotSpotPublicIntegrationTest` — `target exit aborts public session without replacing its event stacks(ContainerEndpoint, Path)`
- `io.mazewall.profiler.hotspot.HotSpotPublicIntegrationTest` — `supported ordinary current JVM executes the public workload facade(ContainerEndpoint, Path)`
- `io.mazewall.profiler.hotspot.HotSpotPublicIntegrationTest` — `real helper permission refusal and startup deadline never yield a usable session(ContainerEndpoint, Path)`
- `io.mazewall.profiler.hotspot.HotSpotPublicIntegrationTest` — `dense interpreter diagnostic retains checked success or rejection(ContainerEndpoint, Path)`
- `io.mazewall.profiler.hotspot.HotSpotPublicIntegrationTest` — `profiled dense interpreter diagnostic retains JFR and checked retirement(ContainerEndpoint, Path)`
- `io.mazewall.profiler.api.TierEDaemonNativeWorkflowIntegrationTest` — `daemon controls an agent loaded workload through attach detach and shutdown()`
- `io.mazewall.profiler.api.TierEEndToEndIntegrationTest` — `unique edge native session attaches and captures definitions()`
- `io.mazewall.profiler.api.TierEEndToEndIntegrationTest` — `native session captures definitions and becomes terminal after finish()`
- `io.mazewall.profiler.api.TierEEndToEndIntegrationTest` — `all target captures distinct managed paths and ambient syscall rows()`
- `io.mazewall.profiler.TierERootfulContainerEndpointIntegrationTest` — `timeout retires only its own privileged container(ContainerEndpoint)`
- `io.mazewall.profiler.TierERootfulContainerEndpointIntegrationTest` — `Kotlin owned coverage baseline runs without agent or collector(ContainerEndpoint, Path)`
- `io.mazewall.profiler.TierERootfulContainerEndpointIntegrationTest` — `killed fixture controller cannot leave its privileged collector running(ContainerEndpoint, Path)`
- `io.mazewall.profiler.TierERootfulContainerEndpointIntegrationTest` — `startup cancellation settles an in flight real Docker creation before cleanup(ContainerEndpoint, Path)`
- `io.mazewall.profiler.TierERootfulContainerEndpointIntegrationTest` — `Kotlin owned delayed closed artifact is admitted after target exit(ContainerEndpoint, Path)`
- `io.mazewall.profiler.TierERootfulContainerEndpointIntegrationTest` — `Kotlin owned short benchmark reconciles exact operations(ContainerEndpoint, Path)`
- `io.mazewall.profiler.TierERootfulContainerEndpointIntegrationTest` — `Kotlin owned allowed incomplete flag uses explicit coverage verdict(ContainerEndpoint, Path)`
- `io.mazewall.profiler.TierERootfulContainerEndpointIntegrationTest` — `Kotlin owned owner-only control socket rejects target and unrelated users(ContainerEndpoint, Path)`
- `io.mazewall.profiler.TierERootfulContainerEndpointIntegrationTest` — `failed real log export retains stopped container and its recoverable logs(ContainerEndpoint, Path)`
- `io.mazewall.profiler.TierERootfulContainerEndpointIntegrationTest` — `Kotlin owned two-container fixture recovers exact 72 declared paths(ContainerEndpoint, Path)`
- `io.mazewall.profiler.TierERootfulContainerEndpointIntegrationTest` — `Kotlin owned stale identity is rejected before workload attribution(ContainerEndpoint, Path)`
- `io.mazewall.profiler.TierERootfulContainerEndpointIntegrationTest` — `Kotlin owned fault fixture retains exact coverage and incomplete ring transport(ContainerEndpoint, Path)`
- `io.mazewall.profiler.TierERootfulContainerEndpointIntegrationTest` — `rootful endpoint executes the Tier E image(ContainerEndpoint)`
- `io.mazewall.profiler.TierERootfulContainerEndpointIntegrationTest` — `Kotlin owned unclosed artifact is rejected without policy eligibility(ContainerEndpoint, Path)`
- `io.mazewall.profiler.TierERootfulContainerEndpointIntegrationTest` — `interrupted fixture teardown still reaps unrelated sentinel(ContainerEndpoint, Path)`
- `io.mazewall.profiler.TierERootfulContainerEndpointIntegrationTest` — `privileged failure exports caller-owned artifacts(ContainerEndpoint, Path)`
- `io.mazewall.profiler.TierERootfulContainerEndpointIntegrationTest` — `Kotlin owned negative artifact keeps terminal incomplete(String, ContainerEndpoint, Path)`
- `io.mazewall.profiler.TierERootfulContainerEndpointIntegrationTest` — `installed all-target daemon qualifies evidence access(String, ContainerEndpoint)`
- `io.mazewall.profiler.TierERootfulContainerEndpointIntegrationTest` — `Kotlin owned simulated PID reuse closes the session incomplete(ContainerEndpoint, Path)`
- `io.mazewall.profiler.TierERootfulContainerEndpointIntegrationTest` — `non-root coverage target and privileged collector recover the exact 72 paths(ContainerEndpoint, Path)`
- `io.mazewall.profiler.hotspot.HotSpotAssetsTest` — `installed native bundle passes Kotlin admission()`
- `io.mazewall.profiler.hotspot.HotSpotBackendTest` — `validated installed assets preserve operator argv prefix and split pipes()`
- `io.mazewall.profiler.hotspot.HotSpotBackendTest` — `actual external collector refusal preserves native errno and retires child pipes()`
- `io.mazewall.profiler.hotspot.HotSpotNativeAttachTest` — `owned Kotlin adapter captures real ordinary JVM and finishes through parent control pipe()`
- `io.mazewall.profiler.tierE.daemon.TierEEvidenceFileAccessTest` — `inherited named nonmember acl is removed before widening either sink output()`
