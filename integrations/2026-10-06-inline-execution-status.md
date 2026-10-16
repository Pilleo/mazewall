# MazeWall integration foundation — inline execution ledger

## Operator decisions

- Latest override (8 October): isolated fleet approved, with persistent worktrees,
  checkpoint/report files, explicit session IDs and one writer per worktree. This
  supersedes the earlier inline/no-worktree/no-subagent rules below. The coordinator
  retains serial integration/native verification and preserves all unrelated work.
- Latest execution endpoint: finish all release gates or stop at 12:00 local CEST
  on 8 October 2026, preserving recoverable progress. Initial fleet base `39cd5ce0`.

- Execute directly on `fast-master`; do not create or use worktrees.
- Execute inline without subagents or routine progress commentary.
- A later operator-requested read-only two-agent pilot produced a confirmed CET UAPI defect; one task was cancelled. The operator then explicitly restored original-plan inline execution. No pilot agent edited files or ran builds/native tests.
- Remove obsolete APIs and compatibility shims after migrating their callers. Breaking API changes are approved; no legacy preservation is required.
- Preserve native security invariants and actual resource owners, including receipt registration closure.
- Preserve all unrelated pre-existing working-tree changes. Do not push or merge outside the repository review path.
- Operator subsequently requested continuous inline progress until 14:00 local CEST on 7 October while the session remains active; commit boundaries are not pauses.
- Operator subsequently extended execution through the very end of the approved plan; the earlier time endpoint no longer applies.

## Baseline

- Source revision: `0edb3d79c3e62b5b2f03b9ff9754d40e6cb0a871`.
- Host: Linux `7.2.9-x64v3-xanmod1`, x86_64.
- Java: Oracle GraalVM `25.4.4.1.1+1.1`, HotSpot build `25.0.4.1.1+1-LTS-jvmci-25.4-b23`.
- Gradle: 9.6.1.
- Initial `unitCheck` failed before main tests because the root aggregate required `:portals:unitCheck` from a grouping project.
- Corrected root task selection is covered by an executable Gradle fixture, including configuration-cache reuse and preservation of all child gates.
- `./gradlew -p buildSrc verificationActionsTest`: passed after the recorded red regression.
- `./gradlew unitCheck`: passed; 121 actionable tasks, 6 executed, 115 up-to-date. Cached product checks are baseline evidence, not proof of future changes.
- Full current-change gate: `./gradlew build` passed; 272 actionable tasks, 104 executed, 168 up-to-date. An initially introduced constructor parameter-count warning was corrected without suppressing the ratchet or expanding its inventory.
- Full host kernel/fresh-JVM gates: `./gradlew integrationTest integrationTestFreshJvm` passed.
- Full local Docker gate: `MAZEWALL_CONTAINER_ENGINE=docker MAZEWALL_DOCKER_HOST=unix:///var/run/docker.sock ./scripts/run_tests.sh` passed in 6m21s after recovering the pre-existing root-owned generated native build directory.
- Ownership prevention is planned in existing backlog issue `issue-20260925-141818-005-prevent-root-owned-outputs-from-containerized-test.md`, including every producer, caller-mapped build writes, engine-volume-only root writes, caller-owned export, and failure/interruption ownership probes.

## Scoped commits on fast-master

