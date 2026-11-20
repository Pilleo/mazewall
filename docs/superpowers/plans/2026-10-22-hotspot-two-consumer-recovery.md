---
document_type: execution_plan
base_revision: a28cbe293289f2549858aa232d9e6ebd7c4756dd
status: source_implemented_host_unit_verified_native_and_release_blocked
---

# OTel HotSpot Two-Consumer Implementation Plan

> **For agentic workers:** Use `executing-plans` for serial implementation in the current checkouts. Use `subagent-driven-development` only if the operator selects delegation. Steps use checkbox (`- [ ]`) syntax for tracking. Do not create worktrees, commit user-owned changes, or publish automatically.

**Goal:** Finish ordinary-JIT/G1 Java syscall-stack attribution in MazeWall and Kubescape by reusing the existing OTel-based collector, shared bridge, node-agent companion and storage implementation.

**Architecture:** OTel already performs external HotSpot unwinding/symbolization; preserve its inline-frame and stale-JIT checks and the existing syscall-event correlation. Add an explicitly observational Kotlin entry point without changing strict entry points. Node-agent already consumes the observed profile: finish dependency integration and acceptance, not another companion or symbolizer.

**Tech Stack:** Existing pinned Gadget/OTel Go collector; published hotspot-bridge v0.2.0; JDK25/Kotlin profiler; Go1.27 node-agent; existing typed Kubescape storage API.

**Spec:** [Observed collector contract](../../plans/2026-10-18-hotspot-observed-profile-contract.md), [Kubescape design](../specs/2026-10-18-kubescape-agentless-hotspot-design.md), [existing consumer plan](2026-10-18-node-agent-hotspot-companion.md). The additive Kotlin API below is the proposed implementation of the approved recovery direction; old strict-only Kotlin documentation is not an observed-API implementation. This plan replaces the recovery outline, not historical evidence or the unresolved strict-qualification ledger.

## Global Constraints

- Linux-amd64; initially Temurin25.0.4.1+1-LTS. Observed means ordinary tiered compilation/G1, `otel_hotspot25_observed_v1`, schema3, capture ABI2, collector version3. Strict remains `temurin25_xint_serial`, schema2, collector version2, `-Xint -XX:+UseSerialGC`.
- Every schema3 row has explicit `policy_eligibility:false`. Whole-collection coverage remains incomplete. A drained selected stream is not complete policy evidence.
- Readiness <=10seconds, session <=60seconds, stop <=2 remaining seconds, event enrichment deadline1second; no larger worker/pending/native buffer budgets.
- Kotlin retention4096 occurrences/33,554,432 wire-payload bytes, max line1,048,576bytes, max wire rows65,536, max frames4096, max tracked occurrences32,768.
- Native enrichment workers4, pending events1024, pending payload16MiB, producer LRU256, frame cache16384, frame data3072words and perf data524288bytes/possible CPU remain fixed.
- Rehash every asset byte and validate the complete local OCI graph. Preserve existing provenance pins and corruption/path/media checks. No metadata-only hash cache.
- Never swallow/downgrade EPERM/EACCES. Missing/evicted metadata stays unavailable; no substituted later stack. Preserve original collector/read/Wait errors and pending cleanup owners.
- No breaking public APIs, new library dependencies, startup compiler/download/sudo, native algorithm rewrite, JVM flag workaround, or Gadget graph unification. Shared bridge v0.2.0 is already published; never retag/release it again.
- Preserve the dirty MazeWall checkout and external Tier E prototype. No worktrees, resets, broad staging, automatic commits, pushes, unapproved deployments/signals, or historical-resource queries.
- No more checked-HOST Gradle instrumentation, replacement HOST pairs, approval-loop retries, or new execution-monitoring/replay platform. Existing failed evidence, consumed budgets and resource obligations remain intact.
- This plan does not renew native/Docker/cluster/fullgate execution authority. Commands below describe future checks, not permission to run them under the expired HOST slots. Prepare source and exact runtime requirements first; request one concrete execution decision when needed.

## Verified starting point — do not redo completed work

### Inline execution checkpoint (2026-10-22)

- Implemented MazeWall's explicit `observeHotSpot` path, schema3 target/provider/row/accounting validation, typed compound native causes, and diagnostic provenance/false-policy preservation. Existing strict selection and public constructor descriptors were left unchanged.
- Added synthetic wire and disposable-Java-child regressions. Actual RED checks reproduced missing schema3 admission, missing target/accounting checks, manifest/profile mismatch, missing provenance, owner reader fixed to schema2, and omitted required zero-valued fields. Connected HotSpot unit checks then passed; native/installed-asset cases remain explicitly skipped, not accepted.
- Operator explicitly authorized normal focused Kotlin/HotSpot host checks, including standard build dependencies/BTF generation and disposable test children. Native profiling, real collector execution, Docker, Kubernetes, fullgate and publication remain excluded. No old checked-HOST slot or candidate was used.
- Node-agent now pins published bridge v0.2.0 with sums produced by the real cached Go1.27.0 module resolver (`GOWORK=off`, `GOTOOLCHAIN=local`, `GOPROXY=off`, `GOSUMDB=off`). Corrected its stale interpreter-only documentation. Preserved the dirty Tier E prototype and all other existing work.
- **Task3 remains partial:** storage v0.0.303 has no `FrameSymbol`/`CallStackEvidence`; a remotely available reviewed typed-storage revision is still required for the normal released graph. No storage version, publication or node-agent test/build success was invented.
- **Tasks4–5 remain unexecuted:** no fresh native/cluster authority or selected release graph. The old integration harness still has the previously identified acquisition/cleanup limitations; no new runner or broad fixture-owner framework was written to bypass them.
- **Task6 remains partial:** scoped connected-code review and documentation checks apply only to this source slice; ABI/static/full-build/native/persisted-profile gates are not implied by unit success. No commit, push or deployment was made.

### Continuation checkpoint (2026-10-23, host checks only)

