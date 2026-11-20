---
document_type: execution_plan
base_revision: a28cbe293289f2549858aa232d9e6ebd7c4756dd
status: planned_with_external_release_and_execution_gates
---

# HotSpot Release and Runtime Qualification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended only after the operator selects delegation) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Planning does not authorize publication, staging, commits, worktrees, downloads, images, native execution, cluster operations or fullgate.

**Goal:** Turn the completed host-source integration into an actually released storage graph and independently evidenced MazeWall/Kubescape runtime qualification, or retain the exact rejected/blocked gate without inventing readiness.

**Architecture:** Reuse the existing collector, published bridge v0.2.0, typed storage contribution, public MazeWall observed API, companion and node-local runner. Release storage before selecting a server or enabling collection. Close the existing Kotlin acceptance fixture's evidence-retention gap with a small test-only helper; then run the two consumers independently against the same admitted collector bytes and finish the owning quality/review gates.

**Tech Stack:** Linux-amd64, pinned Temurin25.0.4.1+1-LTS, JDK25/JDK22+ library floor, Kotlin/JUnit5, independently recorded Go compilers, bridge v0.2.0, existing Kubernetes generated JSON/protobuf clients, existing image recipes and node-local runner.

**Spec:** [Governing design, A–D](../specs/2026-10-18-kubescape-agentless-hotspot-design.md), interpreted with the executed [host-source handoff](../../internals/reviews/2026-10-23-hotspot-host-source-handoff.md) and [observed-profile contract](../../plans/2026-10-18-hotspot-observed-profile-contract.md). This plan resumes the external exits of the [remaining-acceptance plan](2026-10-23-hotspot-remaining-acceptance.md); it does not restart its completed Tasks2–4.

## Global Constraints

The following binding requirements are copied from the governing design:

- JDK25 idioms; library compatibility floor JDK22. Supported host Linux-amd64.
- Readiness <=10seconds, session <=60seconds, stop <=2 remaining seconds, event enrichment deadline1second; no larger worker/pending/native buffer budgets.
- Kotlin retention4096 occurrences/33,554,432 wire-payload bytes, max line1,048,576bytes, max wire rows65,536, max frames4096, max tracked occurrences32,768.
- Rehash every asset byte and validate the complete local OCI graph; no size/mtime/inode digest cache or trusted-helper self-verification shortcut.
- No dependencies or breaking public APIs; additive native/public interfaces require explicit approval and legacy ABI verification before promotion.
- No swallowed/downgraded EPERM/EACCES. Fallback is explicit operator choice only.
- Captured occurrence/session/process generation/BOOTTIME identity is immutable; never substitute a later stack or infer logical identity from host TID alone.
- Physical drain, selected-stream accounting, whole-session collection and policy eligibility remain separate. Policy coverage stays incomplete for selected openat evidence.
- Never install seccomp from a virtual thread or change JVM coordination syscall floors; do not touch GITHUB_TOKEN.
- Native builds remain optional, reproducible and operator-invoked; no startup compiler/download/sudo or registry fallback.
- Preserve user work, scope commits to the relevant backlog item and use the orchestrator-managed review path; no default-branch push.
- Native enrichment workers4, pending events1024, pending payload16MiB, producer LRU256, frame cache16384, frame data3072words and perf data524288bytes/possible CPU remain fixed.

Execution-specific constraints:

- Observed explicitly selects `otel_hotspot25_observed_v1`, schema3/capture ABI2/collector3 and ordinary tiered/G1. Strict schema2, its interpreter/Serial requirements and AUTO do not change. Every observed row remains explicitly policy-ineligible; global collection remains incomplete.
- Reuse bridge **v0.2.0** without rebuilding/republishing it. Preserve independent node/collector dependency graphs and the actual collector compiler, even when different from node/storage Go versions.
- Companion: one active collector, eight queued incarnations, one15s window/incarnation,10s discovery,1s reconciliation,256MiB evidence quota,16KiB ready/32MiB capture bounds. The runner's first120s application-startup observation is not a native readiness/window extension.
- Keep the target alive until collector ownership is proven retired. A missing live child observation, same-birth zombie, denied read, lost Create UID or unknown cleanup outcome is rejection with retained owners, never permission to delete by name.
- Work in the current checkouts. No worktree, branch switch, automatic stage/commit, blanket stash, push or merge. Review snapshot-relative deltas because much of M/N is already dirty/untracked. Existing staged `CLAUDE.md` work is outside this plan.
- Publication, dependency downloads, image build/load/pull, host/security provisioning, native runs, cluster mutation and fullgate each require their own exact operator-approved scope. A receipt, plan, environment variable or execution-style selection is not such authority.
- Historical strict-eight stays FAILED/INCOMPLETE; consumed HOST/N3 grants and unresolved custody/resource obligations remain unchanged. Do not query/resume protected historical resources to fill new input slots.

---

## Current baseline and what not to redo

| Area | Actual current evidence | Remaining exit |
|---|---|---|
| M public observed API | Existing host/ABI checks and reviewed source | Actual native run with complete retained evidence |
| N acquisition/readback/verifier/runner |26 scoped paths, final reviewed hashes in `.superpowers/sdd/2026-10-23-hotspot-remaining-acceptance/final-fix-hashes.json` | Released graph and real-node qualification |
| N final covering checks | Runner885.429s/verifier946.683s/CLI race PASS; vet PASS | Reuse for unchanged revision/graph; do not rerun for planning |
| S typed contribution | Local source at `4c2e1cf1b64b6169b02b7a9b0ceacafd985aad5f`;33-file review inventory | Actual scoped review/publication selection |
| N standalone storage | v0.0.303 lacks required typed fields | Reviewed remotely resolvable replacement |
| Public native fixture | Existing `HotSpotObservedIntegrationTest` | Maps, failed-attempt retention and sampled compiled-frame proof |
| Full quality | Separate16-finding Detekt-test issue | Selected maintenance scope and authorized full gate |

Do not recreate the collector/bridge/schema/runner, change the linear BPF design, add a remote launcher/inspector pod, widen JVM support or introduce another provenance/lifecycle platform. This is one integration exit plan across already separated A/B/C/D subprojects; the independent native lane can progress while storage review is external.

Repository aliases used below:

```bash
M=/home/leanid/Documents/code/java/jseccomp
N=/home/leanid/Documents/code/java/stack-attribution/node-agent
S=/home/leanid/Documents/code/java/stack-attribution/storage
GO=/home/leanid/go/pkg/mod/golang.org/toolchain@v0.0.1-go1.27.0.linux-amd64/bin/go
export GOTOOLCHAIN=local
set -C
```

Set these aliases in the execution shell, not in repository module files. Commands below specify their owning checkout; `GOWORK=off` is mandatory for released-node checks. Keep the operator's approved proxy/checksum configuration; missing tools/modules fail rather than trigger an unapproved automatic toolchain download. `set -C` prevents shell redirections from overwriting prior receipts; retries use new owned attempt paths, never disable that protection to erase a first failure. The explicit old development workspace is evidence only, never release qualification. The cached Go path is this checkout's available tool, not a universal required installation path.

## File responsibilities before execution

| Path | Responsibility / intended change |
|---|---|
| S existing33-file contribution | Review existing typed/generated/aggregation/persistence/Create-response changes; no second schema |
| N `go.mod`, `go.sum`, `docs/agentless-hotspot.md` | Pin only the actual resolved reviewed storage version and document the graph |
| M `profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotObservedAcceptanceEvidence.kt` | New small test-only bounded maps/compiled-event/failure-retention helpers |
| M adjacent `HotSpotObservedAcceptanceEvidenceTest.kt` | New host-only helper regressions |
| M existing `HotSpotObservedIntegrationTest.kt` | Use those helpers around the existing public API invocation; preserve child ownership |
| M `profiler/HOTSPOT_AGENTLESS.md` | Document exact new retained artifacts and their limitations |
| N existing `build/Dockerfile.hotspot`, workload recipe/source, `cmd/{prepare,run,verify}` | Reuse unchanged recipes/commands; no normal source edit planned |
| S existing `tests/agentless_stack_roundtrip_test.go` | Existing real-server JSON/protobuf gate; no new importer |
| N `docs/agentless-hotspot-acceptance.md` | New durable record of actual environment/cases/results and unresolved owners |
| M `docs/internals/reviews/2026-10-23-hotspot-runtime-qualification.md` | New cross-consumer result record; classify source/native/live/fullgate separately |
| Existing backlog issues912/967/825/300/293/528 | Update only with evidence; archive only when their actual conditions pass |