- `8371e83a`: root verification aggregates select only real lazy child gates.
- `9bd2306e`: active empty filesystem intent survives reconstruction; permission snapshots are immutable; actual kernel fresh-open denial is verified in an isolated JVM.
- `9fdbc001`: profiler readiness/terminal waits use observable bounded conditions; workload pacing is identified explicitly.
- `d3e11915`: dedicated-worker profile conversion retains the chosen runtime through restrictive composition.
- `95417771`: real staged Java/Kotlin consumers, complete platform publication/API compile dependency, locked upstream Koog/Boot resolution probes, and review reconciliation.
- `c7b98801`: executable-mapping Boolean configuration honors false, preserving built snapshots; focused/full enforcer, ABI, fresh packaged consumers, and merge gate passed.
- `235b1acf`: validated operator-requested rootful build-output ownership prevention plan recorded in the existing issue.
- `5092fa7a`: profiler compile variants resolve the JARs named by its declared Kotlin friend paths; internals remain internal.
- `d41452e7`: fixed process/worker construction and explicit filesystem intent; Java-only construction removed, process/profiler/consumer callers migrated, ABI and actual-kernel tests updated.
- `1e0d862f`: retired list-spec facades and companion list forwarders; CLI/agent/web production policy construction now uses fixed-scope builders, preserving existing mapping settings.
- `a63c2d26`: completed every remaining caller migration and removed nested `Policy.Builder` and its factories; ABI, staged bytecode, host/kernel/fresh-JVM, packaged consumers, and full Docker gate verified.
- `61de4914`: recorded an independently observed helper owner-death procfs test-probe race; subsequent focused/full gates passed, and no helper lifecycle fix is claimed.
- `deef5f2a`: required Seccomp/Landlock installation evidence gates wrapped task entry even under configured bypass; twelve native fault cases, three receipt cases, corrected positive-state fixtures, host/build/kernel/fresh-JVM/Docker gates verified.
- `2622ca27`: installation attempts/Landlock sessions capture fallback once; receipts retain original failures and newly applied Seccomp/Landlock progress; cold failure-reporting types preload before containment. Eight regressions, host/ABI/consumer/build/kernel/fresh-JVM/Docker gates verified.
- `e6be7f03`: terminal thread/process installation quarantine survives wrapper/policy cache reuse, engine-state merging, and late publication; six host and registry-effect cases, a real-kernel failed-verification case, supervised failure identity, and full host/build/kernel/fresh-JVM/Docker gates verified.
- `b69a5a84`: inline CET correction: Linux status UAPI `0x5005`, no late FFM enable, explicit verified current-thread outcome/lock progress, retained backend/thread proof, permission-error and process-coverage rejection, safe native fixtures, and withdrawn unsafe research. Full host/ABI/consumer/build/kernel/fresh-JVM/Docker gates passed; positive native lock acceptance is explicitly prerequisite-skipped on the current inactive runtime.
- `e4a55943`: one pinned backend owns plain BPF packing/privilege/setup/retry/fallback/mode verification and the same attempt's CET branch. Three replacement regressions, CET handoff, strengthened architecture rules, one real-kernel capture test, host/ABI/quality/build/kernel/fresh-JVM gates passed; Docker passed on unchanged rerun after the recorded intermittent profiler failure.
- `14c7eb91`: Landlock session/ruleset native and typed capability capture, explicit path/FD retirement backend, and captured applied-ABI diagnostics. Six red-to-green host cases, one actual-kernel denial case, full host/ABI/quality/build/kernel/fresh-JVM/Docker gates passed.

- `c6c5e336`: supervised installation, daemon reuse authorization and cached task registration retain attempt native authority; notification support is captured at explicit installer entry. Two actual-kernel capture cases passed without skips; host/profiler/ABI/quality/build/kernel/fresh-JVM/Docker gates passed. Fresh daemon lifecycle ownership and unified context remain pending.

- `d3924b79`: recorded process-tree cleanup failures observed during concurrent host/container verification; the unchanged serial Docker rerun passed. Root cause is unproven; shared-checkout gates now run serially.
- `1f19d7e5`: optional self-verification captures the selected native backend and enablement before native installation; default real/mock classification belongs to the immutable snapshot. Two red-to-green host regressions, forced actual-kernel verification, full host/platform/ABI/quality/build/kernel/fresh-JVM and serial Docker gates passed. Unified context and verification memoization evidence scope remain pending.

- `4886a79a`: per-attempt provider selection binds real probes to the native owner and reaches support/Linux/CET/Landlock/USER_NOTIF plus plain privilege/TSYNC checks. Two red-to-green host cases, three native Landlock capture cases, host/ABI/quality/build/kernel/fresh-JVM and serial Docker gates passed.
- `ac25b168`: retained another process-wide profiler ACK-32 recurrence; unchanged serial Docker rerun passed, with no claimed profiler fix.
- `f47f651e`: removed program-global verifier proof reuse and obsolete reset seam; every enabled current verification probes its selected backend, and stacked checks require merged evidence. Four red-to-green cases and two-fresh-native-worker acceptance passed alongside full host/build/kernel/fresh-JVM/serial Docker gates.