- Added the opt-in direct-host `HotSpotObservedIntegrationTest` in **`src/test`**, reusing the unchanged400-operation/four-worker/20ms Java workload. The larger reviewed remote-launch helper requires a prepared-launch ownership stack; it was inspected, not copied. The new test needs no Docker, sudo, launcher prefix or remote container acquisition. Its source is compiled, but its native body has not run.
- Test-only identity discovery reads bounded proc membership, exact nonescaping unified paths, the visible cgroupv2 inode, an exact nonzero mount-namespace link and raw birth before/after discovery. Pure parser regressions pass. The fixture retains scratch and holds target release while collector ownership is pending; interruption status is preserved during cleanup.
- Fixed the new Gradle configuration-cache failure by capturing a **task-local Boolean**, not a script-level provider, in cache predicates. The parser-test invocation now stores the configuration cache. Explicit native selection disables test-result reuse/cache.
- Both Kotlin/legacy ABI checks, main-source Detekt, all profiler ktlint source sets and main SpotBugs pass. The ABI baseline contains only the reviewed additive target/method/evidence getters. Formatting corrections are scoped to this task's Kotlin files. Final connected HotSpot host run: **113tests,108passed,5explicitly skipped,0failures/errors**,22XML reports. Log: `/tmp/opencode/hotspot-host-final-20261023.log`. The native test remains one of the skipped cases, not a passing acceptance.
- Scoped connected-code review caught definition-reader diagnostic fields whose presence, but not known scalar types, was checked. A focused RED reproduced acceptance of negative loss; the minimal decoder correction plus malformed/overflow/unsigned-max controls now pass. No loss-completeness claim or strict/schema1 rule was broadened.
- Additional `:profiler:detektTest` fails on **16existing error-level findings outside this task's HotSpot files**. They are recorded in [the scoped maintenance issue](../../internals/backlog/issue-20261022-221346-528-restore-profiler-test-detekt-gate-without-weakenin.md); no rules, baselines or unrelated fixtures were changed. Existing warnings, including the opt-in test's complexity warning, remain visible. These source checks are not a clean full quality gate.
- Node-agent's verified image-context copy, CLI and optional derived Dockerfile are implemented. Offline packaging `go test -race ./examples/agentless-hotspot-sbob/... -count=1` passes, including unlisted-file exclusion; `go vet` passed. No image was built or container/cluster contacted.
- Added the separate N `examples/agentless-hotspot-sbob/verify` package and Linux `cmd/verify` offline command. They replay bounded original schema3 JSONL with published bridge v0.2.0, recompute canonical CallIDs, and independently check the actual saved ContainerProfile JSON's identity, ordered typed symbols/optional locations/BCI/unsigned positions/provenance. The verifier never calls node-agent's projector; tests use its real projection and mutate saved results independently. A second RED caught old-client Boolean pruning being silently decoded as false; both explicit false fields are now required in the original JSON body. Inputs are bounded regular/no-symlink/non-FIFO files, with no rewrite or cluster contact.
- The owned cluster runner, actual API retrieval/custody and native ownership proof still need implementation/qualification. The offline checks and asset preparation do not satisfy those steps. Actual server/node-image/context receipts remain unselected; none were fabricated to start cluster work.
- Found and corrected the acceptance readiness gap: controller `Running` precedes native ready, while the old capture files were final-only. The real collector adapter now feeds only SDK-accepted ready rows to the existing private/no-replace writer. A distinct16KiB `collector_ready` receipt binds independent target + actual session/lifecycle without certifying completion or a lease. It shares the unchanged256MiB quota, existing reader/Wait owner,10s/60s/2s bounds and error tree; no new config, public SDK API, native helper, monitor or buffer was added. See [the open integration issue](../../internals/backlog/issue-20261022-223621-210-persist-decoder-accepted-hotspot-readiness-before-.md).
- Host RED/GREEN covers missing readiness output, pre-final controller publication, wrong/missing ready rejection, EACCES persistence failure/reaping and a blocked ready callback retaining its reader until explicit cleanup, including the later EACCES. Full companion + acceptance-source race checks pass (`/tmp/opencode/hotspot-companion-ready-race-20261023.log`); the added blocked-reader check separately passes (`/tmp/opencode/hotspot-ready-pending-reader-20261023.log`). Go vet and formatting pass for the source slice. Standalone `GOWORK=off` preparation tests still pass without typed storage. The updated development binary builds at `/tmp/opencode/hotspot-node-agent-development-ready-20261023` and was not run.
- Added the offline400-operation/four-worker corpus check and optional Linux verifier `--oracle` input. Exactly400 marked pairs, at least360 useful correct routes, zero wrong TIDs/routes/order, duplicate/extra tokens and truncation checks now have RED/GREEN regressions. Namespace worker TIDs are translated only using independently supplied bounded status chains; missing/empty/duplicate/foreign/relative/ambiguous/overflowing identities are rejected. The original corpus race run passes (`/tmp/opencode/hotspot-corpus-green-20261023.log`,20.673s). CLI regression reproduced the unsupported flag and an explicit-empty-flag downgrade; both are corrected. See [the open namespace-custody issue](../../internals/backlog/issue-20261022-230122-825-bind-hotspot-workload-oracle-tids-across-pid-names.md).
- Complete example race checks including the400-operation CLI positive and wrong-namespace-TID negative pass in `/tmp/opencode/hotspot-corpus-cli-green-20261023.log` (CLI7.303s, verifier21.810s). Scoped review then reproduced intermediate namespace worker collisions and leader/init IDs being accepted despite valid endpoints; every namespace level is now checked, retaining a valid nested-chain control. No native or cluster acquisition is implied by synthetic status bodies.
- Final race checks for the latest acceptance-example revision pass in `/tmp/opencode/hotspot-corpus-final-20261023.log` (CLI7.346s, verifier21.282s); Go vet/gofmt, local documentation links, diff whitespace and backlog validation pass. Backlog now records156active issues (155open,1deferred); the namespace-custody item remains open. Full build/fullgate and runtime acceptance are not covered or reclassified by these checks.
- Worker status acquisition is still unimplemented: it must precede `measure`, independently select the gated workers, bracket raw process birth, and establish initial-namespace procfs origin. Consistent namespace fields alone are not global host-ID proof. The canonical `ObservedWorkload.java` was copied byte-for-byte into the node-agent example (`cmp` passes) and compiled with host javac25.0.4.1.1 into `/tmp/opencode/hotspot-workload-compile.pTGXO7`. Its plain UID1000/PID1 Dockerfile is source only: no image build/pull/workload/native execution occurred.
- The full companion race suite and connected HotSpot profile/cache/config/cmd checks pass in `/tmp/opencode/hotspot-two-consumer-20261022.go.work`. A normal `go build ./cmd` also passes **in that development workspace**, producing `/tmp/opencode/hotspot-node-agent-development-20261023`; the binary was not run. The workspace selects published bridge v0.2.0 and local reviewed storage4c2e1cf1, not a local SDK replacement.
- Reproduced the normal **`GOWORK=off` build failure**: storage v0.0.303 lacks `IdentifiedCallStack.Evidence` and `StackFrame.Symbol`. No new release pin or publication is invented. Storage source remains unchanged; the local workspace is not released-graph evidence.
- Read-only remote inspection on2026-10-23 found storage v0.0.304 at `c87618e6b701d9b48615f65ad2548092f4fe5d5f` and HEAD `e3186031c85a15ef13b5f46d45f933d6f5ac8733`; the requested typed branch was absent from `ls-remote`. Both immutable GitHub `pkg/apis/softwarecomposition/v1beta1/types.go` responses (HTTP200) lack `FrameSymbol`, `CallStackEvidence`, and their fields. The new tag does not resolve the typed release blocker. No dependency pin, fetch/checkout or publication was made.
- Native observed acceptance, persisted-profile acceptance, full build/fullgate, publication and old strict-eight/custody obligations remain unexecuted or blocked exactly as before. No consumed HOST slot/candidate was resumed.