Do not edit orchestrator code or start its daemon to choose a task implicitly. Use the existing operator-controlled scoped review/approval flow; do not assign another worker to an active orchestrator worktree.

## Dependency and authority map

| Task | Depends on | Independently reviewable deliverable | Separate authority before effects |
|---|---|---|---|
|1 Storage review/release | Existing S source/inventory | Scoped contribution accepted or exact review blocker | Submission/publication/merge |
|2 Released consumer graph |1 actual remote selection | Pinned graph, standalone binaries and host checks | Required module/tool downloads |
|3 Public-native evidence gap | Existing M API/fixture | Host-tested test-only retention/proof changes | Source execution selection; no native run |
|4 Actual inputs/images/node contract |2 for cluster image lane; native-input lane independent | Separate native request and complete cluster artifact/receipt request | Build/load/pull/provisioning/inspection operations |
|5 Selected server API gate |1/4 matching server | Original Create/Get JSON/protobuf and owned cleanup | Deployment/API resources |
|6 MazeWall native acceptance |3 and4 native-input lane only | One independently reported public-API native attempt | One exact native invocation/retention scope |
|7 Kubescape live/control acceptance |2/4/5; same admitted collector as6 | Actual runner bundle, recreation/source-route controls and matrix | Named cluster/native cases/resource custody |
|8 Owning full gates/handoff | Final revisions and6/7 outcomes | Separate status lanes and scoped integration handoff | Maintenance/fullgate/CI/release actions |

Long test commands remain owned until terminal result; never return a completed task while relying on a child-local completion notification. Run one implementation writer at a time. Do not select Subagent-Driven automatically merely because it was used for the previous plan.

### Task 1: Prepare and obtain review of the existing storage contribution

**Files:** Read S `docs/agentless-stack-evidence.md`, `docs/agentless-stack-evidence-live-api.md`, API/generated/aggregation/persistence/test files in the retained33-file inventory. Write new review artifacts only under a fresh owned execution output. Modify existing S files only for actual review findings after a scoped source decision.

**Interfaces:** Consumes local S base `d963c7941f5bd493751109324e6eba41f7d2b5af` and candidate `4c2e1cf1b64b6169b02b7a9b0ceacafd985aad5f`. Produces an actually reviewed remote `STORAGE_REVISION`, review receipt and matching server-source selection—not an invented release tag/image.

- [ ] **Step 1: Freeze the current contribution and dirty boundaries.** Read `git status --short`, branch/HEAD and the old Task1 inventory before opening source. Require `QUALIFICATION_OUTPUT` to name a new absolute private output under an operator-owned0700 parent; reject pre-existing output/symlink roots. After checking all parent components for symlinks/ownership, acquire it with `mkdir -m 0700 "$QUALIFICATION_OUTPUT"` (no `-p` and no existing-directory reuse). Record current hashes rather than assuming these old candidate revisions still describe live work.

```bash
# From S, after acquiring the new output directory:
git status --short > "$QUALIFICATION_OUTPUT/storage-status.txt"
git rev-parse HEAD > "$QUALIFICATION_OUTPUT/storage-head.txt"
git diff --binary d963c7941f5bd493751109324e6eba41f7d2b5af..4c2e1cf1b64b6169b02b7a9b0ceacafd985aad5f \
  -- . ':(exclude)build' > "$QUALIFICATION_OUTPUT/storage-review.diff"
git diff --check d963c7941f5bd493751109324e6eba41f7d2b5af..4c2e1cf1b64b6169b02b7a9b0ceacafd985aad5f \
  -- . ':(exclude)build'
```

Do not inspect/include protected `build/` handoffs, live output or credentials. The old raw branch contains a historical tracked build handoff: publishing that entire branch is not this scoped package. Generated `ignorerule.go` also contains unrelated SourceKind/SourceName/SourceNamespace/Justification/ImpactStatement drift; explicitly separate or justify it in review, never silently delete it from user work.

- [ ] **Step 2: Review the existing contract, not a new schema.** Verify Symbol protobuf4/Evidence protobuf3, optional nil/zero/max locations, unsigned decimal positions, explicit false fields, deepcopy/conversion/apply/OpenAPI/protobuf, aggregation ownership and full persisted ContainerProfile Create responses. Verify existing legacy fixtures and metadata-only watch behavior. Reuse matching prior checks; if source correction changes a boundary, first add a failing case to its existing `stack_evidence_test.go` or `containerprofile_create_response_test.go`, then run the owning tests below.

```bash
# From S; host checks, only when new/reviewed revision needs them:
GOWORK=off "$GO" test ./pkg/apis/softwarecomposition/... ./pkg/generated/openapi \
  ./pkg/registry/file/callstack ./pkg/registry/file -count=1
GOWORK=off "$GO" test -tags=agentless_hotspot_integration ./tests \
  -run '^TestStackEvidence(Helpers|WireExamples)' -count=1 -v
```

These use existing test names and synthetic transports. They are not new live API receipts. Do not run codegen without an actual generated-source correction; preserve unrelated generated drift and review regeneration results.

- [ ] **Step 3: Submit the exact reviewed scope only after applicable approval.** Present the diff, source file inventory/hashes, focused evidence and excluded files through the scoped review path. Ask the operator to select the actual publication/merge action and repository target; do not start a generic daemon, push the default branch or create a worktree. If publication is unavailable, finish this task with a BLOCKED review/release report, not a fabricated module version.

- [ ] **Step 4: Record the actual accepted remote revision and server source.** Save review/publication receipts and the exact resolved commit when it exists. A locally committed candidate, prior disposable server result or published bridge v0.2.0 does not select a released storage/server build. Gate2 waits here; Task3 can proceed independently.

**Gate:** Existing contribution reviewed or exact unresolved findings recorded; no automatic commit/publication. A release-ready claim requires actual remote resolution in Task2.

### Task 2: Pin the real storage release and build standalone consumers

**Files:** Modify N `go.mod`, `go.sum`, `docs/agentless-hotspot.md`. Reuse tests in N `pkg/stackattribution/hotspot`, `pkg/containerprofilemanager/v1`, `pkg/objectcache/containerprofilecache`, `pkg/config`, `cmd` and the example. No local storage replacement or bridge/Gadget upgrade.

**Interfaces:** Consumes actual reviewed `STORAGE_REVISION`. Produces resolver `Version`, `Sum`, `GoModSum`, `Origin`, complete module graph, actual binary hashes and source/compiler identities for `node-agent`, `hotspot-run` and `hotspot-verify`.

- [ ] **Step 1: Resolve and inspect the selected source before editing.** Require separate download permission if cache is insufficient. No need to repeat the known v0.0.303 failure; its absent fields are already established.

```bash
# From N; actual operator selection, never a guessed version:
: "${STORAGE_REVISION:?select the actual reviewed remote storage revision}"
GOWORK=off "$GO" mod download -json "github.com/kubescape/storage@$STORAGE_REVISION" \
  > "$QUALIFICATION_OUTPUT/storage-resolution.json"
```

Extract the returned `Version` into `STORAGE_VERSION`; retain all resolver fields, verify that `Origin` binds the reviewed source, and inspect that exact `Dir` against Task1's contract. A successful download of an older untyped version rejects. Module-cache source is read-only.

