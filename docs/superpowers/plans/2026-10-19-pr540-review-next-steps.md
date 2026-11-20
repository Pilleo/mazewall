---
document_type: execution_plan
base_revision: 183fe06405a88e9999e90ab7fc6bba2517d3b889
status: tasks_1_2_reviewed_task_3_authorized_checkpoint_pending_at_authoring_release_review_still_required
---

# PR540 failure fidelity and integration next steps

> **For agentic workers:** The operator approved implementation with subagents.
> Use subagent-driven-development task by task in the current checkout; no Git
> worktree, orchestrator sweep or release publication is authorized by that choice.
> Required review and publication/provisioning gates remain separate.

**Goal:** Preserve original native failure causes across the real producer/SDK
boundary, then finish the already approved release and Kubernetes integration.

**Architecture:** Keep the existing collector, shared SDK and bounded supervisor.
Correct failure serialization rather than weakening sticky rejection or parsing
human error messages. Advance the existing A/B/C/D plans with actual artifacts;
do not duplicate their implementations or call local workspaces released pins.

**Tech stack:** Existing Go1.26 bridge/collector, Go1.27 node-agent, pinned native
dependencies, JDK25 and explicit disposable Linux-amd64 Kubernetes environment.

**Spec:** [Governing design](../specs/2026-10-18-kubescape-agentless-hotspot-design.md),
[observation/error contract](../../plans/2026-10-18-hotspot-observed-profile-contract.md),
[release ordering](../../plans/2026-10-19-hotspot-bridge-release.md).

## Review scope and evidence

PR540 at183fe064 against fast-master16fab8e6 changes215files. Sixty changed
canonical/experimental Go or patch pairs are byte-identical. Node-agent72034696
and storage4c2e1cf are separate local checkouts, not source changes in this PR.

Assumed load: one active capture, four enrichment workers, up to1024pending events;
failures from multiple workers/readers can race during shutdown. No hypothetical
multi-tenant service scale is assumed.

Review evidence: `build/kubescape-dev/pr540-review-20261019/`. A committed SDK
archive was tested in isolation with Go1.26.8:452existing test/subtest passes,
zero skips. Added probes fail for the following two concrete cases, with passing
single-error controls. The second probe compiles the unchanged actual
`runObserved` function body with kernel acquisition replaced only for the probe.
No production code, native target, cluster or PR approval was changed by review.

1. **Failed summary masks later typed errno.** Native Run writes a failed
   `session_summary` before runObserved writes `collector_error{errno:13}`.
   Decoder.Accept makes the first failure sticky, so Finish retains a rejected
   capture but errors.Is(EACCES) is false. Relevant lines: canonical
   `internal/capture/collector.go:414–418`, `observed.go:49–62` and SDK
   `wire/decoder.go:85–88`/`wire/validation.go:24–25`.
2. **Compound failure loses permission classification even without a summary.**
   fatalState retains first EIO plus later EACCES, but observed.go:58–61 uses one
   errors.As into syscall.Errno. It serializes5, not13; the SDK cannot recover
   the second cause from the message. Relevant native first/permission ownership
   already exists in `internal/capture/fatal.go:17–39` and its tests.

Both captures stay rejected. These are cause-preservation defects, not evidence
that a failed capture currently becomes successful policy evidence.

## Global constraints

- Preserve strict schema2 admission/API and its absent observation-policy fields;
  ordinary tiered/G1 schema3 remains separately negotiated and policy-ineligible.
- JDK25 idioms; library compatibility floor JDK22. Supported host Linux-amd64.
- Readiness <=10seconds, session <=60seconds, stop <=2 remaining seconds, event
  enrichment deadline1second; no larger worker/pending/native buffer budgets.
- Keep workers4, pending1024/16MiB, native producer LRU256, frame cache16384,
  frame data3072words and perf data524288bytes/possible CPU unchanged.
- Keep decoder limits:1MiB/line, nesting64,65536rows,32768tracked occurrences,
  4096frames/terminal,4096retained pairs/32MiB retained wire payload.
- Preserve original EPERM/EACCES and cleanup causes. No success after a failure,
  no message-string errno parsing, no timeout-as-retirement or automatic fallback.
- No new dependency, breaking public API, unapproved wire addition or native ABI
  change. Task2's additive failure-field proposal needs explicit review first.