| Area | Source inspected on 2026-10-22 | Remaining work |
|---|---|---|
| Collector/bridge | Existing admitted schema3 implementation and published v0.2.0; accepted six-case collector evidence | Reuse matching artifacts/evidence; rebuild only if inputs change |
| MazeWall | `HotSpotOptions` says strict; `HotSpotAssets` accepts only2/2; `HotSpotBackend` launches schema2; reader/workers/protocol fix schema2 | Explicit observed entry point and end-to-end Kotlin schema3 validation/mapping |
| Node-agent | `cmd/hotspot.go:60` verifies `wire.Observed25`; `supervisor.go:169–175` launches schema3 with independent target tuple; `project.go` emits false-policy typed evidence | Released SDK/storage graph, normal build and actual native/persisted-profile acceptance; documentation correction |
| Storage | External `docs/agentless-stack-evidence.md` records successful local JSON/protobuf API gate against46c8444a | Published contribution/version and deployment provenance, not another schema implementation |
| Release graph | Node-agent `go.mod` still selects storage v0.0.303 and contains no hotspot-bridge requirement | Add the real SDK pin; select a real schema-capable storage revision, never invent a version |

Historical local handoff JSON still lists SDK publication as missing; that part is superseded by the published v0.2.0. Preserve those historical files rather than editing their evidence. The storage publication gap is not superseded.

## File/responsibility map

Paths are relative to MazeWall unless prefixed with N or S:

```text
M=/home/leanid/Documents/code/java/jseccomp
N=/home/leanid/Documents/code/java/stack-attribution/node-agent
S=/home/leanid/Documents/code/java/stack-attribution/storage
```

| Files | Treatment |
|---|---|
| `profiler/src/main/kotlin/io/mazewall/profiler/api/HotSpotObservationTarget.kt` | New immutable independently supplied PID/birth/cgroup/mount tuple |
| `profiler/src/main/kotlin/io/mazewall/profiler/api/MazewallProfiler.kt`, `profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotPublicBridge.kt` | Add `observeHotSpot(target, options)` returning the existing session type; keep existing factory/strict methods compatible |
| `profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotCaptureExpectation.kt` | Small closed internal strict/observed request; source of schema/profile selection |
| `profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/{HotSpotAssets,HotSpotBackend}.kt` | Explicit observed manifest admission and argv; existing byte/OCI verifier and process owner reused |
| `profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/{HotSpotProtocol,HotSpotWireReader,HotSpotWireMapper,HotSpotEvidenceAssembler}.kt` | Schema3 validation, pairing, accounting and unchanged frame mapping |
| `profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/{HotSpotCollectorProcess,HotSpotCollectorWorkers,HotSpotSessionInterpreter}.kt` | Thread expectation through existing reader/interpreter, not a new lifecycle machine |
| `profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/{HotSpotEvidence,HotSpotEvidenceDocument}.kt`, `profiler/src/main/kotlin/io/mazewall/profiler/api/StructuredProfileEvidence.kt` | Preserve negotiated profile in structured diagnostics and serialization |
| `profiler/src/main/kotlin/io/mazewall/profiler/api/HotSpotProfilingSession.kt` | Preserve compound native causes in the existing public exception, without changing its session lifecycle |
| `profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotObservedProtocolTest.kt`, `HotSpotObservedConsumerTest.kt` | New focused schema3 regressions |
| Existing `HotSpotAssetsTest`, `HotSpotProtocolTest`, `HotSpotChildFixture`, `HotSpotBackendTest`, `HotSpotCoverageTest`, compatibility/lifecycle tests | Extend only affected cases; preserve strict controls |
| `profiler/src/test/resources/hotspot/observed-consumer.jsonl` | Small synthetic full session, explicitly not native qualification |
| `profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotObservedIntegrationTest.kt`, `HotSpotObservedTargetIdentity{,Test}.kt` | Explicitly gated direct-host public check and test-only independent identity discovery; existing workload reused |
| N `go.mod`, `go.sum`, `docs/agentless-hotspot.md`, existing HotSpot tests | Dependency integration, targeted missing regression, accurate documentation |
| N `pkg/stackattribution/hotspot/{supervisor,project,controller,capability}.go` and profile publication/cache files | Reuse; change only a reproduced defect |
| S existing schema/codegen/aggregation/tests | No new schema work expected; use current reviewed implementation |