- [ ] **Step 2: Make the minimal module edit and review its entire delta.** Preserve pre-existing bridge additions and all current replacements. Use the resolver's version, not its requested revision spelling, as the require value.

```bash
: "${STORAGE_VERSION:?use Version from the verified resolver receipt}"
GOWORK=off "$GO" mod edit -require="github.com/kubescape/storage@$STORAGE_VERSION"
GOWORK=off "$GO" mod download "github.com/kubescape/storage@$STORAGE_VERSION"
git diff -- go.mod go.sum
GOWORK=off "$GO" list -m -mod=readonly -json all > "$QUALIFICATION_OUTPUT/node-module-graph.json"
```

Do not run broad `go mod tidy` to absorb unrelated dirty dependencies. Require bridge v0.2.0 and the unchanged node Gadget graph; missing/transitive resolution needs its own authorized input, not a permanent absolute replace.

- [ ] **Step 3: Build through the normal standalone recipe and actual CLI callers.** Use a new output path so an existing user binary is never overwritten.

```bash
# From N; create QUALIFICATION_OUTPUT/bin exclusively first:
PATH="$(dirname "$GO"):$PATH" GOWORK=off make binary BINARY_NAME="$QUALIFICATION_OUTPUT/bin/node-agent"
GOWORK=off "$GO" build -o "$QUALIFICATION_OUTPUT/bin/hotspot-run" ./examples/agentless-hotspot-sbob/cmd/run
GOWORK=off "$GO" build -o "$QUALIFICATION_OUTPUT/bin/hotspot-verify" ./examples/agentless-hotspot-sbob/cmd/verify
"$GO" version > "$QUALIFICATION_OUTPUT/node-go-version.txt"
sha256sum "$GO" > "$QUALIFICATION_OUTPUT/node-go-executable.sha256"
"$GO" version -m "$QUALIFICATION_OUTPUT/bin/node-agent" > "$QUALIFICATION_OUTPUT/node-buildinfo.txt"
sha256sum "$QUALIFICATION_OUTPUT/bin/"* > "$QUALIFICATION_OUTPUT/node-binaries.sha256"
```

Record reviewed source snapshots including untracked build inputs and actual dirty diffs; VCS buildinfo alone does not identify this dirty checkout. Build info and hashes are build observations, not independent attestation of an image.

- [ ] **Step 4: Run affected host gates once on the pinned graph.** A changed dependency graph invalidates old development-workspace compilation; these are meaningful new checks, not a rerun of unchanged code for planning.

```bash
GOWORK=off "$GO" test -race ./examples/agentless-hotspot-sbob/... -count=1 -timeout=30m
GOWORK=off "$GO" test -race ./pkg/stackattribution/hotspot ./pkg/containerprofilemanager/v1 \
  ./pkg/objectcache/containerprofilecache ./pkg/config ./cmd \
  -run 'TestHotSpot|TestRuntimeController|TestAdmission|TestSupervisor|TestReady|TestAgentlessHotspot|TestNamespaceFilter|TestCalculateSHA256CallStackHash|TestLegacyPackagesDeleted' \
  -count=1 -timeout=15m
GOWORK=off "$GO" vet ./examples/agentless-hotspot-sbob/...
git diff --check
```

- [ ] **Step 5: Update the node guidance and get scoped review.** Record resolved module/sums/source/compiler/binary hashes and the remaining matching-server image prerequisite. Preserve default-disabled configuration, baseline sensors and the root asset-preparation dependency boundary. Commit only if separately selected; do not stage other node changes.

**Gate:** Real standalone node graph and CLI builds pass, or exact compiler/dependency failure is retained. This does not qualify the server or native environment.

### Task 3: Retain complete public-native attempt evidence without changing the API

**Files:** Create M test helpers `profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotObservedAcceptanceEvidence.kt` and `HotSpotObservedAcceptanceEvidenceTest.kt`; modify existing `HotSpotObservedIntegrationTest.kt` and `profiler/HOTSPOT_AGENTLESS.md`. Read `profiler/AGENTS.md`, existing `HotSpotObservedTargetIdentity.kt`, `HotSpotObservedConsumerTest.kt`, public evidence/exception types and `profiler/hotspot-agentless/internal/model/hotspot_quality.go`/`patches/profiler-quality.patch`.

**Interfaces:** Existing `MazewallProfiler.observeHotSpot(identity, HotSpotOptions(assets))` remains unchanged. Produce these **test-only internal** functions, with no production hook:

```kotlin
internal fun readObservedMaps(path: Path): ByteArray
internal fun observedCompiledMarkedEvents(evidence: StructuredProfileEvidence, markedNames: Set<String>): Set<String>
internal fun retainObservedFailure(directory: Path, failure: Throwable): Throwable
```

`exact_pc`/`preceding_pc` are actual existing nmethod symbolization provenance; `interpreter`, unknown/stub/method-only, enabled compilation or absent `-Xint` do not establish this PC-scoped compiled-frame evidence. This is an observed frame claim, not a generation lease, universal JIT correctness or policy admission.

- [ ] **Step 1: Write host-only cause-reaching tests.** Cover16MiB maps bound, exact byte preservation, original read denial, exclusive writes, retained errno/diagnostics, write failure joined with the first failure, and compiled/interpreter/unknown/error/partial-walk/unmarked distinctions. Example tests (imports use the existing JUnit/Kotlin/Java test conventions):

```kotlin
@Test
fun `maps are bounded and preserved`(@TempDir dir: Path) {
    val file = dir.resolve("maps")
    val bytes = "1000-2000 r-xp 0 00:00 1 /actual/libjvm.so\n".toByteArray()
    Files.write(file, bytes)
    assertContentEquals(bytes, readObservedMaps(file))
    Files.newOutputStream(file).use { it.write(ByteArray(16 * 1024 * 1024 + 1)) }
    assertFailsWith<IllegalArgumentException> { readObservedMaps(file) }
}

@Test
fun `retention does not overwrite or erase the original failure`(@TempDir dir: Path) {
    Files.writeString(dir.resolve("failure.txt"), "first attempt", CREATE_NEW)
    val original = java.nio.file.AccessDeniedException("owned-maps")
    val result = retainObservedFailure(dir, original)
    assertSame(original, result)
    assertEquals("first attempt", Files.readString(dir.resolve("failure.txt")))
    assertTrue(result.suppressed.any { it is java.nio.file.FileAlreadyExistsException })
}
```

For compiled-frame tests reuse the real raw fixture/replay procedure, not a made-up public DTO constructor. In `HotSpotObservedAcceptanceEvidenceTest`, define `evidence(scope: String)` as below; its `/synthetic` input is expressly host-only and is never native proof:

```kotlin
private fun evidence(scope: String): StructuredProfileEvidence {
    val text = requireNotNull(javaClass.getResourceAsStream("/hotspot/observed-consumer.jsonl"))
        .bufferedReader().use { it.readText() }
        .replace("\"function_name\":\"leaf\"", "\"function_name\":\"ObservedWorkload.leaf(java.nio.file.Path)\"")
        .replace("\"error_frame\":false", "\"error_frame\":false,\"scope_lookup\":\"$scope\",\"scope_walk\":\"returned\"")
    val limits = HotSpotReplayLimits()
    val expected = HotSpotCaptureExpectation.Observed(HotSpotObservationTarget(Pid(123), 456uL, 789uL, 42uL))
    val protocol = HotSpotProtocol(123, 456uL, expected)
    val accumulator = HotSpotEvidenceAssembler(limits, expected).begin()
    HotSpotWireReader(limits, 3uL).read(text.byteInputStream()) { row ->
        protocol.controlEvent(row)
        when (row.kind) {
            "session_started" -> accumulator.accept(row.copy(kind = "collector_attached"))
            "collector_ready" -> Unit
            else -> accumulator.accept(row)
        }
    }
    val result = accumulator.finish().also { protocol.summaryEvent(it) }
    return StructuredProfileEvidence(result)
}

@Test
fun `only observed nmethod PC provenance counts`() {
    for (scope in listOf("exact_pc", "preceding_pc")) {
        assertEquals(setOf("observed:0:1"), observedCompiledMarkedEvents(evidence(scope), setOf("synthetic")))
    }
    for (scope in listOf("interpreter", "unknown", "stub", "method_only")) {
        assertTrue(observedCompiledMarkedEvents(evidence(scope), setOf("synthetic")).isEmpty())
    }
    assertTrue(observedCompiledMarkedEvents(evidence("exact_pc"), emptySet()).isEmpty())
}
```