- Preserve user changes, including staged CLAUDE.md deletion and configuration
  edits. Never stage the dirty node-agent prototype wholesale or change credentials.
- Keep published v0.1.0 immutable. No invented v0.2.0/storage version, checksum,
  image digest, provider handoff, global completeness or native acceptance result.

## Task 1 — Fix failure-row ordering at the existing publication boundary

**Status:** Complete at `5b9d85e73d0d7f283919a327a6f881d7bdb6a2b9`;
task-scoped independent Spec PASS / Quality APPROVED. Issue remains open.

**Files:** Both native owners' `internal/capture/collector.go`,
`internal/capture/failure_wire_test.go` (new); mirror canonical and experimental
changes byte-for-byte. Existing wrappers are `protocol.go` and `observed.go`.

**Interface:** Private
`writeSessionSummary(Config, model.Sink, string, model.Summary, error) error`.
It returns a failed owned session to its existing wrapper without writing an
invalid summary. It preserves schema1's existing diagnostic-summary behavior.

- [x] File one scoped backlog issue using `./scripts/adkw new-issue` and the first
  recorded failing probe; do not invent an issue filename or auto-archive it.
- [x] Add the small publication-boundary regression before implementation:

```go
func TestOwnedFailureDoesNotPublishSummary(t *testing.T) {
    for _, cfg := range []Config{{production: true}, {profile: wire.Observed25}} {
        sink := &recordSink{}
        cause := errors.Join(context.Canceled, syscall.EACCES)
        err := writeSessionSummary(cfg, sink, "failed", model.Summary{}, cause)
        if !errors.Is(err, syscall.EACCES) || len(sink.snapshot()) != 0 {
            t.Fatal("owned failure serialized a misleading summary", err, sink.snapshot())
        }
    }
}
```

- [x] Observe RED, then replace only Run's final summary-write/return with this
  tested boundary. The minimal helper preserves its original writer errors:

```go
func writeSessionSummary(cfg Config, sink model.Sink, session string,
    summary model.Summary, cause error) error {
    if cause != nil && (cfg.production || cfg.profile == wire.Observed25) {
        return cause
    }
    return errors.Join(cause, sink.Write(context.Background(), model.Record{
        SchemaVersion: 1, Kind: "session_summary", SessionID: session, Summary: &summary,
    }))
}
```

- [x] Test successful summary publication, schema1 failure compatibility and sink
  failure propagation. Verify owned failure reaches the existing collector_error
  wrapper, while errReadoutPending still does not re-enter a held writer.
- [x] Use the real JSON sink and SDK decoder to prove a sole EACCES survives the
  producer boundary and no Capture is returned. Do not keep a permanent test which
  hardcodes the obsolete failed-summary-first producer order as the new behavior.
- [x] Run focused capture race tests in each owner's explicitly selected workspace;
  review connected Run/wrapper/reader/Wait paths and commit only this fix/tests.

## Task 2 — Preserve the bounded compound failure, not just its first errno

**Status:** Complete at `be3987a686dfbe49262b294c7edb253e9e8d2172`;
operator-approved bounded extension, task-scoped independent Spec PASS / Quality
APPROVED. Independent review followed the scoped commit; release/publication
approval remains separate. Issue remains open.

**Files:** SDK `wire/{record.go,json_dto.go,json.go,copy.go,decoder.go}` and
`wire/failure_causes_test.go` (new); both native owners' `capture/observed.go` and
`capture/failure_wire_test.go`. A private serializer may replace the duplicated
tail only where it fully preserves the current primary errno and returned error.

**Approval checkpoint:** Proposed additive schema3 lifecycle member:
`errno_causes`, typed `[]int`, omitted for ordinary/single-error rows, at most
three unique values: primary errno and retained EPERM/EACCES. Preserve the existing
`errno` field and message. Keep schema2 emission unchanged. Approve this precise
bounded extension before changing the public DTO; do not silently broaden schema.

- [x] File the second scoped issue, using the actual-producer EIO+EACCES probe.
- [x] Add a decoder regression using existing same-package syntheticSession and
  acceptRecords fixtures; the proposed public field is ErrnoCauses:

```go
func TestCollectorFailureRetainsCompoundPermissionCauses(t *testing.T) {
    x, rows := syntheticSession()
    failure := rows[1]
    life := *failure.Lifecycle
    failure.Kind, failure.Lifecycle = "collector_error", &life
    life.Errno = int(syscall.EIO)
    life.ErrnoCauses = []int{int(syscall.EIO), int(syscall.EACCES)}
    life.Message = "original compound native failure"
    d := NewDecoder(x)
    err := acceptRecords(d, []Record{rows[0], failure})
    if !errors.Is(err, syscall.EIO) || !errors.Is(err, syscall.EACCES) {
        t.Fatal("compound native causes were flattened", err)
    }
}
```

- [x] Observe RED. Implement only the approved optional member and bounded
  validation. If present, reject null/non-array/empty/over-three entries, null or
  duplicate entries, nonpositive/out-of-range errno values and contradiction with
  the primary errno. Permit it only on schema3 collector_error, never ready/success.
- [x] Join validated numeric causes to ErrCollector only after lifecycle/target
  binding succeeds. Never infer typed errors from Message. Deep-copy the new slice
  and update the custom DTO field inventory so it is not duplicated as Extensions.
- [x] Serialize primary errno plus independently retained EPERM/EACCES from the
  native error tree. Keep original error objects in Go return paths. Test EIO then
  EACCES, EIO then EPERM, both permissions, cancellation joins and the single-error
  unchanged encoding. No unbounded error-tree payload or new configuration knob.
- [x] Verify real producer JSON -> SDK error roundtrip, rejected empty Capture,
  unchanged strict profile fields and retained reader/Wait errors. Use candidate
  workspaces until the new actual SDK release exists; never invent a required sum.
- [x] Review the additive SDK change independently before publication (completed
  after the scoped commit, as recorded above; not a pre-commit review claim).

## Task 3 — Verify the corrected provider, then refresh the review

**Local status:** SDK/archive and owning package race results reused only after
committed/tested byte-identity checks; missing native identity/dependency-isolation
checks pass. Original full hook failed; that commit was aborted. Independent broad
review is now Spec PASS / Quality APPROVED, not release approval. Separate diagnosis
locates5s socket wall-budget expiry before direct connect, not worker/child expiry;
one unchanged focused diagnostic passes1/0/0 in2.208s with real ENOSYS cleanup errors.
Initiating delay/retirement remain unproved. One authorized unchanged full commit
checkpoint is pending at authoring; actual outcome/SHA/full XML/custody are recorded
in the local Task3 report without amending these docs to predict their own result.
Hosted PR refresh and scoped documentation-delta review remain with controller.
See [evidence](../../plans/2026-10-19-pr540-failure-fidelity-evidence.md).

**Files:** Only fixes/tests from Tasks1–2, their two owner mirrors, owning backlog
items and actual evidence docs. No unrelated cleanup or node-agent prototype.

- [x] Export the candidate SDK alone and run
  `GOWORK=off go test -race ./... -count=1`; verify only its own packages appear in
  nonstandard dependencies. Record the actual compiler and archive inventory.
- [x] In each native owner, select its separate existing prepared workspace and
  run `go test -race -buildvcs=false ./internal/capture ./internal/identity
  ./internal/model ./internal/accounting ./internal/tooling -count=1`. Never combine
  the identical native ModulePaths in one workspace or GOWORK with -modfile.
- [x] Check all shared changed files in the two owners are byte-identical. Native
  patch fingerprints need not change for a Go-only serialization repair; do not
  rewrite generated native assets or strict pins without an actual input change.
- [ ] Run focused Kotlin compatibility checks if the strict serialized path or
  paired assets change; retain the full `./gradlew build`/configured hook checkpoint.
- [ ] Refresh PR540 with the exact reviewed SHA, new negative-control evidence and
  the scope of tests actually executed. Local review is not hosted CI, maintainer
  approval, orchestrator approval or authorization to merge/publish.

## Task 4 — Publish the SDK only after required review, then pin actual bytes

**Files:** `hotspot-bridge/README.md`, both native owners' go.mod/go.sum and actual
prepared modfiles; [release plan](../../plans/2026-10-19-hotspot-bridge-release.md).