## Task 1: Negotiate schema3 in the existing Kotlin decoder

**Interfaces:** Existing `HotSpotWireReader.read(InputStream, consume)`, `HotSpotProtocol.controlEvent(HotSpotWireRecord)`, `HotSpotEvidenceAssembler.begin()` and immutable frame evidence remain the processing pipeline. Add these types:

```kotlin
// api/HotSpotObservationTarget.kt
public data class HotSpotObservationTarget(
    public val pid: Pid,
    public val processStartTicks: ULong,
    public val cgroupId: ULong,
    public val mountNamespace: ULong,
) {
    init {
        require(pid.value > 0)
        require(processStartTicks > 0uL && cgroupId > 0uL && mountNamespace > 0uL)
    }
}

// internal/hotspot/HotSpotCaptureExpectation.kt
internal sealed interface HotSpotCaptureExpectation {
    val schema: ULong
    val runtimeProfile: String

    data object Strict : HotSpotCaptureExpectation {
        override val schema: ULong = 2uL
        override val runtimeProfile: String = "temurin25_xint_serial"
    }

    data class Observed(val target: HotSpotObservationTarget) : HotSpotCaptureExpectation {
        override val schema: ULong = 3uL
        override val runtimeProfile: String = "otel_hotspot25_observed_v1"
    }
}
```

Imports: `Pid` is `io.mazewall.platform.lifecycle.Pid`; public target is `io.mazewall.profiler.api.HotSpotObservationTarget`. Keep the existing PID/birth constructor parameters for strict callers. Append `expectation: HotSpotCaptureExpectation = HotSpotCaptureExpectation.Strict` to `HotSpotProtocol`; validate observed expectation PID/birth against those parameters.

- [ ] **Step 1: Add the request types and the synthetic session resource.** Copy no native captures or mutable external files into tests. The resource has five JSONL rows: started, ready, event, enrichment, summary. Use PID123/birth456/cgroup789/mount42, session`observed`, event`observed:0:1`, provider pins from `HotSpotProtocol.kt`, and the pinned JVM build/SHA. These are synthetic input values, not claims about a running process.

Required ready payload:

```json
{"pid":123,"process_start_ticks":456,"cgroup_id":789,"mount_namespace":42,"clock_domain":"CLOCK_BOOTTIME","boot_ns":101,"runtime_profile":"otel_hotspot25_observed_v1","validation_mode":"upstream_generation_checks","jvm_build_id":"e4dad22744e32e4d71755f4a58c5cf1f1d336f87","jvm_sha256":"5e0fb6e83a5676090f28ff0b453e9199df06af1ff90d473c1b9837e41096114c","provider":{"name":"otel-ebpf-profiler-via-gadget","gadget_revision":"7097db144ccd8dab15373f27ec98d2c3d22e4588","profiler_revision":"ef6cf29492c8a980f889052903fa2dd0c0765cc0","patch_sha256":"89b3599935df5206ff710292574e4480a09da535f8529b4b0462b8c29a7e0f50"}}
```

Started uses the same PID/clock/profile/provider, boot_ns100, without ready-only fields. All outer rows include `schema_version:3`, `policy_eligibility:false`, `session_id:"observed"` and their kind; only event/enrichment carry event_id.

Use this event payload, retaining integer precision:

```json
{"session_id":"observed","event_id":"observed:0:1","cpu":0,"sequence":1,"host_pid":123,"host_tid":124,"process_start_ticks":456,"cgroup_id":789,"capture_boot_ns":102,"clock_domain":"CLOCK_BOOTTIME","event_kind":"openat_handler_entry","correlation_id":1,"dirfd":-100,"flags":0,"mode":0,"filename":"/synthetic","filename_bytes":"L3N5bnRoZXRpYw==","register_read":0,"argument_read":11,"path_truncated":false,"hotspot_identity":{"state":"unknown"}}
```

Enrichment has `attribution:"event_correlated"`, `walk:"returned"`, `scope_lookup:"mixed"`, the same provider, unknown HotSpot identity, and these leaf-first frames:

```json
[{"provider_type":6,"frame_type":"jvm","function_name":"leaf","source_line":42,"bci":0,"raw_position":9007199254740993,"position_domain":"provider_hotspot_bci_or_fallback","error_frame":false},{"provider_type":6,"frame_type":"jvm","function_name":"caller","raw_position":18446744073709551615,"position_domain":"provider_hotspot_bci_or_fallback","error_frame":false}]
```

Summary uses the actual fields in `hotspot-bridge/wire/record.go`: enrichment received1/resolved1/unavailable0/skipped0/max_pending1/max_pending_payload_bytes10, all recovery counters0; terminal accounting true; collection_health partial; the four unproven lanes definitions/enrichment/wire/retention; selected accounting scope owned_openat_handler_entry, schema1, same session/PID/birth, map_id7, possible_cpus1, attempted/submitted/received/terminals1, reserve_failed0, complete true. Event finalization is owned_openat_event_ring/resources_retired/request with all five join/drain flags true, native_reserve_failures0, background_loss_sampler_started false and loss_complete false. Definition stop has all four flags true and semantics owned_definition_perf_drained_partial_loss_snapshot. Event loss observation is observed0/complete false/semantics quiescent_native_reserve_failures_partial_snapshot. Definition reader observation is buffer_bytes_per_cpu524288/complete false/semantics consumed_trace_perf_records_partial_snapshot. No global event_loss/definition_loss total.

- [ ] **Step 2: Write failing reader/protocol tests.** In `HotSpotObservedProtocolTest`, define a local reader helper and expected target:

```kotlin
private val target = HotSpotObservationTarget(Pid(123), 456uL, 789uL, 42uL)

private fun rows(text: String): List<HotSpotWireRecord> = buildList {
    HotSpotWireReader(HotSpotReplayLimits(), 3uL)
        .read(text.byteInputStream()) { add(it) }
}

private fun session(): String = requireNotNull(
    javaClass.getResourceAsStream("/hotspot/observed-consumer.jsonl"),
).bufferedReader().use { it.readText() }

@Test
fun `schema3 requires explicit false policy eligibility on every row`() {
    assertEquals(5, rows(session()).size)
    for (bad in listOf("true", "null", "0", "\"false\"")) {
        assertFailsWith<HotSpotProtocolException> {
            rows(session().replace("\"policy_eligibility\":false", "\"policy_eligibility\":$bad"))
        }
    }
    assertFailsWith<HotSpotProtocolException> {
        rows(session().replace("\"policy_eligibility\":false,", ""))
    }
}

@Test
fun `ready must match independently supplied target and upstream validation mode`() {
    val protocol = HotSpotProtocol(123, 456uL, HotSpotCaptureExpectation.Observed(target))
    val ready = rows(session()).single { it.kind == "collector_ready" }
    assertIs<HotSpotSessionEvent.Ready>(protocol.controlEvent(ready))
    for ((from, to) in listOf(
        "\"cgroup_id\":789" to "\"cgroup_id\":790",
        "\"mount_namespace\":42" to "\"mount_namespace\":43",
        "upstream_generation_checks" to "safepoint_epoch",
    )) {
        val changed = rows(session().replace(from, to)).single { it.kind == "collector_ready" }
        assertFailsWith<HotSpotProtocolException> { protocol.controlEvent(changed) }
    }
}
```

Add table cases for wrong birth/PID/profile/provider/build, mixed schema, true completeness claims, malformed selected accounting and orphan/duplicate terminals using the same resource. Retain existing schema1 replay/schema2 tests unchanged.

- [ ] **Step 3: Run the focused RED check when host-test execution is authorized.**

```bash
./gradlew :profiler:test --tests 'io.mazewall.profiler.hotspot.HotSpotObservedProtocolTest'
```

Expected initial failure: schema3 rejected or new expectation constructor not yet wired. A missing native dependency/toolchain is a blocked check, not RED evidence.

- [ ] **Step 4: Implement schema/profile selection in existing reader/protocol.** Reader accepts an explicitly selected3, never auto-detects. Schema3 requires a present Boolean false policy field; schema2 rejects that field. `HotSpotProtocol` uses the selected profile and retains the original strict provider/JVM pins. Ready binds PID/birth/cgroup/mount/validation mode; events bind PID/birth/cgroup. Validate observed provider identity on enrichment, not only lifecycle. Do not claim an epoch lease for observed frames.

Port the observable validation rules from existing `wire/decoder.go:{recordShape,lifecycle,validateEvent,terminal,recovery}` and `wire/validation.go:validateSummary`, not OTel unwinding code. Include event sequence/ID/correlation binding; one recovery per original terminal with matching correlation; checked non-overflowing aggregate equations; exact selected-accounting target; final-loss consistency; explicit four unknown lanes; no global-completeness claim. Apply new rules to the observed path without silently changing strict/schema1 compatibility.

Preserve compound `collector_error` causes: optional errno_causes has1–3 unique Linux errnos1..4095, primary first, additional causes only EPERM/EACCES; reject null/empty/invalid/schema2 uses. Extend `HotSpotCollectorFailure` with an immutable cause list defaulting to its existing primary and flatten it in `HotSpotProfilingException.from`; keep existing nativeErrno access compatible.

- [ ] **Step 5: Run GREEN plus the existing protocol/replay regressions.**

```bash
./gradlew :profiler:test --tests 'io.mazewall.profiler.hotspot.HotSpotObservedProtocolTest' --tests 'io.mazewall.profiler.hotspot.HotSpotProtocolTest' --tests 'io.mazewall.profiler.hotspot.HotSpotWireReaderTest' --tests 'io.mazewall.profiler.hotspot.HotSpotEvidenceAssemblerTest'
```

**Deliverable:** Schema3 rows validated against an explicit expected tuple, with strict/replay behavior retained. No child process or native collection is required for these parser checks. Review this task before wiring live sessions.

## Task 2: Expose the observed MazeWall session without changing strict APIs

**Interfaces:** Add `MazewallProfiler.observeHotSpot(target: HotSpotObservationTarget, options: HotSpotOptions): HotSpotProfilingSession` to its companion. Keep `HotSpotOptions` constructor descriptors, `HotSpotSessionFactory.attach`, `MazewallProfiler.open/profile/attach`, AUTO and the public session type unchanged. Add `HotSpotBackend.observe(target, options: HotSpotAttachOptions): HotSpotProfilingSession` internally; `RealHotSpotSessionFactory.observe` wraps it using the same exception translation as strict attach.

- [ ] **Step 1: Add RED asset-admission cases to `HotSpotAssetsTest`.** Keep its current fixture/checksum/OCI graph helper. Add a profile argument to that helper so it can write the3/3 manifest without changing artifact bytes. Append an internal `expectation` parameter with a strict default to `HotSpotAssets.load` overloads and `HotSpotAssetManifest.validate`.

```kotlin
@Test
fun `observed manifest requires explicit observed admission`() {
    fixture()
    val manifest = directory.resolve("manifest.json")
    Files.writeString(manifest, Files.readString(manifest)
        .replace("\"collector_version\":\"2\"", "\"collector_version\":\"3\"")
        .replace("\"wire_schema\":2", "\"wire_schema\":3"))
    assertFailsWith<IllegalArgumentException> { HotSpotAssets.load(directory) }
    HotSpotAssets.load(directory, expectation = HotSpotCaptureExpectation.Observed(
        HotSpotObservationTarget(Pid(123), 456uL, 789uL, 42uL),
    ))
}
```

Also reject2/3 and3/2 pairings and observed selection against a strict manifest. Existing tampering, no-symlink, byte-budget and media-conflict cases still run.

- [ ] **Step 2: Write a failing argv/ownership regression in `HotSpotBackendTest`.** Extend `HotSpotChildFixture.argv` with `observed: Boolean = false`; observed mode writes the Task1 resource into the existing fixture file order (started, ready, event, enrichment, summary, error), appending a synthetic schema3 permission error as the sixth row for error modes. Keep the Java fixture and strict mode behavior. Reuse its current `ProcessLauncher` seam; no native helper invocation.