Use imports for existing `internal.hotspot` replay types and public `StructuredProfileEvidence`/`HotSpotObservationTarget`, as in `HotSpotObservedConsumerTest`; no package crosses the production boundary. Add error-frame/partial-walk cases by mutating those actual JSON fields before replay, keeping event identity unchanged. Construct `HotSpotProfilingException` through its existing internal test-visible constructor for a retained errno13/diagnostic test; reuse existing backend synthetic-child controls for genuine pending-owner behavior.

- [ ] **Step 2: Run the new class RED before helpers.**

```bash
# From M, no observed acceptance opt-in:
./gradlew :profiler:test --tests io.mazewall.profiler.hotspot.HotSpotObservedAcceptanceEvidenceTest
```

Initial unresolved helper symbols are scaffolding RED, not a claimed behavioral bug. Then implement the helper bodies below and require the meaningful bound/no-overwrite/identity cases to fail if their guard is temporarily removed; preserve the test outputs.

- [ ] **Step 3: Implement the narrow helper bodies.** Use existing `CleanupFailures.merge` rather than a new failure framework:

```kotlin
internal fun readObservedMaps(path: Path): ByteArray {
    val bound = 16 * 1024 * 1024
    val bytes = Files.newInputStream(path).use { it.readNBytes(bound + 1) }
    require(bytes.isNotEmpty() && bytes.size <= bound) { "Missing or oversized target maps" }
    return bytes
}

internal fun observedCompiledMarkedEvents(
    evidence: StructuredProfileEvidence,
    markedNames: Set<String>,
): Set<String> = evidence.records.filter { record ->
    record.event.filename.substringAfterLast('/') in markedNames &&
        !record.event.pathTruncated && record.evidence.attribution == "event_correlated" &&
        record.evidence.frames.none { it.errorFrame } &&
        record.evidence.frames.any { frame ->
            frame.scopeLookup in setOf("exact_pc", "preceding_pc") && frame.scopeWalk == "returned" &&
                listOf("leaf", "routeA", "routeB").any { method -> frame.functionName.contains("ObservedWorkload.$method(") }
        }
}.map { it.eventId }.toSet()

internal fun retainObservedFailure(directory: Path, failure: Throwable): Throwable {
    var result = failure
    val files = mutableListOf("failure.txt" to failure.stackTraceToString())
    if (failure is HotSpotProfilingException) {
        files += "collector-failure.stderr" to failure.diagnostics
        files += "native-errnos.json" to failure.nativeErrnos.joinToString(prefix = "[", postfix = "]")
        files += "pending-ownership.txt" to failure.pendingOwnership.toString()
        try {
            failure.partialResult?.structuredEvidence?.let { files += "partial-evidence.json" to it.toJson() }
        } catch (serialization: Throwable) {
            result = CleanupFailures.merge(result, serialization)
        }
    }
    for ((name, text) in files) {
        try {
            Files.writeString(directory.resolve(name), text, CREATE_NEW)
        } catch (write: Throwable) {
            result = CleanupFailures.merge(result, write)
        }
    }
    return result
}
```

No false pending-owner value is written for a generic failure: its ownership is unknown until the existing session cleanup establishes it. Record that distinction in the native attempt report. Keep original `Throwable`/errno causes, not parsed text.

Execution preflight clarification: the body above illustrates a root HotSpot exception, but `CleanupFailures.merge` can promote a later Error or retain the HotSpot failure in causes/suppressed values. The implemented helper must also preserve actual reachable HotSpot errno/diagnostic/partial-result data in those cases, using a small bounded identity-aware traversal or preserving the original exception at its call site; never reconstruct errno from the text dump or replace unknown ownership with false. Add host regressions for a wrapper cause and a promoted Error with the original HotSpot exception suppressed. This is test-only retention at the existing boundary, not a new public exception/API/framework.

- [ ] **Step 4: Integrate retention into the existing native test.** Before attaching, bracket original `/proc/<pid>/maps` reads with `observedTargetIdentity(identity.pid) == identity`, save `target-maps-before` with CREATE_NEW, and repeat after finish while the target is alive. Save `collector.diagnostics`/discardedDiagnosticBytes before close and after close under distinct exclusive filenames. Success still saves public `evidence.json`; failure after owned cleanup calls `retainObservedFailure` and rethrows its returned original/merged failure. Save pending/target-retirement outcomes even when evidence persistence fails; never let a write error prevent the existing close/hold logic.

Add this check after the existing400-token corpus validation, deriving names from the four original TSVs:

```kotlin
val markedNames = (0..3).flatMap { worker ->
    Files.readAllLines(directory.resolve("oracle-$worker.tsv")).map { it.substringBefore('\t') }
}.toSet()
val compiledEvents = observedCompiledMarkedEvents(evidence, markedNames)
Files.writeString(directory.resolve("compiled-marked-event-ids.txt"), compiledEvents.sorted().joinToString("\n"), CREATE_NEW)
assertTrue(compiledEvents.isNotEmpty(), "No marked application frame has observed nmethod PC provenance")
```

Report counts per route and the exact sample IDs/quality fields; this minimum proves some sampled compiled application frames, not that every useful route was compiled. If none occur, retain a rejected attempt. Do not increase warmup, alter400/4/20, force compilation, enable an agent, change GC or relabel interpreter frames to pass.

The public evidence report includes bounded original per-record/summary JSON and diagnostics, **not a complete standalone collector stdout transcript**. Do not label it as such. Any independently required full-transport capture needs a separately reviewed retention arrangement before native execution, not a second collector window or a new public hook hidden in this task.

- [ ] **Step 5: GREEN, docs and scoped review.**

```bash
./gradlew :profiler:compileTestKotlin
./gradlew :profiler:test \
  --tests io.mazewall.profiler.hotspot.HotSpotObservedAcceptanceEvidenceTest \
  --tests io.mazewall.profiler.hotspot.HotSpotObservedConsumerTest \
  --tests io.mazewall.profiler.hotspot.HotSpotBackendTest \
  --tests io.mazewall.profiler.hotspot.HotSpotObservedTargetIdentityTest
git diff --check
```

Review denied-read and write-failure outcomes with disposable synthetic inputs only. Reuse unchanged main ABI checks because no production API changes. Keep the separate Detekt-test issue visible; do not suppress it. This task's commit/review scope is test helpers, existing test and its guidance only.

**Gate:** Host-tested native evidence retention/proof is ready; no native test has run. Preserve the existing `@EnabledIfLinuxAndSupported`, opt-in/cache bypass and `@TempDir(cleanup = NEVER)` semantics.

### Task 4: Select actual artifacts, node custody and separately approved image operations

**Files:** Read existing N image/workload recipes, runner README/input DTOs and M admitted asset policy. Create operator-reviewed execution/build/origin receipts in the new output, not synthetic test receipts. No new production source change is planned.

**Interfaces:** Consumes Task2 source/binaries and independently supplied assets/JVM/base/server choices. Produces actual immutable node/storage/workload image references, original `NodeOrigin`/`BuildReceipt` bytes matching current parser schemas, and one exact approval request per applicable operation class.

