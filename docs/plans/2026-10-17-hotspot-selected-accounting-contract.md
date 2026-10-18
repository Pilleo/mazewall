# Selected HotSpot accounting contract

Decision: **`operator_requested_scoped_implementation_and_selected_qualification_completed`** (2026-10-18).

The operator explicitly requested continuation of the reviewed map/schema/pin scope
in the corrected Plan04 Task2 dispatch; the controller ledger records approval by
reference. This supersedes the earlier implementation gate for that exact scope.
The corrected early-validation/post-acquisition binding boundary below is included.
The scoped implementation and retained native/public qualification are completed
at `70f5ce9e138a0b405b8421a94f5319fa9a124916`, as recorded below and in the
[production inventory evidence](2026-10-17-hotspot-production-loss-evidence.md).
Historical proposal/approval/proof gates below retain their original context;
they are not requests to rerun completed work. This grants no broader runtime
authority, global completeness, policy eligibility or JIT/GC/Loom/FFM/digest
activation. The separate [identity allocator](2026-10-18-hotspot-correlation-identity-evidence.md#proposed-approval-boundary-and-future-gates)
remains unapproved and unimplemented.

Historical Plan04 Task2 Step1 was documentation only. Its original gate was:
explicit operator approval of the map, optional diagnostic and provenance transition
is required before any native/BPF/Go/Kotlin, ABI/schema/pin or generated-graph edit.
Approval of this document's preparation is not approval to implement its proposal.
Baseline: `0bf71521ff3317440f1898e5c33f2218dbf997d9`, branch
`feat/hotspot-selected-accounting-binding`. The existing loss issue remains open.

## Grounding and scope

The [shared specification](2026-10-17-hotspot-remaining-work-spec.md), global
requirements and R4, and the same-plan Task1 report/progress govern this proposal.
Task1's existing `Binding`, `Snapshot` and `BindAndEvaluate` are pure host proof;
literal identities/flags do not establish native acquisition or finality.

Sources inspected at the historical documentation baseline (relative to the
repository root) were:

- `profiler/hotspot-agentless/gadgets/openat-stack/program.bpf.c`: PID/leader-birth
  selection at lines 55–64, key-zero sequence lookup at 65–69, reserve at 70–72,
  submit at 132. There was no production `selected_counts` map at that baseline.
- `profiler/hotspot-agentless/patches/gadget-additions/pkg/operators/event_finalization.go.txt`
  and `ebpf/event_finalization_{owner,backend,native,reader}.go.txt` in that same
  additions directory: current optional drain API, ordered stop owner and reserve
  counter sampling. `patches/gadget-finalization.patch` adds owner fields/lifecycle.
- `profiler/hotspot-agentless/internal/capture/collector.go`: admitted session,
  PID/birth, full-drain handoff, ready callback and final stats;
  `internal/capture/event_finalization.go`: parent snapshot conversion;
  `internal/model/record.go`: existing summary and partial loss diagnostics.
- `profiler/hotspot-agentless/internal/accounting/{binding,conservation}.go`:
  exact identity, per-CPU conservation, checked totals and scoped loss evaluation.
- `profiler/hotspot-agentless/internal/tooling/{prepare,assets}.go`, `Makefile`
  and `dependencies.lock.json`: patch derivation, bundle verification/build inputs.

The experiment is a materialized snapshot, not a symlink. Future implementation
must mirror only approved shared files, not experimental drivers or whole generated
directories. This contract changes neither snapshot, graph, pin nor runtime.

## Proposed native map and producer denominator

Map name `selected_counts`; `BPF_MAP_TYPE_PERCPU_ARRAY` / Go `ebpf.PerCPUArray`;
key size **4**, value size **32**, max entries **1**, only key `uint32(0)`.
The 8-byte-aligned value has four unsigned 64-bit fields, no padding:

| C field | Go field | Byte offset | Width |
| --- | --- | --- | --- |
| `attempted` | `Attempted` | 0 | 8 |
| `submitted` | `Submitted` | 8 | 8 |
| `reserve_failed` | `ReserveFailed` | 16 | 8 |
| `overflow` | `Overflow` | 24 | 8 |

Increment attempted after successful PID/leader-birth selection, before sequence
lookup. Increment reserve_failed on the actual reserve-failure branch; increment
submitted only after the real `gadget_submit_buf` call. Preserve all existing
event/stack/helper capture and early-return semantics. Counter updates must be
atomic under possible same-CPU nesting; any unsigned wrap sets sticky overflow
nonzero (atomic OR), which invalidates the entire proof. No resets or inferred
zero rows. A sequence-map early return after attempted is an unclassified lane:
it cannot be certified by assuming a reserve failure; a mismatch remains unknown.
The fixed map entry is instrumentation, not a delivery-buffer enlargement.

Loader admission must validate the specified layout and the acquired map's actual
metadata. A missing/unsupported map rejects explicitly negotiated accounting;
permission, lookup and acquisition failures retain their causes, never zero-fill
or silently fall back. Ordinary capture without negotiation keeps existing partial
semantics. Production provenance admission remains exact, not capability guessing.
There is no event ABI/layout, ring size, capture ABI 2 or wire schema 2 change.

## Proposed optional interfaces and binding authority

The additive operators proposal is:

- `SelectedAccountingTarget{Session string, PID uint32, Birth uint64}`;
  optional `Accounting *SelectedAccountingTarget` on `EventFinalizationOptions`.
- `SelectedCPUCounts{Attempted, Submitted, ReserveFailed, Overflow uint64}`.
- `SelectedAccountingObservation{Target SelectedAccountingTarget, MapID uint32,
  PossibleCPUs int, Rows []SelectedCPUCounts, Quiesced bool, Joined bool}`;
  optional `SelectedAccounting *SelectedAccountingObservation` on the existing
  owner observation, JSON `selected_event_accounting,omitempty`.
- A prepared-identity getter on the actual owned `ebpfInstance`,
  `SelectedAccountingBinding() (operators.SelectedAccountingObservation, bool)`,
  returning captured identity by value without accessing a retired map. Collector
  conversion uses Task1's separate expected `Binding` and actual final `Snapshot`.

Negotiate only within the existing explicitly owned `cfg.DrainFull` profile.
Copy the admitted nonempty session, positive host PID and nonzero process birth;
validate PID/birth against the actual `target_pid`/`target_start_ticks` producer
parameters. Acquire the actual collection map, its successful `Info().ID()`
(nonzero), and `ebpf.PossibleCPU()` (>0), retaining the owned map object and an
immutable prepared identity. No target-supplied map ID/CPU count/finality flags.
No invented FD/token, process-global map-generation cache or late map discovery.

Before ready/attached is published, collector obtains the prepared getter from
`gc.GetVar("ebpfInstance")`, checks it independently against its immutable admitted
session/PID/birth, and stores a session-local expected Binding. The final snapshot
must not supply its own expected MapID. Missing getter/owner/binding is an error.
The prepared binding must be safely published once before consumers start and
never mutated; subsequent reads occur after the owned runtime returns.

At final sampling independently check that the collection still owns the exact
acquired map object, its actual ID still matches prepared MapID, actual layout
still matches the contract, and Cilium eBPF v0.22.0's process-cached boot-fixed
possible-CPU cardinality equals the prepared count. This repeated `PossibleCPU()`
call is not an uncached final sysfs/permission re-probe. Metadata/ID checks and
key-zero counter lookup remain actual final operations on that same live map
before retirement. Require exactly one row per possible CPU, including offline
possible CPUs. Reject missing/extra slots, substitution, unavailable IDs,
cardinality mismatch and all actual read errors.

### Possible-CPU cardinality clarification (FFR-M03)

The selected dependency is **github.com/cilium/ebpf v0.22.0**, pinned in
[`go.mod`](../../profiler/hotspot-agentless/go.mod) and selected by the prepared
`profiler/hotspot-agentless/build/experiment.go.mod`.
Its official tagged [`cpu.go`](https://raw.githubusercontent.com/cilium/ebpf/v0.22.0/cpu.go)
delegates to `possibleCPU`; [`cpu_other.go`](https://raw.githubusercontent.com/cilium/ebpf/v0.22.0/cpu_other.go)
uses `sync.OnceValues` around `/sys/devices/system/cpu/possible`. Later calls reuse
that process-cached result (including an initial error), rather than detecting a
new sysfs access/permission failure. The original literal freshness claim is
corrected, not claimed executed.

The official [kernel CPU-map contract](https://docs.kernel.org/core-api/cpu_hotplug.html#cpu-maps),
retrieved as **Linux 7.3.0-rc7**, describes `cpu_possible_mask` as static after boot
discovery, unlike online/present masks. This explains possible-CPU slot accounting;
it does not claim inspection of the historical **7.2.10** host's source/config or
a runtime hotplug experiment. Existing actual 12-slot acquisition/read and
missing-slot rejection receipts are linked in the
[production evidence clarification](2026-10-17-hotspot-production-loss-evidence.md#follow-up-evidence-clarifications-ffr-m02ffr-m03).

## Real stop ordering, ownership and failure paths

Preserve the existing stop backend interface and reserve-sum `Sample` return.
The owner must establish, in order:

1. Successful real owned-link detach (`Quiesce`), not merely cancellation.
2. Successful real ring flush (`Flush`).
3. Actual receiver and in-flight callback completion, `eventDrain.done` result and
   instance waitgroup join. Full drain also successfully completes AfterReadout:
   definition drain, enrichment Finish and auxiliary StopAuxiliary joins.
4. Only then selected-map final read in `Sample`, along with the existing reserve
   sample. Publish owned observation under `eventStopOwner.mu`; do not hold that
   mutex across joins or call `snapshot()` while already holding it.
5. Successful reader/map/program retirement. Only parent `resources_retired` with
   ProducersQuiesced, ReceiverJoined, FinalCounterRead, BufferDrained and
   ResourcesRetired true permits collector proof conversion from final stats.

The existing stop state machine, not a target assertion, establishes Quiesced and
Joined. `EventFinalization()` continues to delegate to the owner's locked
snapshot. `copySnapshot` must deep-copy the optional observation and its Rows while
holding the same mutex, so neither returned pointer nor row slice aliases owner
state. No unlocked instance-level diagnostic publication channel is allowed.

A held callback past the unchanged deadline yields error/pending owner, no final
sample or complete diagnostic, and retains resources while native use continues.
Detach/flush/receiver/definition/auxiliary errors retain the native cause. A failed
sample or later retirement failure cannot certify finality; any intermediate stored
rows are non-authoritative until the parent is final. Pending owners remain
inspectable under existing lifetime handling; no timeout is a physical join and no
new forced-close, retry lifecycle or deadline extension is implicitly approved.
Collector must preserve joined errors and cannot mark cleanup/terminal accounting
complete after selected read/binding failure. It must avoid locking for a summary
under an active callback, as the current early pending-return path already does.

## Proposed optional summary diagnostic (schema 1)

`Summary.selected_event_accounting` is optional and omitted for non-accounting
captures. Its additive diagnostic is distinct from outer production wire schema 2:

| JSON field | Type / meaning |
| --- | --- |
| `schema_version` | uint32, exactly 1 |
| `scope` | string, exactly `owned_openat_handler_entry` |
| `session_id` | admitted session string |
| `host_pid` | uint32, admitted PID |
| `process_start_ticks` | uint64, admitted birth |
| `map_id` | uint32, actual prepared and rechecked map ID |
| `possible_cpus` | int, positive acquired and rechecked cardinality |
| `attempted`, `submitted`, `reserve_failed` | uint64, checked per-CPU totals |
| `received` | uint64, actual final enrichment Received |
| `terminals` | uint64, checked Resolved + Unavailable + Skipped |
| `complete` | bool, selected loss-accounting proof only |
| `reason` | optional string, incomplete proof reason |

Numbers remain JSON numbers, not strings. Binding/layout/read failures propagate
errors rather than fabricated zero diagnostics; valid but nonconserving/nonfinal
input cannot yield complete. Each CPU must have overflow=0, submitted<=attempted
and reserve_failed=attempted-submitted before checked uint64 aggregation. Reject
aggregate/terminal addition overflow; require received<=submitted and
terminals=received. A complete all-zero result requires the same real acquisition,
all CPU slots and actual finality as any other proof. Zero never implies final.

Complete means the selected loss denominator is known, including possible loss:
Task1 computes lost=attempted-received. It does **not** mean zero loss, complete
definition/auxiliary/enrichment/wire/retention collection, global collection loss
completeness, or policy eligibility. Global CollectionHealth/EventLossObservation,
LossComplete/collectionLossComplete and selected-openat policy refusal keep current
semantics. Missing source denominators and unknown admission lanes stay unknown.

## Historical baseline identities: observed, not replacements

Read-only checks on 2026-10-17 at the baseline:

- `go run ./cmd/mw-collector-tools patch-id`, run separately from canonical and
  experimental module directories, both returned
  `618c3d67d71e86dfb0befb13f73498402af6110f1f2fceb3784c53785b0da00c`.
- Canonical `go run ./cmd/mw-collector-tools verify --bundle build/assets` exited 0,
  rehashing assets and validating the local OCI graph. Manifest SHA256:
  `0584b244540dbc615a126b690144b68eb774963f6c5c969a252445abb5669b04`.
- Current manifest: schema 1, collector version 2, capture ABI 2, wire schema 2,
  linux-amd64; Gadget revision `7097db144ccd8dab15373f27ec98d2c3d22e4588`,
  profiler revision `ef6cf29492c8a980f889052903fa2dd0c0765cc0`.
- Current collector bytes SHA256:
  `afb3fd5a0a994a06ed70e42689abe586b926b21fe2967db2c423c73a60c3907e`.
- Current gadget OCI digest (manifest and local verified graph):
  `sha256:3ae6007bdf574e1c082b5f185f38d3f60bb678d9e964f05a658da4ac5670de68`.
- `image --key go` returned
  `golang@sha256:fb612b7831d53a89cbc0aaa7855b69ad7b0caf603715860cf538df854d047b84`.
  Lock also pins builder
  `ghcr.io/inspektor-gadget/gadget-builder@sha256:dba339d2a2f189ae5fcdc08730e6cac128d4ed30c3af42e5fb93a5c8cf4c77aa`
  and JDK
  `eclipse-temurin@sha256:119a3d18f160a3e7655a66034d0f43beee31cd7b3b9142d57a5de29772011de6`.
- There is no experimental `build/assets/manifest.json` in this checkout; equality
  of patch inputs does not establish an experimental built bundle identity.

At this historical baseline the next accepted patch digest and built image did
not exist. The implementation identities below were subsequently computed from
approved authored inputs and actual reproducible builds; historical receipts are
retained with their original identities and linkage limitations.

### Deterministic transition required after separate approval

These acceptance requirements were originally future work under the Step1 dispatch
and are now within the operator-requested implementation scope:

1. Resolve the review conditions below, implement only the approved sources/tests
   canonically, then mirror matching native/BPF/addition/model/test files explicitly.
   Record mirrored path/SHA256 pairs; do not copy drivers or generated directories.
2. Compute `go run ./cmd/mw-collector-tools patch-id` canonically. `prepare.go`
   hashes each relative input path, NUL, then exact bytes: fixed Gadget patch list,
   lexically sorted Gadget `.go.txt` additions, fixed profiler patch list, rebuild
   script, sorted profiler additions (standard mode). No invented combined digest:
   BPF/collector/model bytes are additionally bound by built assets/OCI hashes.
3. Verify a lowercase 64-hex result different from the current pin; reconcile
   baseline differences before replacements. Synchronize that exact result across
   **all nine** current pin-bearing source/test consumers:

   - `profiler/hotspot-agentless/internal/tooling/assets.go`
   - `profiler/hotspot-agentless/internal/tooling/prepare_test.go`
   - `experiments/hotspot-agentless/internal/tooling/assets.go`
   - `experiments/hotspot-agentless/internal/tooling/prepare_test.go`
   - `profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotAssets.kt`
   - `profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotProtocol.kt`
   - `profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotAssetsTest.kt`
   - `profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotProtocolTest.kt`
   - `profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotChildFixture.kt`

   If the upstream-alignment source audit has since been implemented, its canonical
   and mirrored `internal/qualification/jitgc/capture_audit.go` and
   `capture_audit_test.go`, plus the valid-pin fixture in experimental
   `internal/candidate/capture_audit_test.go`, are five additional reviewed-key consumers. Repeat actual
   source characterization/review before advancing those keys; until then the new
   overlay remains unaudited and candidate source-contract generation rejects it.

4. Recompute canonical and experimental patch IDs and require equality; run existing
   `prepare` in both module directories, record generated patch manifests and exact
   source graph. Reject the old patch pin at new production admission; do not admit
   an old graph just because an optional diagnostic is absent.
5. Preserve the old bundle/build/hash evidence before creating a fresh destination
   (`pack` refuses an existing output). From the canonical module use the existing
   `DOCKER_HOST=unix:///var/run/docker.sock make bundle DOCKER_HOST_ARG='--host unix:///var/run/docker.sock'`.
   Record locked inputs and `SOURCE_DATE_EPOCH=1790985600`, actual builder output,
   `build/gadget-image.ref` derived by `oci-ref` from the exact local tag and OCI
   content, and packed manifest. Do not derive the image from a patch digest.
6. Reproduce helper bytes twice on the same pinned Go image with the existing
   container-build environment: CGO_ENABLED=0, GOOS=linux, GOARCH=amd64,
   `-modfile build/experiment.go.mod -trimpath -buildvcs=false`, ldflags
   `-s -w -X io.mazewall/experiments/hotspot-agentless/internal/capture.GadgetPatchSHA=<computed-id>`.
   Preserve the first bytes/hash before the second build; require equality. Preserve
   and compare actual repeated gadget OCI output under identical locked builder,
   source and epoch inputs before claiming graph reproducibility. Any mismatch
   blocks acceptance rather than receiving a guessed digest.
7. Run `go run ./cmd/mw-collector-tools verify --bundle build/assets`; rehash every
   helper/OCI byte and validate the whole local graph and revised exact provenance.
   Record actual new helper/image/manifest identities and old-pin refusal. No
   metadata digest cache, startup compiler/download, fallback or rewritten old evidence.

## Required future proof gates

No native proof is claimed by this contract. Approval alone does not satisfy these:

- Host/race controls: every identity field mismatch, invalid expected identity,
  nil/empty/missing/extra CPU slots, unjoined/zero flags, sticky and aggregate
  overflow, contradictory CPU tuples, delivery/terminal mismatch, checked terminal
  sum, immutable owner snapshots, and missing capability/map/parameter rejection.
- Real acquired-map controls: two distinct actual kernel map IDs, substitute the
  collection map before final Sample, reject a direct pre-join Sample, omit a CPU
  slot, and prove no complete/public diagnostic. Pure Binding fixtures alone cannot
  prove native owner ordering. Retain original kernel/read/permission causes.
- Actual held callback test
  `TestKernelEventFinalizationDetachesDrainsAndSamplesWithoutLaterEvent` and host
  `TestEventFinalizationDeadlineNeverRetiresActiveCallback`: hold past existing
  deadline, assert pending/error, no late sampling/unsafe retirement. Exercise
  detach, flush, receiver, definition and auxiliary failure boundaries as well.
- `TestKernelOpenatCountersPressureAndOverflow`: real 512-attempt denominator,
  pressure reserve_failed>0, all possible CPU slots, per-CPU equations, checked
  totals and sticky overflow refusal. Run mw_kernel owning Gadget tests and actual
  selected map binding tests using the pinned Go image, privileged rootful endpoint
  and host PID namespace; missing/denied capability fails the gate, no unconditional
  skip. Isolated asm/test owners cannot replace a compiled production proof.
- Go host/race accounting and prepared-graph capture/Gadget owner suites; Kotlin
  HotSpotAssets, Protocol, WireReader, Coverage, Compatibility and legacy ABI checks.
  Verify optional diagnostic absence compatibility and new pin rejects old bundle.
- Live installed public `HotSpotPublicIntegrationTest` against the actual newly
  verified compiled bundle (`MW_HOTSPOT_NATIVE_ACCEPTANCE=true`, explicit assets
  directory and rootful Docker endpoint): assert scope/schema, session/PID/birth,
  actual map/CPU binding, attempted-submitted=reserve_failed, received<=submitted,
  received=final summary received=terminals, complete selected accounting and
  **false global collection loss completeness**. Preserve actual live artifacts,
  unchanged public policy refusal, budgets and real resource joins.
- Full mandatory quality/commit gate with retained log; new evidence must describe
  cache/skips honestly and cannot substitute a model or prior graph's test result.

## Approval conditions and source inconsistencies requiring review

The following records the original review conditions. The scoped implementation
below resolves acquisition timing, metadata copying, publication and sampling;
the remaining broader qualification boundaries are still explicit.

1. **Acquisition timing:** current materialized Gadget `ebpf.go` calls
   `prepareEventFinalization` at line 734, before `NewCollectionWithOptions` at 890
    and `i.collection = collection` at 899. The original brief's bind call at prepare
    rejected `i.collection==nil` and could not work there. The revised proposal keeps
    strict target parsing and by-value options copying in early prepare, then calls
    `bindPreparedSelectedAccounting()` immediately after the actual collection
    assignment, before tail-call setup, map publication991, tracer startup1002 and
    attachment1012. Bind failure joins its original cause with checked cleanup of
    that acquired/unpublished collection. Review and prove this partial-start path
    and write-once getter publication before implementation approval; no native
    source relocation or new receipt is authorized by this planning correction.
2. **Identity API and locking:** `gc.GetVar("ebpfInstance")` exists in the current
   graph, but the proposed getter does not yet exist. Prove its availability before
   collector OnStart, write-once publication/happens-before and final read ordering.
   The proposed `sampleAfterJoin` helper itself only checks map ownership, not joins;
   production entry must enforce actual owner phase and successful effects. The
   phase check and owner-mutex publication must not race failure/deadline reporting
   or deadlock with snapshot. An observation published before reserve sampling or
   retirement succeeds remains partial and must never pass the parent's final gate.
3. **Parameter and metadata boundaries:** current Param.AsUint64 parses its stored
   decimal string and discards parse errors; its availability is not proof of valid
   PID width/type. Review validated parameter descriptors/ranges and actual map
   metadata (not only mutable collectionSpec), positive IDs/cardinality and map
   replacement protection. Preserve all failures rather than accepting zero defaults.
4. **Semantics and compatibility:** approve the exact map layout and schema1 optional
   diagnostic, binding getter/API visibility, production pin migration and all nine
   consumers together. Verify legacy ABI/public refusal and unknown lanes. The
   existing unbounded `wg.Wait()` cannot be turned into a synthetic join on timeout;
   preserve pending-owner semantics under the unchanged deadline.
5. **Fixed production envelope:** Linux-amd64, Temurin25.0.4.1+1-LTS,
   `-Xint -XX:+UseSerialGC`, synchronous `openat_handler_entry`, JDK22 library floor.
   Readiness<=10s, session<=60s, stop<=2 remaining seconds, enrichment deadline1s;
   workers4, pending1024/16MiB, producer LRU256, frame cache16384, frame data3072
   words, perf data524288 bytes/possible CPU, ring `1024*256` unchanged. Kotlin
   retention4096/33,554,432 payload bytes, line1,048,576 bytes, rows65,536,
   frames4096, tracked occurrences32,768 unchanged. No dependency, host-policy,
   fallback, source budget or profile adjustment is included.

The operator-requested implementation above resolves the authorization gate for
this reviewed scope. These conditions remain implementation/proof requirements;
qualification is pending until the actual native/live and provenance gates pass.

## 2026-10-18 scoped implementation and evidence

The canonical implementation and exact materialized mirror now negotiate the
optional selected capability for owned full drain. Strict metadata validation and
by-value target copying occur in prepare. Actual map object/ID/layout and possible
CPU binding is acquired immediately after collection assignment, before map
publication, receiver startup and attachment. Failed binding joins its cause with
checked collection cleanup. The write-once getter is synchronized with the existing
instance mutex; final observation publication/deep copying uses the owner mutex.
Final sampling follows successful detach/flush/receiver, callback, definition,
enrichment and auxiliary joins and precedes collection retirement. Collector proof
additionally requires the fully retired parent and final delivered statistics.

Computed accepted patch (both modules):
`270a89f6dafb03afe2131b219f98673e30be79223eb68a11a5703b66d8f6a127`.
Actual gadget digest:
`sha256:5c5791bb47beba9568d98216abf7af4a03abe9093d1d024d511a26e645e051e9`.
Twice-built helper SHA256:
`25fcd41ce47a0c6f7aae841fa2d807313c796d90d2f9ca74f82772c8a2094ef7`.
Packed manifest SHA256:
`1fcf764192a645ad237aed82d004162ad793a423554f2d13c4b5ce4ac813764d`.
Repeated locked Go/builder/epoch builds matched every helper, gadget-output and
bundle byte; complete helper/OCI graph verification passed. Old-pin admission
remains refused. The nine baseline provenance consumers and five reviewed-source
keys move together only after actual new-graph compile-ID characterization/source
review. The source audit still rejects unreviewed keys and always reports production
qualification false; no runtime lifetime/lease authority is added.

Host/race and real rootful `mw_kernel` controls cover independent identities,
actual map substitution/layout, all possible CPUs, sticky overflow, conservation,
pre-join refusal, metadata/options ownership, held callbacks, failed acquisition
cleanup and publication ordering. The isolated native 512-attempt pressure control
is distinct from the actual compiled-producer public acceptance run. The public
suite exercises the schema1 raw selected diagnostic and unchanged policy refusal;
capture ABI2, wire2, event/ring layouts and all delivery limits stay fixed.

This implements selected accounting only. Dense public readout may still reject;
its raw failure/JFR artifacts and original identity-failure receipts remain evidence,
not an identity repair. Global/all-source completeness and policy eligibility remain
false. The parent issue and broader qualification remain open pending independent
controller review and the still-missing all-source/lifetime mechanisms.