The observed launch must contain these literal arguments:

```kotlin
listOf(
    "--protocol-version=3",
    "--runtime-profile=otel_hotspot25_observed_v1",
    "--hotspot-identity=unknown",
    "--expected-start-ticks=456",
    "--expected-cgroup-id=789",
    "--expected-mntns=42",
    "--control-stdin",
    "--drain-full",
)
```

Assert no safepoint-epoch option, no shell/string joining, unchanged operator prefix, split stdout/stderr, and no child acquisition when the independent birth check disagrees. Success must finish with one retained pair and pendingOwnership false; existing fake-child failure/late-exit tests must continue rejecting failed captures. Use a tiny synthetic asset fixture, not a required installed native bundle; reuse the assets-test fixture construction rather than a second OCI verifier.

- [ ] **Step 3: Run the two focused classes to demonstrate RED, then wire the existing owner.**

```bash
./gradlew :profiler:test --tests 'io.mazewall.profiler.hotspot.HotSpotAssetsTest' --tests 'io.mazewall.profiler.hotspot.HotSpotBackendTest'
```

`observe` verifies the observed asset pairing, reads current birth through `HotSpotTargetIdentity`, rejects disagreement with the supplied target, and passes its immutable expectation into `HotSpotCollectorProcess.attach`. Append that parameter with a strict default to the internal owner/interpreter constructors; pass `expectation.schema` into the workers' existing stdout reader. Pass the same expectation to the protocol and accumulator. Retain the existing machine, driver, timers, pipe ownership and cleanup behavior.

Public bridge implementation:

```kotlin
// MazewallProfiler companion; RealHotSpotSessionFactory implements observe.
public fun observeHotSpot(
    target: HotSpotObservationTarget,
    options: HotSpotOptions,
): HotSpotProfilingSession = RealHotSpotSessionFactory.observe(target, options)
```

`HotSpotOptions` now describes common assets/timing settings: strict methods remain strict; only this explicit method negotiates observed collection. The caller supplies the independently discovered host tuple; do not add another Kotlin cgroup/container-discovery subsystem.

- [ ] **Step 4: Preserve diagnostic provenance and prove no policy promotion.** Add nullable `runtimeProfile` to internal/public structured evidence, populated only from explicit negotiated context; old schema1/older diagnostic documents decode with null rather than invented provenance. Store it additively in `HotSpotEvidenceDocument`; validate any supplied profile against the two supported names. Expose `policyEligibility:false` on public structured evidence and serialized reports; reject attempts to deserialize true. Do not change `collectionLossComplete:false`, coverage.complete false, or the empty policy behavior/stackProfile path.

In `HotSpotObservedConsumerTest`, use the Task1 resource with the actual selected protocol and accumulator. Define the helper completely in the test:

```kotlin
private fun observedEvidence(): io.mazewall.profiler.internal.hotspot.StructuredProfileEvidence {
    val limits = HotSpotReplayLimits()
    val expected = HotSpotCaptureExpectation.Observed(
        HotSpotObservationTarget(Pid(123), 456uL, 789uL, 42uL),
    )
    val protocol = HotSpotProtocol(123, 456uL, expected)
    val accumulator = HotSpotEvidenceAssembler(limits, expected).begin()
    requireNotNull(javaClass.getResourceAsStream("/hotspot/observed-consumer.jsonl")).use { input ->
        HotSpotWireReader(limits, 3uL).read(input) { row ->
            protocol.controlEvent(row)
            when (row.kind) {
                "session_started" -> accumulator.accept(row.copy(kind = "collector_attached"))
                "collector_ready" -> Unit
                else -> accumulator.accept(row)
            }
        }
    }
    return accumulator.finish().also { protocol.summaryEvent(it) }
}

@Test
fun `ordinary JVM frames survive public diagnostics but cannot produce policy`() {
    val result = HotSpotCoverageAdapter.toLiveResult(observedEvidence())
    assertFalse(result.coverage.complete)
    assertFailsWith<IncompleteProfileException> { result.toDedicatedWorkerPolicies() }
    val publicEvidence = io.mazewall.profiler.api.StructuredProfileEvidence(observedEvidence())
    val restored = io.mazewall.profiler.api.StructuredProfileEvidence.fromJson(publicEvidence.toJson())
    assertFalse(restored.policyEligibility)
    assertFalse(restored.collectionLossComplete)
    assertEquals("otel_hotspot25_observed_v1", restored.runtimeProfile)
    val frames = restored.records.single().evidence.frames
    assertEquals(listOf("leaf", "caller"), frames.map { it.functionName })
    assertEquals(9007199254740993uL, frames.first().rawPosition)
    assertEquals(18446744073709551615uL, frames.last().rawPosition)
    assertEquals(0u, frames.first().bci)
    assertNull(frames.last().bci)
}
```

Add an unavailable terminal variant with no invented frames and consistent unavailable counters, plus a schema3 error with primary EIO and retained EACCES. Assert public nativeErrnos contains both original causes. Existing false/partial output must not become successful after cleanup retry.

- [ ] **Step 5: Run the connected HotSpot unit suite and ABI checks.**

```bash
./gradlew :profiler:compileKotlin
./gradlew :profiler:test --tests 'io.mazewall.profiler.hotspot.*'
```

Use the existing configured Kotlin ABI validation through the project quality gate; preserve old constructor/method descriptors and `HotSpotResultCompatibilityTest`. Do not refresh ABI baselines blindly. Compiler/LSP output before indexing completes is not a replacement for these checks.

**Deliverable:** A real public observed-session path backed by the existing collector owner, with host-only regression coverage and no change to strict admission or AUTO. Update `profiler/HOTSPOT_AGENTLESS.md` in this task with the new explicit method, caller-supplied tuple, ordinary JVM flags and unchanged limitations.

## Task 3: Finish the existing node-agent contribution, not another implementation