This task has two explicit output lanes, not one all-or-nothing grant. `native-request.md` needs only the actual collector/assets/JVM/direct-host/permission/custody selections for Task6 and can be reviewed while Task1/2 are blocked. `cluster-request.md` plus complete original NodeOrigin/BuildReceipt inputs wait for Task2 and the selected server/images; they qualify Tasks5/7 only. Do not fabricate partial cluster receipts or storage/image values to unblock the independent native lane. Steps2/3 and the cluster JSON inputs in Step4 are cluster-lane work; Step1 and the native request's actual asset/JVM/host provenance remain independently actionable.

- [ ] **Step 1: Require real selections before any environmental probe.** Record exact kubeconfig/current context, dedicated Node name/UID/boot/kernel/initial-procfs/cgroup origin, local CRI socket, UID1000 gate/evidence parent, approved access/capabilities/LSM/Yama and chosen server endpoint. Record actual JVM executable/mapped libjvm and assets/manifest/collector/source/compiler provenance. No path/hash/context is selected by this plan. Do not recover values from consumed candidates or historical Ready receipts.

The existing collector pins require mapped libjvm SHA256 `5e0fb6e83a5676090f28ff0b453e9199df06af1ff90d473c1b9837e41096114c` and build ID `e4dad22744e32e4d71755f4a58c5cf1f1d336f87`; these are compatibility constants, **not independent trust receipts**. Check actual bytes/provenance under the approved scope. Do not change Yama/sysctls, become root, add an init helper or new privilege to make a denial disappear.

- [ ] **Step 2: Build only genuinely missing approved images.** Prefer already reviewed immutable images built from the exact selected source/graph. An old node image with the old module cannot become the new Task2 binary merely by adding assets. If the ordinary node base is missing, use its existing normal build with operator-selected immutable builder/runtime inputs and preprovisioned reviewed `tracers.tar`; review the exact build-input recipe before building. Do not call `make gadgets`, fetch mutable bases or install tools implicitly. Record actual builder/runtime manifest identities and each artifact's actual compiler independently.

For that conditional ordinary-base build, preserve `build/Dockerfile` and all its commands. Render a temporary reviewed recipe changing only its two FROM inputs, never its Go flags, archive copy, cwd or entrypoint. Require exact existing lines so an unexpected source recipe change stops rather than silently produces a different build:

```python
# Run from N with the three environment values explicitly exported:
from pathlib import Path
import os
import re

text = Path("build/Dockerfile").read_text()
for name, old, prefix, suffix in (
    ("NODE_BUILDER_IMAGE", "FROM --platform=$BUILDPLATFORM golang:1.27-trixie AS builder",
     "FROM --platform=$BUILDPLATFORM ", " AS builder"),
    ("NODE_RUNTIME_IMAGE", "FROM gcr.io/distroless/static-debian13:latest", "FROM ", ""),
):
    pin = os.environ[name]
    assert re.fullmatch(r"[^\s@]+@sha256:[0-9a-f]{64}", pin)
    assert text.splitlines().count(old) == 1
    text = text.replace(old, prefix + pin + suffix, 1)
destination = Path(os.environ["QUALIFICATION_OUTPUT"]) / "node-base.Dockerfile"
with destination.open("x") as output:
    output.write(text)
```

The narrow input guard prevents recipe-line injection; Docker and prior reviewed selection still determine actual supported image syntax/custody. Record the exact temporary recipe/hash and compare its two-line delta before execution. With approved builder/runtime images already provisioned and module/build caches ready:

```bash
# From N; only this conditional base-build authority:
: "${NODE_BASE_LOCAL_TAG:?approved unused local node-base label}"
docker buildx build --platform linux/amd64 --load --pull=false --network=none \
  --metadata-file "$QUALIFICATION_OUTPUT/node-base-build.json" \
  -f "$QUALIFICATION_OUTPUT/node-base.Dockerfile" -t "$NODE_BASE_LOCAL_TAG" .
```

Missing caches/inputs reject; do not remove network isolation or rebuild/pull gadgets as an implicit retry. Resolve the build's actual qualified manifest reference into `NODE_BASE_IMAGE` only after checking its source/graph/compiler/archive provenance. If an already reviewed matching base exists, skip this entire conditional build.

Prepare the existing verified named asset context, then use existing derived/workload recipes only after explicit build/load permission:

```bash
# From N; inputs/outputs supplied and reviewed before this command:
: "${ASSETS_DIR:?approved read-only assets}"
: "${MANIFEST_SHA256:?independently trusted manifest digest}"
: "${ASSET_CONTEXT:?new absolute verified context output}"
GOWORK=off "$GO" run ./examples/agentless-hotspot-sbob/cmd/prepare \
  --assets="$ASSETS_DIR" --manifest-sha256="$MANIFEST_SHA256" --output="$ASSET_CONTEXT"

: "${NODE_BASE_IMAGE:?reviewed immutable ordinary node image from Task2 source}"
: "${JVM_IMAGE:?approved immutable matching plain Temurin image}"
: "${NODE_LOCAL_TAG:?approved local build label, never manifest identity}"
: "${WORKLOAD_LOCAL_TAG:?approved local build label, never manifest identity}"
docker buildx build --platform linux/amd64 --load --pull=false \
  --metadata-file "$QUALIFICATION_OUTPUT/node-image-build.json" \
  --build-context "hotspot_assets=$ASSET_CONTEXT" --build-arg "NODE_AGENT_IMAGE=$NODE_BASE_IMAGE" \
  -f build/Dockerfile.hotspot -t "$NODE_LOCAL_TAG" .
docker buildx build --platform linux/amd64 --load --pull=false \
  --metadata-file "$QUALIFICATION_OUTPUT/workload-image-build.json" \
  --build-arg "JVM_IMAGE=$JVM_IMAGE" \
  -f examples/agentless-hotspot-sbob/workload/Dockerfile -t "$WORKLOAD_LOCAL_TAG" \
  examples/agentless-hotspot-sbob/workload
```

All referenced images must already be locally available unless acquisition itself was explicitly approved; `--pull=false` is not an authorization mechanism. Require actual manifest digest provenance from the selected builder/exporter and matching node runtime import; a tag, Docker config ID or tag-shaped `image inspect` result is not that identity. If a local-only builder cannot provide a qualified immutable runtime reference, stop at that exact image-custody blocker—do not publish to solve it without approval. Compare every packaged asset byte/transitive OCI blob and workload class/source/JVM identity to the reviewed inputs. Preserve `/root/tracers.tar`, entrypoint and default sensors.

- [ ] **Step 3: Validate the real UID1000/baseline/privilege contract under an explicit inspection/native scope.** Qualify the actual selected image's `/root` traversal/archive, proc/CRI/assets/evidence access, effective capabilities, BPF/perf/exec tracepoint/uprobe/process-memory permissions and baseline startup. The runner requests hostPID and BPF/PERFMON/SYS_ADMIN/SYS_PTRACE/SYS_RESOURCE/DAC_READ_SEARCH, not privileged:true. A non-root UID declaration does not prove those capabilities survive exec or that a child can read target memory. Some preflight calls load BPF; classify them as native, not harmless inspection. Stop on EPERM/EACCES with retained causes; changing topology/security settings requires a new exact review decision.

- [ ] **Step 4: Obtain and preserve the original independent trust inputs.** `NodeOrigin` fields are schemaVersion,receiptID,context,nodeName,nodeUID,bootID,procfsRoot,cgroupRoot,initialPIDNamespace,procfsPIDNamespace,procfsMountID. `BuildReceipt` has source roles nodeAgent/storage/bridge/collector/workload; compiler roles nodeAgent/storage/collector/workload; actual images, assetsManifestSHA256,collectorSHA256,jvmBuildID,jvmSHA256 and provider. Each source has revision/sourceSHA256/diffSHA256/moduleSHA256 (module optional only for workload); each compiler has version/sha256. Use the existing parser, not another schema:

```go
origin, build, err := verify.ParseTrustInputs(originJSON, buildJSON)
```