- `baead3e9`: exact explicit/automatic classpath artifact grants, directory-only rights masking and required runtime-rule failures. Policy/native sibling-denial and permission-failure regressions passed; full host/build/kernel/fresh-JVM/serial Docker gates passed.
- `90c8fa5c`: daemon lifecycle backend and default owned transport capture; two red-to-green lifecycle/retirement cases and full platform/host/kernel/Docker gates passed.
- `48c5104e`: reusable immutable installation environment retained through dedicated preflight/install and explicit prepared-worker reuse; fallback/global replacement regressions plus full host/kernel/Docker gates passed.
- `4d0ca060`: independently retained another process-wide-profiler ACK-32 recurrence; unchanged full serial rerun passed.
- `5c369da1`: owned synchronous `ManagedRuntime`, finite `DomainLimits`, explicit process baseline, required worker readiness, separate minimal delivery/end stages, cancellation/drain and failed-generation retirement. Twelve host cases, pure lifecycle cases and actual-kernel baseline/two-worker denial passed; full gates passed with no warning-budget expansion.
- `ea0a8276`: canonical Callable-only `Mazewall.callContained(runtime, domain, action)`, exact active-domain inline transitions and retained caught evidence. Legacy top-level facade/aliases removed and Java/Kotlin consumers migrated. Host/native/full/Docker gates passed; candidate packaged uncast Callable and scope gates passed.
- `3fce7ee8`: exact committed-source packaged Java/Kotlin consumer baseline pinned to `ea0a8276`, including positive uncast Callable compilation and negative scope gates.

- `28dbb298`: strict versioned canonical worker/process policy artifacts preserve explicit filesystem intent, action data, runtime/flags and immutable permissions. Six host cases and serialized actual-kernel deny-all passed; full host/ABI/quality/build/kernel/serial Docker gates passed.
- `fd7548d1`: measured test/cache performance and declared local CET fixture source/toolchain/header/link/output inputs. Unchanged fixture compilation is UP-TO-DATE; independent Gradle fixture verifies source/header/compiler/output invalidation. Kernel result freshness remains unchanged; routine iteration now uses named host checks with broad gates only at coherent checkpoints.
- `0caf11f7`: reviewed operation documents retain exact configured adapter/implementation/resource generation, reported coverage and specific exceptions. External trusted digest review is required; document approval flags cannot grant authority. Approved worker documents integrate with owned preparation. Host/ABI/quality and one shared merge checkpoint passed; actual object identity resolution remains adapter/resource-binder work.

- `03f8cf25`: atomic stateful admission rejects overlap before entry and retains terminal unknown/fatal state loss.
- `81815e4f`: immutable Portal manifest/artifact/profile selections before lazy startup.
- `74b13574`: acquisition-before-construction broker ownership, close/startup joining and retained failed cleanup; fatal-first rollback. Portal host/quality and real broker checks passed; shared build passed in 1m43s.

- `65184179`: immutable finite Portal limits, shared configured deadline/transport bounds and pre-I/O overload rejection.
- `a92e248a`: lost stateful broker fails before maintenance and never recreates guest state.
- `7122b7f8`: public prepared typed Portal operations, bounded shared admission, cancellation and physical retirement end stages.
- `5672e44d`: portable format-2 opaque worker resources, bounded verified copying, explicit independent worker dependency closure.
- `3c41ede1`: shared test fixtures resolve the JARs named by their friend paths without widening internal visibility.
- `3b6c7b4b`: integrationTest* tasks are always fresh/non-cacheable; executable fixture preserves ordinary host reuse.
- `29843471`: container init reaps observed orphan zombies; original native assertions and timeouts remain unchanged.
- `245faa66`: admitted native polling observes interruption before/after readiness; deterministic reply-wait cancellation regression.
- `39cd5ce0`: required session/PID parent permit and acknowledgement precede guest initialization; legacy sentinel cannot substitute.

These commits exclude the operator's pre-existing tooling/configuration changes and six unrelated backlog entries. All forty-six commits passed the installed pre-commit audit/test hook. No remote push was performed.

## Execution scope

The revised C0–C9, P0–P5, S0–S3, and R0–R1 plan in the operator conversation is the implementation contract, amended by the no-legacy decision above. Source design and review are `2026-10-06-mazewall-integration-foundation-design.md` and `mazewall-api-findings.md` in this directory.