**Consumes:** Published bridge v0.2.0; current node-agent companion and storage46c8444a implementation. **Produces:** Normal dependency graph/build with explicit publication limitations, accurate docs, existing companion regressions passing.

- [ ] **Step 1: Preserve and inspect current source before changing dependency files.**

```bash
git -C /home/leanid/Documents/code/java/stack-attribution/node-agent status --short
git -C /home/leanid/Documents/code/java/stack-attribution/node-agent diff -- go.mod go.sum cmd/main.go pkg/containerprofilemanager/v1/monitoring.go pkg/storage/v1/containerprofile.go
git -C /home/leanid/Documents/code/java/stack-attribution/storage status --short
```

The uncommitted Tier E importer/config/main edits are not this contribution. Do not stage or undo them. Source inspection has already established that `startAgentlessHotspot`, `prepareChild`, and `ProjectCapture` negotiate Observed25; do not change their profile again.

- [ ] **Step 2: Pin the released SDK using actual module resolution.** When dependency-network access is authorized, from N:

```bash
GOWORK=off go mod download -json github.com/Pilleo/mazewall/hotspot-bridge@v0.2.0
GOWORK=off go mod edit -require=github.com/Pilleo/mazewall/hotspot-bridge@v0.2.0
GOWORK=off go mod download github.com/Pilleo/mazewall/hotspot-bridge@v0.2.0
```

Review the real go.mod/go.sum changes; no manually supplied checksums or SDK replace. Resolve storage separately: use an actually reachable reviewed commit/tag containing the typed schema and record its real module version/sum. If that implementation is not remotely available, prepare its existing scoped contribution and report release-build blocked. Continue permitted local development with the already selected reviewed-source workspace; do not fabricate a storage version or call that a release build. No replacement graph containing the native Gadget module.

- [ ] **Step 3: Check the existing supervisor/projection/publication regressions.** These already cover the requested behavior. Use their current cases before adding tests:

```bash
go test -race ./pkg/stackattribution/hotspot -run 'TestSupervisor|TestProjection' -count=1
go test -race ./pkg/containerprofilemanager/v1 ./pkg/objectcache/callstackcache ./pkg/config -run 'Hot[Ss]pot|HotSpot' -count=1
go test -race ./cmd -run 'Hot[Ss]pot|HotSpot' -count=1
```

Use the selected local development workspace only when explicitly recording development evidence; run `GOWORK=off` for released-graph checks once storage is reachable. Do not run these commands under consumed HOST slots. No skips may be presented as positive runtime acceptance.

Retain controller exit/replacement/late-failure and capability-probe tests. Only add a missing regression if a concrete gap appears. A focused additional projection assertion can reuse the existing fixture without inventing helpers:

```go
func TestProjectionKeepsObservedRuntimeProfile(t *testing.T) {
    rows, err := ProjectCapture(projectionFixture())
    if err != nil || len(rows) != 1 {
        t.Fatal(rows, err)
    }
    e := rows[0].Evidence
    if e == nil || e.RuntimeProfile != string(wire.Observed25) ||
        e.PolicyEligibility || e.GlobalCollectionComplete {
        t.Fatal("observational profile changed during publication projection", e)
    }
}
```

If this assertion is already covered, do not add a duplicate. For any discovered bug: reproduce RED, fix its shared owner once, rerun its connected cases. Do not alter storage schema on speculation.

- [ ] **Step 4: Correct `N/docs/agentless-hotspot.md`.** Replace the strict interpreter/SerialGC description with the implemented pinned ordinary tiered/G1 Observed25 scope. Separate historical development-workspace evidence, the now-published SDK, still-unpublished storage, and still-unrun native/end-to-end checks. Preserve default-disabled behavior, false policy flags, quotas, target identity and cleanup limitations.

- [ ] **Step 5: Build the normal node-agent target once its graph is resolvable.**

```bash
GOWORK=off make binary
```

A failed storage/module resolution is a concrete blocker, not a reason to embed OTel directly into node-agent, edit module caches, or drop typed evidence.

**Deliverable:** Existing observed companion integrated against real dependencies where available; otherwise exact unpublished storage blocker plus reviewable source diff. No cluster/image/release readiness inferred from unit tests.

## Task 4: Exercise MazeWall's public observed API using the existing live workload

**Current fixture design (supersedes the original container-harness sketch):** New `src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotObservedIntegrationTest.kt` reuses `experiments/hotspot-agentless/src/ObservedWorkload.java`, existing collector artifacts and manifests. The existing strict container harness is unchanged. Inspection found that importing the reviewed remote-launch repair would also import a larger prepared-launch stack; a directly owned JVM child and directly executed helper avoid that machinery and Docker entirely. This does not repair or discharge historical container obligations.

- [x] **Step 1: Add and compile the explicitly gated public-API test.** The existing workload receives the test scratch path and `400 4 20`, emits its unchanged40warmups/oracles, and waits on its existing gates. Test-only discovery independently reads host PID/raw birth/cgroupv2/mount namespace; target identity is never taken from collector output. The public path is:

```kotlin
// Simplified successful path; the actual test retains failed/pending owners.
MazewallProfiler.observeHotSpot(target, HotSpotOptions(observedAssets)).use { session ->
    Files.createFile(directory.resolve("measure"))
    HotSpotAcceptanceHarness.await { Files.exists(directory.resolve("done")) }
    assertEquals("400\n", Files.readString(directory.resolve("done")))
    val result = session.finish()
    assertFalse(result.coverage.complete)
    assertFailsWith<IncompleteProfileException> { result.toDedicatedWorkerPolicies() }
    assertFalse(requireNotNull(result.structuredEvidence).policyEligibility)
    assertFalse(session.pendingOwnership)
}
Files.writeString(directory.resolve("release"), "release\n")
```

Use emitted `oracle-0.tsv` through `oracle-3.tsv` to compare every marked filename's routeA/routeB and host TID against public records. Require400marked events/400terminals, at least360useful routes, zero wrong routes/TIDs/order. Unavailable rows count toward terminal accounting, not useful frames. Keep the original corpus/deadlines. Scratch is never automatically deleted. Close/retry the existing collector owner before releasing the child; retain the target if ownership remains pending, preserve failure/interrupt information, and check the released child's actual exit. This test creates no remote acquisitions or CLI proxies.