This is the existing runner boundary, not a request to add a new validator command. The runner itself parses/binds originals before effects. Same numbers/hashes do not prove their own origin; operator review must bind original source/module/compiler/image assets. Do not generate declarations from synthetic test fixtures. Keep original <=64KiB files, independently acquired identities and their custody. The native/public and cluster requests must name exact owners, selected cases/budgets, retained output, permitted cleanup and excluded publication. Task6 requests one public-native invocation. Task7's positive, recreation and optional source-new-route invocations are distinct requests: each creates two workload windows, for at most six15s collector windows if all three are selected, plus their separate baseline/agent resources. Extra attempts, native preflights and destructive controls are not included implicitly.

**Gate:** Exact artifact/environment request ready or an explicit rejection. No inferred execution consent. Tasks5–7 may run only within their separately approved scopes.

### Task 5: Qualify the selected deployed storage API before enabling the agent

**Files:** Existing S `tests/agentless_stack_roundtrip_test.go`, `docs/agentless-stack-evidence.md`; record actual selected server/source/image and originals in new output and N acceptance guidance. No schema or test rewrite planned.

**Interfaces:** Actual Task1 server source/image and approved API context -> original generated-client JSON/protobuf Create/Get receipts, real acquired ContainerProfile UIDs and checked cleanup. This is a server gate, not node-originated profile acceptance.

- [ ] **Step 1: Deploy/select only the approved server image and test namespace.** Require the approved server to serve `spdx.softwarecomposition.kubescape.io/v1beta1`, exact typed fields/generated graph and the normal ContainerProfile GVK decoration. Reuse a prior matching server receipt only if source/image/environment/custody are actually applicable; an old retired cluster is not selected by its record. New deployment/setup commands belong to the separately reviewed environment procedure, not ambient `kubectl apply` against an existing cluster.

- [ ] **Step 2: Invoke the existing tagged gate once when new server evidence is needed.** The prerequisite namespace is exactly `agentless-hotspot-test`, provisioned under this server/API approval, distinct from the runner's run-unique namespace pair. Never change current context on the user's behalf.

```bash
# From S, with the selected kubeconfig already on the selected context:
: "${KUBECONFIG:?one approved absolute kubeconfig}"
: "${AGENTLESS_HOTSPOT_CONTEXT:?approved explicit current context}"
: "${AGENTLESS_HOTSPOT_OUTPUT_DIR:?approved private receipt parent}"
KUBECONFIG="$KUBECONFIG" AGENTLESS_HOTSPOT_CONTEXT="$AGENTLESS_HOTSPOT_CONTEXT" \
AGENTLESS_HOTSPOT_OUTPUT_DIR="$AGENTLESS_HOTSPOT_OUTPUT_DIR" \
GOWORK=off "$GO" test -tags=agentless_hotspot_integration ./tests \
  -run '^TestStackEvidenceAPIRoundTrip$' -count=1 -v
```

- [ ] **Step 3: Review original bodies and ownership.** Require exact requested response media, full persisted values in both Create/Get, optional nil/zero/max fidelity, unsigned precision, explicit false flags, actual object identity and no pruning. Retain the gate's original `*-request.body`, `*-response.body`, `*-media.json`, command output/result and acquired UID/Delete receipts. Lost Create UID or failed Delete remains rejected/unknown; never weaken to metadata-only success or delete by name. Preserve the first failure even if an approved correction/retry follows.

- [ ] **Step 4: Get server-gate review before enabling the node feature.** Record matching image/source and original response digests. This gate may pass while Task7 remains unexecuted. No node-profile importer supplies positive evidence.

**Gate:** Reviewed current-server JSON/protobuf persistence and actual owned cleanup, or its exact rejection. The old local gate's success alone is not a current server selection.

### Task 6: Run one public MazeWall native attempt with the retained evidence

**Files:** Existing augmented M native test from Task3, current public API, `profiler/HOTSPOT_AGENTLESS.md`; create the M durable runtime result document listed above. No collector, transport, symbolizer or target changes.

**Interfaces:** Trusted Task4 assets/JVM/host + Task3 evidence helper -> one non-skipped public observed test, retained success/partial evidence, sampled compiled marked-frame IDs and real retirement outcome. This lane does not require storage release completion.

- [ ] **Step 1: Review the exact one-invocation request.** Record source/test/build inputs and compiler/JVM identities, independent manifest SHA, full OCI verification/custody, host/node boot/procfs/permissions, actual target non-root identity and explicit collector/target/pending owner retention scope. Reuse matching admitted six-case collector evidence only after actual source/binary/provenance matching. Neither this plan nor opt-in environment variables renew historical native grants.

- [ ] **Step 2: Verify selected manifest bytes externally, then invoke once.** The existing public options have no expected-manifest-SHA parameter; do not invent one. Keep trusted assets immutable between the external check and internal full-byte verification.

```bash
# From M, only after the exact native request is approved:
: "${MANIFEST_SHA256:?independently trusted current manifest SHA256}"
: "${MW_HOTSPOT_ASSET_TEST_DIR:?approved absolute read-only observed assets}"
: "${MW_HOTSPOT_OBSERVED_JAVA:?approved absolute pinned Java executable}"
: "${NATIVE_ATTEMPT_OUTPUT:?new absolute private native attempt output}"
mkdir -m 0700 "$NATIVE_ATTEMPT_OUTPUT"
export NATIVE_ATTEMPT_OUTPUT
printf '%s  %s\n' "$MANIFEST_SHA256" "$MW_HOTSPOT_ASSET_TEST_DIR/manifest.json" | sha256sum --check --strict
MW_HOTSPOT_OBSERVED_ACCEPTANCE=true \
MW_HOTSPOT_OBSERVED_JAVA="$MW_HOTSPOT_OBSERVED_JAVA" \
MW_HOTSPOT_ASSET_TEST_DIR="$MW_HOTSPOT_ASSET_TEST_DIR" \
./gradlew :profiler:test --tests io.mazewall.profiler.hotspot.HotSpotObservedIntegrationTest
```

Preserve the actual command status plus stdout/stderr with checked capture; a failed command is not replaced by the exit status of `tee`. Keep the existing test cache bypass. Do not add sudo/container wrappers, PR_SET_PTRACER target changes, JVM agents or fallback flags.

- [ ] **Step 3: Freeze actual XML and retained scratch before any later Gradle gate.** Copy the original non-skipped XML with exclusive creation to the native attempt output; later host/full builds can overwrite `profiler/build/test-results`. This check is an example of inspecting the actual current result, not evidence generated by the checker:

```python
from pathlib import Path
import os
import xml.etree.ElementTree as ET

source = Path("profiler/build/test-results/test/TEST-io.mazewall.profiler.hotspot.HotSpotObservedIntegrationTest.xml")
raw = source.read_bytes()
destination = Path(os.environ["NATIVE_ATTEMPT_OUTPUT"]) / "public-native-test.xml"
with destination.open("xb") as output:
    output.write(raw)
suite = ET.fromstring(raw)
assert int(suite.attrib["tests"]) == 1
assert int(suite.attrib.get("skipped", "0")) == 0
assert int(suite.attrib.get("failures", "0")) == 0
assert int(suite.attrib.get("errors", "0")) == 0
```

On failed/skipped XML, retain the original under the same custody procedure and report rejected/unexecuted; do not run a second attempt just because the success assertions above fail. The named attempt output must be newly acquired before invoking the test, not derived from an old XML result.

- [ ] **Step 4: Review all native acceptance predicates.**400unique marked events/terminals, four independently sourced workers, at least360useful correct routes, zero wrong TIDs/routes/order, original flags/maps, actual marked nmethod PC-scope sample IDs, explicit policy/global incompleteness and no physical pending owner. Retain first failures, compound errno/diagnostics and checked target exit. If compiled-marked evidence is empty, this is a rejected compiled-coverage gate, not permission to modify the workload. Source-bound diagnostics/report serialization is not a raw-transport transcript or strict lifetime proof.