- [x] Repair aggregate verification selection and establish the host-unit baseline.
- [ ] C0: consumer/dependency baseline and review reconciliation. Independent staged Java/Kotlin consumers compile and inspect policies with a single enforcer dependency; canonical uncast Java Callable entry now compiles successfully. Missing platform publication and public compile dependency were repaired. Upstream Koog 1.3.0 and Boot 4.1.1 graphs resolved and locked; reconciliation is recorded in `docs/internals/reviews/2026-10-06-api-review-reconciliation.md`. Exact-source packaged API gates passed against `ea0a8276`. Stable-Kotlin/JDK22/native consumer gates remain pending.
- [x] C1: filesystem intent and immutable snapshots. Reconstruction and permission views preserve active deny-all; canonical versioned artifacts retain unrestricted/deny-all/resource state and fixed scope through round trip, with actual-kernel serialized deny-all acceptance and immutable decoded permissions.
- [x] C2: fixed-scope canonical builders and removal of obsolete construction APIs. Process/worker builders require explicit runtime/list mode and provide immutable results; worker mutations retain the receiver and Boolean settings assign both true and false. Every production/demo/host/kernel/compiler/consumer caller has migrated; Java-only construction, nested `Policy.Builder` and its factories, list specs, and companion list forwarders are removed. Raw definition configuration remains internal behind the same compiler. Host/profiler/demo suites, ABI, fresh packaged scope compiles, full build/kernel/fresh-JVM and caller-mapped Docker gates passed. Canonical artifact serialization remains C1/C6 closure.
- [ ] C3: captured required installation and effective feature outcomes. Required entry, immutable environment, failure/progress, quarantine and corrected CET evidence are implemented. Plain/Supervised Seccomp, CET and Landlock retain selected authority; daemon lifecycle/transport has its own captured owner. Current-installation verification never reuses program-global proof. The managed runtime retains environment and requires readiness/physical retirement. Positive native CET lock acceptance still needs a compatible pre-enabled runtime, and adapter/process application owners retain their separate gates.
- [ ] C4: exact static classpath artifact grants and actual sibling denial are implemented; complete rights, artifact/descriptor identity and prepared generation binding remain pending.
- [ ] C5: profile runtime preservation and corrected tested guidance. Runtime composition repair and full profiler host checks passed; guidance and profile-backed process acceptance remain pending.

The first fresh full enforcer run exposed a pre-existing profiler synchronization source gate failure that the cached baseline did not rerun. Its waits were corrected in a separate scoped backlog item, and both full enforcer and profiler host suites subsequently passed. This does not count as optional rootful collector acceptance.
- [ ] C6: canonical operation documents, configured identity/generation matching, external trusted review and specific coverage exceptions are implemented. Actual implementation/classloader/resource-object resolution and profiler export integration remain required closure.
- [x] C7: synchronous owned bounded runtime and invocation lifetime. Trusted manager/bootstrap, explicit permanent baseline, retained environment, fixed workers/admission, required readiness, physical end, cooperative cancellation and bounded drain are implemented and verified. Framework and process-domain owners remain their separate adapter/Portal scopes.
- [ ] C8: exact nested transitions and logical observed evidence are implemented and verified; framework-specific metadata capture/restore bridges remain integration work.
- [ ] C9: canonical Callable-only entry and legacy top-level facade retirement are implemented; remaining low-level caller-owned factory/wrapper surfaces still need deliberate migration/removal.
- [ ] P0–P4: Portal lifecycle, stateful admission, public limits, required readiness, and portable packaging.
- [ ] P5: upstream Koog adapter and packaged native consumer.
- [ ] S0–S3: synchronous Spring JDBC integration, optional Security, and packaged starter.
- [ ] R0–R1: distribution, compatibility, native gates, usability, and measured release evidence.

No integration release or native protection claim is complete until its own gates pass. Coroutine/WebFlux, JPA, brokers, workspaces, and general external process execution remain separately gated extensions.

## Portal finite-limits checkpoint

The configured worker/admission/deadline/request/response/capability bounds are implemented.
Focused host/real overlap/quality checks passed (`run-277eeb2019aa`). The initial merge
gate correctly exposed the late-acquisition fixture's old close/startup deadline coupling;
its close budget is now explicit and its unchanged retirement assertions pass
(`run-5cfdec508a66`). Full `./gradlew build` passed in 44 seconds (`run-2871266b9e50`).
Committed as `65184179`.

## Stateful terminal-retirement checkpoint

Runtime now forwards the immutable instance mode to the broker. Worker loss terminally
fails a stateful broker before maintenance; cleanup remains owned and stateless replacement
is preserved. Pure lifecycle and real timeout/child-reaping/no-recreation checks passed,
including a mutation check that reproduced the failure without terminal admission.
Full `./gradlew build` passed in 2m32s (`run-4f0e0fd8d0f3`).
Committed as `a92e248a`.

## Authoritative plan restored

The operator supplied the detailed C0–C9/P0–P5/S0–S3/R0–R1 contract. Its execution
copy replaces the superseded T00–T19 file at
`integrations/2026-10-06-mazewall-integration-foundation-plan.md`, preserving acceptance
criteria and applying later explicit inline/no-worktree/no-legacy decisions. P2 still
requires a public prepared-operation API, cancellation and physical retirement evidence;
configured synchronous limits alone do not close it. P3/P4 remain required before Koog.

## P2 owned prepared-operation checkpoint