The test stays in `src/test` to avoid `integrationTest`'s automatic rootful-container dependency. `MW_HOTSPOT_OBSERVED_ACCEPTANCE=true` enables it and disables Gradle result reuse/cache; absent that explicit flag, its native body is skipped. Parser tests remain host-safe and run normally.

- [ ] **Step 2: Prepare one exact native execution request.** Name source revision/diff, existing asset directory and trusted manifest hash, pinned JVM executable, selected test, helper BPF/perf/process-memory authority and owned-child cleanup. Select actual reviewed inputs first; no untrusted guessed hash or readiness receipt. Preserve old obligations separately. No N3-path candidate or old conditional HOST2 slot is executable authority.

- [ ] **Step 3: After authorization, run the selected new public test and affected existing controls.**

```bash
MW_HOTSPOT_OBSERVED_ACCEPTANCE=true \
MW_HOTSPOT_OBSERVED_JAVA=/absolute/reviewed/pinned-jvm/bin/java \
MW_HOTSPOT_ASSET_TEST_DIR=/absolute/reviewed/observed/assets \
./gradlew :profiler:test --tests 'io.mazewall.profiler.hotspot.HotSpotObservedIntegrationTest'
```

The paths above are placeholders, not selected inputs or an executable grant. Use only the explicitly authorized direct-host environment; there is no sudo/container fallback on failure. Existing accepted collector six-case evidence remains reusable only for matching collector bytes; changes to the collector require affected controls again. Do not conflate this observed test with the eight original strict methods.

**Deliverable:** Genuine ordinary-JVM frames returned through MazeWall's public observed method, with actual child retirement. Until the test runs, label this deliverable unverified.

## Task 5: Complete Kubescape's existing persisted-profile acceptance path

**Owning execution detail:** [Existing Kubescape acceptance plan](2026-10-18-kubescape-agentless-hotspot-acceptance.md), Tasks1–4. Its named packaging/example paths are planned work, not all present in N. Preserve its real persisted-profile checks; supersede stale prerequisites saying the SDK or local storage API gate do not exist. Do not recreate retired VM/kind environments without fresh authority.

- [ ] **Step 1: Reuse or finish its small derived image and example, after inspecting N/examples.** Use the existing node-agent binary plus the same observed collector bytes used by MazeWall; manifests may differ only where their actual packaging contract requires. Keep the default node image independent of native assets. Existing release artifacts are not rebuilt twice merely for a new consumer.
- [ ] **Step 2: Carry forward the existing acceptance-plan tests and commands, not a second framework.** Its `PrepareImageContext` corruption test, ordinary Java route fixture, raw/typed evidence verifier and API-readback checks define the implementation. Retain false policy flags and exact caller order through storage. Do not substitute storage's already-passed JSON/protobuf gate for node-agent capture acceptance.
- [ ] **Step 3: Prepare the exact server/node-image/cluster/namespace execution request together.** Use a reviewed schema-capable storage server and actually built node image, with real digests and explicitly selected context. Publication is separately authorized; no synthetic release identifiers or historical Ready receipts.
- [ ] **Step 4: Under that authority, execute the existing D corpus and fetch the persisted profile.** Verify Java function/location/BCI values, runtime profile, target/container/image identity, stable content CallIDs, false policy eligibility and false global completeness. Exit/replacement/read-denial/late-failure cases must remain rejected or explicitly unavailable, never route to another container. Verify actual owned cleanup and retain failure evidence.

**Deliverable:** An API-fetched Kubescape profile originating from an actual ordinary-JVM capture through node-agent. Local object marshaling, SDK tests and a Ready node alone do not satisfy it.

## Task 6: Required gates and two contribution handoffs

- [ ] **Step 1: Run the required completion gates once for the selected revision and authorized environment.**

```bash
./gradlew build
```

N runs its applicable configured checks after the focused suites and normal binary build. S runs its configured gates only if its source changes. Preserve the unresolved strict-eight/fullgate failures until actual successful evidence replaces them. If normal integration fixtures fail on resource ownership, fix that demonstrated fixture defect with one regression; do not add another Gradle action/classloader monitor.

- [ ] **Step 2: Perform one scoped connected-code review.** Check both profile selections, target/cgroup binding, provider pins, every schema3 false-policy row, public error causes, unavailable metadata, frame order/precision, report roundtrip and actual process ownership. Check node-agent's enforcement-index exclusion and old-client field-pruning limitations. Review the actual diff, not only this plan.
- [ ] **Step 3: Finish documentation and prepare separate contribution diffs.** MazeWall: public observed method and required native assets. Kubescape: existing companion/dependencies/docs and any necessary packaging example; storage remains its own existing contribution. Stage only task-owned changes if explicitly proceeding through the selected review path; no automatic commit/push/merge and no user-prototype files.
- [ ] **Step 4: Validate the plan/docs and report outcomes honestly.**

```bash
git diff --check
./scripts/adkw check-backlog
```

Report source checks, actual MazeWall observed integration, actual Kubescape persisted-profile acceptance and strict qualification as separate statuses. List exact blocked external prerequisites once; do not reopen incremental approval loops.

## Execution order and stop conditions

1. Tasks1–2 finish MazeWall's missing source path. Task3 finishes the already-written node-agent integration; no new symbolizer/collector/storage schema.
2. Tasks4–5 prove the two product entry points under explicit runtime authority. Task6 supplies the normal project gates and review handoff.
3. Stop only for a material scope/security change or a real missing external prerequisite. Do not stop after every file to ask whether to continue; do not run restricted commands merely because they appear here.

**Intentionally excluded:** broader JDK/architecture support, Loom identity guarantees, complete policy admission for observed stacks, direct OTel embedding in node-agent, a new offline core-dump infrastructure, new release versions for unchanged SDK bytes, and more checked-HOST qualification machinery.