- [ ] **Step 5: Record an independent lane verdict and review it.** Report either qualified observed public API, rejected with exact evidence, or unexecuted with exact missing input/authority. Collector cleanup retry never turns the original rejection into success; unknown/held target or child remains an explicit obligation.

**Gate:** Independently reviewed actual MazeWall public-native outcome. This cannot substitute for Task7's persisted node profiles, strict-eight or fullgate.

### Task 7: Execute actual Kubescape positives, recreation and controls

**Files:** Reuse N built `hotspot-run`/`hotspot-verify`, existing image recipes/workload and original trust schemas. Create N `docs/agentless-hotspot-acceptance.md`; update M runtime qualification result. No new runner flags, remote shell or fault-injection framework is planned.

**Interfaces:** Tasks2/4/5 plus exact node-local approval -> original two-target final bundle and separately classified recreation/source-route/control results. The current CLI has one workload-image pin and two workload Pods; do not invent per-target image flags.

- [ ] **Step 1: Admit the exact run and artifact set.** Execute directly on the selected node as the qualified UID1000 operator with separately provisioned proc/CRI access and a new0700 output parent. Both gated workloads are acquired before starting the agent; initial current-membership replay is the reviewed prerequisite. Require schema-capable selected storage, exact source/compiler/image/assets receipts and actual origin custody. No `kubectl exec` into distroless, inspector pod, ambient context or registry acquisition.

- [ ] **Step 2: Run the existing CLI once within the named scope.** Every value is a required actual operator selection; input guards are not consent. Use Task2's standalone binary rather than compiling on the test node or selecting a development workspace:

```bash
: "${ACCEPTANCE_KUBECONFIG:?approved absolute kubeconfig}"
: "${ACCEPTANCE_CONTEXT:?approved explicit current context}"
: "${ACCEPTANCE_NAMESPACE:?new run workload namespace}"
: "${ACCEPTANCE_NODE:?approved dedicated node}"
: "${ACCEPTANCE_NODE_UID:?actual selected Node UID}"
: "${ACCEPTANCE_BOOT_ID:?actual selected boot ID}"
: "${ACCEPTANCE_NODE_IMAGE:?reviewed loaded immutable node reference}"
: "${ACCEPTANCE_STORAGE_IMAGE:?matching deployed reviewed server reference}"
: "${ACCEPTANCE_WORKLOAD_IMAGE:?reviewed loaded plain JVM reference}"
: "${ACCEPTANCE_ASSETS:?approved read-only absolute assets}"
: "${ACCEPTANCE_MANIFEST_SHA256:?independently trusted manifest SHA}"
: "${ACCEPTANCE_RUNTIME_ENDPOINT:?approved absolute local CRI socket}"
: "${ACCEPTANCE_NODE_ORIGIN:?original independent NodeOrigin JSON}"
: "${ACCEPTANCE_BUILD_RECEIPT:?original reviewed BuildReceipt JSON}"
: "${ACCEPTANCE_OUTPUT:?new absolute private run output}"
"$QUALIFICATION_OUTPUT/bin/hotspot-run" \
  --kubeconfig="$ACCEPTANCE_KUBECONFIG" --context="$ACCEPTANCE_CONTEXT" \
  --namespace="$ACCEPTANCE_NAMESPACE" --node="$ACCEPTANCE_NODE" \
  --node-uid="$ACCEPTANCE_NODE_UID" --node-boot-id="$ACCEPTANCE_BOOT_ID" \
  --node-agent-image="$ACCEPTANCE_NODE_IMAGE" --storage-image="$ACCEPTANCE_STORAGE_IMAGE" \
  --workload-image="$ACCEPTANCE_WORKLOAD_IMAGE" --assets="$ACCEPTANCE_ASSETS" \
  --manifest-sha256="$ACCEPTANCE_MANIFEST_SHA256" --runtime-endpoint="$ACCEPTANCE_RUNTIME_ENDPOINT" \
  --node-origin="$ACCEPTANCE_NODE_ORIGIN" --build-receipt="$ACCEPTANCE_BUILD_RECEIPT" \
  --output="$ACCEPTANCE_OUTPUT"
"$QUALIFICATION_OUTPUT/bin/hotspot-verify" --bundle "$ACCEPTANCE_OUTPUT"
```

The offline command is meaningful only for the actual retained output and Run success; a partial directory or independently invoked verifier does not override a nonzero live-run result.

- [ ] **Step 3: Inspect original positive and retirement evidence.** For each container require exact400marked pairs, >=360useful correct routes, zero wrong/swapped TIDs/routes/order, overlapping namespace PID1 but distinct actual container/host-incarnation/profile UID bindings. Preserve original API/CRI/proc/oracle/status/ready/capture/raw/JSON/protobuf/media/terminal/cleanup files. Profiles must contain full typed frame order/content/locations/BCI/unsigned precision/CallIDs and explicit false booleans at the same actual object revision. Confirm both native windows preceded slow readback. Actual known/shared Pod/profile/Namespace/control-Pod UIDs and complete config/RBAC inventory must be retired; supplemental proc files are not proven merely by final schema hashes.

- [ ] **Step 4: Execute a separately approved recreation run.** Only after first owners are checked retired, invoke the same existing CLI with a second **new** approved namespace/output and newly acquired actual identities; reuse immutable source/images only when unchanged. Compare both final bundles: new Pod/container/profile UIDs and raw host process births must differ, namespace PIDs can overlap, and no old ready/session/capture/CallID authority may be substituted. Classify this as an explicit recreation/restart control, not an automatic in-place restart feature. Never resume the first output or silently replace a failed incarnation.

- [ ] **Step 5: Execute a source-new-route control only if included in image/native scope.** Create a new owned build context containing copies of the canonical workload recipe/source, leaving source files in N unchanged. The narrow variant preserves400/4/20, source TSV labels and ordinary JVM flags but adds an actual Java call frame:

```java
static void routeA(Path path) throws Exception { routeAAdded(path); }
static void routeAAdded(Path path) throws Exception { leaf(path); }
```

Replace exactly the existing `static void routeA(Path path) throws Exception { leaf(path); }` line in that copied source, assert one replacement, record the variant source/class/build hashes, build/load its distinct immutable image under new explicit authority and provide an original BuildReceipt describing those actual changed bytes. Run the existing CLI with that workload image, another new namespace/output and the independently acquired oracle. Keep all existing leaf/route ordering predicates; additionally require at least one original raw **and persisted-profile** marked routeA stack to contain `ObservedWorkload.routeAAdded(` in correct leaf→added→routeA order. No observed extra frame means this control is not qualified; never add the expected frame, remap an oracle or suppress inlining/GC to make it pass.

- [ ] **Step 6: Classify each negative/control case using its owning evidence, without inventing a live fault mode.** Reuse unchanged source/binary-qualified host/disposable-child receipts when that is the claimed level. Rerun only invalidated controls; examples of existing exact selectors are:

```bash
# From N, host/disposable-child controls only, on the selected released graph:
GOWORK=off "$GO" test -race ./cmd ./pkg/stackattribution/hotspot \
  -run 'TestAgentlessHotspotDisabledHasNoConstructionOrEffects|TestRuntimeControllerDisabledDoesNoIOOrProbe|TestRuntimeControllerStorageDenialPrecedesProbesAndChildren|TestAdmissionReportsStorageRejectionWithoutEffects|TestSupervisorRejectsUnverifiedAssetsWithoutStartingChild|TestRuntimeControllerPermissionFailureRetainsCauseAndStartsNoChild|TestRuntimeControllerRemovalCancelsActualChildAndNeverPublishes|TestRuntimeControllerQueueOverflowRetiresItsOnlyActiveChild|TestSupervisorRawBudgetRequestsStopAndDoesNotPublishDiscardedCapture|TestSupervisorRetainsRealHeldPipeOwnerUntilRetry|TestReadyCallbackRetainsPendingReaderUntilExplicitCleanup' \
  -count=1 -timeout=15m
```