Public typed service preparation and submission now return the shared minimal handle.
Prepared/synchronous calls share finite admission, submit-time absolute deadlines,
queued cancellation and separate physical end. Failed/cancelled exact-worker retirement
is retained through pre-native cleanup failure and broker close retry. Mapping/encoding
remain host work; broker substitution/local fallback and operation self-close reject.
Pure lifecycle/host/full public configuration and actual request-progress cancellation
with delayed socket cleanup passed (`run-cb0dfbf279cb`). Full `./gradlew build` passed
in 1m36s (`run-747f93371827`). Required generation-bound readiness and generated packaged
consumer proof remain P3–P5, not inferred from the typed builtin integration fixture.
Committed as `7122b7f8`.

## P4 portable metadata/closure checkpoint

Format2 opaque resource/digest metadata replaces build-local paths; bounded transactional
resource copying and shared cache ancestor checks precede the existing ZIP extractor.
Worker dependencies no longer inherit host implementation/runtime-only closure. Real
TestKit host-only JAR leakage was reproduced, then portable resolution succeeded after
deleting the original build directory. Full build passed in 2m7s (`run-8af34f0ff7ce`).
This is packaging evidence; generated native relocated execution remains P3/P5/R0 proof.

P3 inspection also confirmed missing parent authorization: worker construction can race
parent cgroup attachment despite earlier design wording. Recorded in
`issue-20261007-214327-867-require-parent-startup-authorization-before-portal.md`;
required parent permit/acknowledgement is now implemented as `39cd5ce0`. Full
artifact/manifest/policy-generation readiness and operator-owned launch profiles remain.

## Fresh native verification reconciliation

The first new Docker command (`run-cef27802bf57`) succeeded but Portal integrationTest
reused host outputs. That result is aggregate success, not fresh Portal container proof.
The root convention now enforces fresh integrationTest* tasks and an actual Gradle
fixture checks repeated execution/cache restoration while retaining ordinary host reuse.

Fresh replay exposed shared-fixture JAR visibility, cancellation inside an already
admitted response poll, and orphan zombies under container PID 1. The fixture uses
matching JAR variants; Portal polling now checks interruption before/after readiness;
the runner uses engine init/reaping. Native process-state probes established the serial
zombie case and were removed. No permissions, assertions or timeouts were weakened.

Final shared fresh host `./gradlew build` passed in 4m29s (`run-87b7f35d556f`);
fresh serial mapped Docker full gates passed in 2m38s (`run-a7557f9c33a8`). Parent
authorization acceptance executed three tests with zero skips/failures in Docker.
Positive CET locking remains prerequisite-unavailable on the inactive runtime.

## Next C3 reconciliation

## Fleet noon handoff — 8 October 2026

Subsequent override at12:07CEST: operator requestedcontinuedworkuntilatleast12:00
unlesshardblocker. Thatminimumalreadyelapsed; preservedfleet tasks areresumed under
sameisolation/review/verification rules, ratherthanguessing a newnext-daydeadline.

Operator-approved fleet executed from02:03 through12:00CEST with private writer
worktrees, checkpoints/session IDs, independent scoped reviews and serial integration.
Admission is closed at cutoff. CurrentintegratedHEAD92da1536 includes reviewedP3
generation/receipt ownership, ordinary-JAR/pinned resource identity, S0/S1/S2host/JDBC
and actualKoog/generatedpublishedconsumer/genuineJVMoperationroot. StableKotlin2.4.20
and actualJDK22/JDK25 externalcorechecks passed. Git-hook foreign-repository corruption
was diagnosed/repaired without discardinguseredits; disposable regressions pass.

LatestBoot fixes1ff84447/511fc40c andSpringtypednative/Security fixes
5dd10ab0/9932b147/b9fed4b0 remain private, awaitingfixreviews; not integrated or
releaseaccepted. KoogproductionwiringHEAD22e8e264 has8verifiedfixfiles staged because
fullcommitgateadmissionclosed atnoon. No workdiscarded orremoteoperations performed.
Finalcombinedbuild/Docker/candidategates pending; individualpasses aren't aggregateproof.
Recovery ledger: `.superpowers/sdd/2026-10-06-mazewall-integration-foundation-plan/progress.md`.

`ManagedRuntime` retains one captured environment for explicit baseline and owned workers. Required readiness and physical failed-generation retirement are verified. The daemon captures its lifecycle backend/transport independently of caller authorization. Program-global verification proof reuse is removed. Standalone diagnostics retain live selection and are not prepared-runtime evidence. Remaining closure includes positive CET runtime prerequisites, framework/Portal ownership and immutable artifact/resource generation binding.