- [ ] Obtain prescribed review/control-plane approval of the exact SDK source,
  including Task2's additive API. Confirm whether v0.2.0 is still unpublished.
  Never move or overwrite an existing tag, and do not start a broad task daemon.
- [ ] Publish only the approved immutable subdirectory tag, then resolve remotely:

```bash
GOWORK=off go mod download -json github.com/Pilleo/mazewall/hotspot-bridge@v0.2.0
```

- [ ] Record actual tag target, Origin, Sum and GoModSum before changing consumers.
  Pin those real bytes, regenerate actual native prepared modfiles, and verify
  GOWORK=off rather than relying on the candidate source workspace.
- [ ] Rebuild strict/observed bundles twice with locked Go1.26.0; compare actual
  path/mode/byte inventories and full OCI graphs. Re-run changed strict gates and
  ordinary/churn/unload capture plus post-ready failure controls against actual
  rebuilt CLI/SDK bytes. Do not substitute the test harness binary for these CLI
  failure controls or call source inspection a kernel run.
- [ ] Produce provider.json only from the real release graph and accepted reports,
  following [Provider Task5](2026-10-18-hotspot-observed-provider.md#task-5-produce-an-actual-release-handoff-for-cd).
  Its historical v0.1.0 planning text is superseded by the actual v0.2.0 release plan.

## Task 5 — Finish storage/consumer review and ordinary image qualification

**Owners:** Separate storage and node-agent checkouts. Existing local starting
points are executable storage46c8444a with docs4c2e1cf and node-agent72034696.

- [ ] Progress each contribution through its scoped maintainer/control-plane
  review; do not pretend these commits are in PR540 or push another repository's
  default branch. Obtain actual selected storage module/image release identities.
- [ ] Apply actual SDK/storage release pins to node-agent, update verified sums,
  and run without the development workspace. Preserve all original prototype hunks;
  stage only owned changes and independently test the committed index.
- [ ] Run the existing connected race suites, `make binary`,
  `make check-legacy-packages` and `make check-ig-pin`. Build the ordinary image with
  its actual baseline tracers and test disabled startup/baseline operation.
- [ ] Recheck actual served OpenAPI and JSON/protobuf typed Create/Get and UID
  cleanup on the selected storage graph. Historical local B3 evidence is not a
  remotely published storage/image identity.
- [ ] Produce a ready C handoff only after those gates. Leave all unavailable
  version/sum/image fields null and releaseReady false until then; existing local
  evidence manifests must not be promoted by editing flags.

## Task 6 — Execute D only when A/B/C handoffs really exist

**Implementation:** Execute Tasks1–4 of the existing
[native Kubernetes acceptance plan](2026-10-18-kubescape-agentless-hotspot-acceptance.md).
Its derived image, owned runner, independent saved-profile verifier, workloads,
failure controls and opt-in CI are not replaced by another harness here.

- [ ] Reacquire a disposable native environment within the explicitly approved
  scope, or obtain approval for any changed topology/resource/host exposure.
  The prior VM and kind server are retired; their Ready/PID/node receipts are
  historical. Revalidate new initial-PID/BTF/cgroup/kernel/node/TLS identities.
- [ ] Package verified provider bytes on the recorded digest-pinned node image.
  Preserve independent worker/token/route expectations and actual process/image
  bindings; no workload agent, runtime registry fallback or Docker socket in node-agent.
- [ ] Run saved-profile acceptance with complete typed symbols/ordered callers,
  stable content IDs, explicit false authority flags and all existing deadlines.
  Exercise target exit/exec, permission denial, unavailable JVM, incompatible
  storage and pending-owner retirement. Do not relabel a rejection as success.
- [ ] Retain raw/typed/API evidence and original errors, delete fixture profiles
  using their actual UIDs, and prove physical collector/VM/cluster retirement.
  Close the overall integration issue only with this complete reviewed evidence.

## Completion and non-goals

First deliverable: fix findings1–2 and obtain a fresh review. Later deliverables:
actual released A/B/C handoffs, qualified image and native D evidence, in that order.
Do not add dependencies, a new supervisor framework, more budgets, general JIT/GC/
Loom guarantees, a lifetime lease or policy eligibility. Tasks1–2 are committed and
independently task-reviewed; Task3 is local verification/documentation only. No PR
refresh, release/control-plane approval, tags or new provisioning is claimed here.