Record separate rows for disabled/baseline, old/pruning storage, corrupt assets, read denial, target exit/exec, queue/quota pressure, hung child/reader/callback, compiled churn/class unload and unknown cleanup. Existing matched collector qualification may supply applicable native controls; do not rerun historical matrices without invalidation. Host fake/disposable-child evidence must be labeled as such, never live Kubernetes proof. A required real destructive/privilege/pressure control needs its own exact granted procedure and owned resources; if absent, keep that control and final D acceptance BLOCKED. Do not add faults to the shipped CLI or patch shared production resources to improvise the case.

- [ ] **Step 7: Record/review the actual lane verdict and all owners.** The result document lists exact source/module/compiler/images/JVM/node/kernel/boot/origin, original response/capture hashes, marked/useful/unavailable/loss counts, object revisions, native/control provenance and each created/reconciled/unknown owner. Preserve main-phase first report, later outcome and every failed cleanup attempt. No accepted result with a nonzero runner, failed required control, unknown acquisition or pending physical owner. Keep backlog issues open until their real conditions pass.

**Gate:** Actual persisted-profile acceptance plus all required control/retirement predicates, or a concrete rejected/blocked result. There is no production deployment or policy-completeness claim.

### Task 8: Finish owning quality gates and hand off separate outcomes

**Files:** Existing M/N/S owning build/test wiring and docs; existing [Detekt maintenance issue528](../../internals/backlog/issue-20261022-221346-528-restore-profiler-test-detekt-gate-without-weakenin.md); M runtime result/N acceptance docs/backlog. No CI file unless an actual approved native runner is selected.

**Interfaces:** Final selected source/graph and independently recorded Task5/6/7 outcomes -> source, MazeWall-native, Kubescape-live and strict/fullgate/custody status lanes. No combined success bit erases a failed lane.

- [ ] **Step 1: Resolve or explicitly retain the independently scoped Detekt blocker.** If maintenance issue528 is selected, run `./gradlew :profiler:detektTest`, retain the recorded16original findings alongside the actual current output, and execute its source-fix protocol against the actual owning tests before the full gate. Do not assert the current count is16 if intervening user work changes it. Known owners are engine/DaemonRunnerTest,ProfilerDaemonTest; internal/DaemonStartupRollbackTest,NativeSocketInputStreamTest; tierE/tools/TierEBenchmarkBundleBuilderTest,TierENativeArtifactPackageTest,TierENativeTimingDiagnosticTest,TierEPhaseReplayTest,TierESourceFingerprintTest,TierESplitCoverageVerifierTest, all under `profiler/src/test/kotlin/io/mazewall/profiler/`. Do not alter lint rules/baselines or add blanket suppressions. If that maintenance scope is not selected, record full quality BLOCKED; do not silently absorb those unrelated files into HotSpot.

- [ ] **Step 2: Run final owning gates once in the approved environment.** Preserve Task6's original XML/scratch first; a later full build may run the class with its opt-in absent. Do not accidentally repeat the native public attempt by leaving its opt-in enabled in a broad Gradle invocation.

```bash
# From M, separately approved complete quality environment:
MW_HOTSPOT_OBSERVED_ACCEPTANCE=false ./gradlew build

# From S, if selected source/graph/server correction invalidated its earlier full gate:
GOWORK=off make test
```

M full build includes its configured integration suites and requires corresponding authority/prerequisites. N requires Task2 standalone build and its current CI-equivalent package scope in the approved environment; `.github/workflows/go-basic-tests.yaml` includes gadget preparation and privileged tests. Do not execute its download/`make gadgets`/sudo commands implicitly or substitute host-only tests for that gate. Record exact applicable CI receipt or unavailable prerequisite. Reuse actual same-revision focused/ABI/example checks; rerun only when changed source/graph or a concrete concern invalidates them.

- [ ] **Step 3: Review only actual new deltas and gate outcomes.** Task1 contribution/release selection, Task2 pins, Task3 test-only evidence work, any selected maintenance fix and actual image/runtime results each retain a scoped review. The previous26-file source review stays completed; do not redispatch it wholesale. A new observed defect enters its existing owner with behavioral RED/GREEN; no automatic repeated acceptance until the exact failed resource/owner/retry scope is reviewed.

- [ ] **Step 4: Validate documentation, file scope and custody.**

```bash
# From M:
git diff --check
./scripts/adkw check-backlog
# From N and S, independently for their task-owned deltas:
git diff --check
```

Check new local documentation references and preserve unrelated index/worktree entries. Present task-owned file lists, snapshots/module/image hashes and review results via the operator-controlled path. No automatic `git add .`, commit, publication, merge or workspace deletion. Keep old source-plan evidence and first failures for recovery. Native CI stays explicitly unconfigured unless a real approved runner, immutable inputs, credential ownership and failure-artifact retention are selected; do not add a speculative workflow label.

- [ ] **Step 5: Write the final four-lane handoff.** Classify source gates, public MazeWall native, node-agent persisted-profile live acceptance and historical strict/fullgate/custody separately as passed/rejected/blocked/unexecuted with exact receipts. A required control, real owner or quality gate still unresolved prevents whole-integration completion. A plan execution-style choice never supplies the missing runtime authority.

**Gate:** Reviewable, truthful integration/release decision with every required outcome and owner accounted for, or its specific blocking decision. Commits/merges/publication remain separately selected actions, not generic per-task steps in these dirty checkouts.

## Plan self-review and coverage

| Governing requirement | Task / preserved contract |
|---|---|
| B typed schema/generated compatibility/persistence/full Create |1 existing contribution review,2 released pin,5 actual server gate |
| Independent compiler/module/source/image/asset identities |1/2/4; no shared-Go fiction or config-ID manifest substitution |
| A actual ordinary compiled-frame evidence/retained maps/diagnostics |3 test-only evidence,6 real public gate; no strict lease claim |
| C startup/default-disabled/baseline and storage-before-enable |2 host gates,4 real image/security contract,5 prerequisite,7 classified controls |
| C immutable occurrence/target/BOOTTIME and one owner/window | Existing reviewed runner reused by7; native budgets never widened |
| D original API JSON/protobuf/typed symbols/false qualifiers |5 server exchanges,7 real node-originated same-revision readback |
| D overlapping PID namespaces/recreation/new route |7 Steps3–5; new actual tuples/source/images, no oracle remapping |
| D denial/exit/pressure/hung ownership/churn/unload controls |7 Step6; exact evidence level and actual grant required, absence stays blocked |
| Physical EOF/Wait versus file/schema success | Existing supervisor + actual child observations/cleanup in6/7; no runner Wait fiction |
| Full gates/maintenance/review/docs/backlog |8; no hidden Detekt waiver or synthetic fullgate claim |
| Protected historical failures/grants/resources/user work | Global constraints and every acquisition/release gate |

Task1 and Task3 can be rejected/reviewed independently; Task6's direct-host lane does not wait on storage publication. Actual API/image/native/control values are execution inputs that must be selected and observed, not template values an engineer may fabricate. All new helper names/signatures are defined in Task3; production DTO/CLI names are the existing source contracts. Runtime acceptance and fullgate remain unexecuted at plan creation.

Planning self-review checked the source/spec coverage table, all three new helper signatures/callers and actual existing CLI/test/type names. It corrected the native-versus-cluster dependency split and moved native XML preservation before pass assertions, so a failed result is retained too. All local documentation links and shell/Python snippet syntax were checked without executing those commands. No new source/native/test pass is claimed by these documentation checks.

## Execution handoff

Plan-only preparation is complete when this document passes local reference/whitespace/backlog checks. Choose **Subagent-Driven** for fresh per-task implementers and scoped review, or **Inline Execution** using `executing-plans`. Do not begin execution, publication, environment probing or a native/image/cluster/fullgate command merely because this plan was saved. Stop at the first missing applicable release/input/authority/ownership fact, retain its exact report and continue only independent already-authorized work.
