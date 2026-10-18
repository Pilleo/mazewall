# Structured production loss-source inventory

## Scope and authority

Plan04 Task3 implements the R4 inventory in the
[remaining-work specification](2026-10-17-hotspot-remaining-work-spec.md#r4-real-loss-accounting).
The controller authorized an optional raw-summary diagnostic and its native public
assertion. Task2's independently approved
[actual selected owner](2026-10-17-hotspot-selected-accounting-contract.md) remains
the sole production source admitted by this inventory. Global collection loss
completeness remains false and policy conversion remains refused. The
[loss issue](../internals/backlog/issue-20261005-193140-042-prove-selected-hotspot-event-conservation-and-all-.md)
stays open.

`accounting.Lane`, `LaneEvidence{Binding, Final, Counts}` and `MissingEvidence`
cover `selected_events`, `definitions`, `enrichment`, `wire`, `retention`, in that
stable order. Every absent, non-final, incorrectly bound, unknown or nonconserving
lane remains missing. An invalid expected binding makes all required lanes
missing, even if every supplied binding equals that invalid value. Unexpected map
keys are additional sorted `unrecognized:<key>` model diagnostics, including an
empty key; they can never yield an empty/complete inventory. Existing
`Inventory`/`Missing` retain their compatibility behavior.

These pure models accept supplied facts; booleans and all-zero rows are not native
acquisition/admission receipts. The collector's `applyCollectionInventory` calls
Task2's `selectedDiagnostic` on the independent OnStart binding, actual final native
rows, physically retired parent and delivered enrichment stats. Per-CPU equations,
sticky overflow, CPU cardinality, checked sums and identity must pass before the
selected lane can leave the missing list. A prepopulated model `complete=true` is
discarded. Other lanes are never constructed from selected-map identity, reader
exit, zero reader loss, terminal accounting or supplied synthetic Final flags.

Full owned captures emit `Summary.unproven_collection_sources`, an array of finite
lane strings, through the existing JSONL sink and public `summary.rawJson`. Healthy
selected-complete captures emit exactly:

```json
["definitions", "enrichment", "wire", "retention"]
```

Absent/invalid selected proof adds `selected_events`. Missing all-source evidence
alone adds no error; selected-owner failures retain the existing error path.
Non-full captures and early partial returns may omit the optional diagnostic;
omission is not completeness. There is no new global completeness boolean, public
Kotlin descriptor, event layout, native interface or policy promotion.

## Actual source owners and missing proof

| Lane | Actual owner / independent source identity | Current proof disposition |
| --- | --- | --- |
| selected_events | Task2 acquired `selected_counts` PerCPUArray; session/PID/birth/map ID/possible CPUs, rechecked after native detach/readout joins | Proved only when the actual final native binding and all CPU/delivery checks pass |
| definitions | OTel `trace_events` perf producer/reader and correlated definition store; auxiliary `report_events`, polling/netlink, executable-map and prune owners | **UNPROVEN** admission/delivery denominators, including auxiliary admission; their source-specific kernel IDs/counters were not acquired as inventory receipts |
| enrichment | Owned queue/workers and event-to-terminal sink | **UNPROVEN** whole-source admission; selected Received/terminal counts prove only selected delivery conservation |
| wire | Actual native helper stdout/JSONL through the public reader/accumulator | **UNPROVEN** source-specific native-to-public denominator and final receipt |
| retention | Public bounded client accumulator, outside the native helper | **UNPROVEN** end-to-end source-specific retention receipt; local discard counts do not admit this lane |

The four unknown owners are not assigned the selected event map's ID or zero loss.
Source-specific counters/owners for their promotion are future work, not synthetic
model input supplied by this task.

## RED/GREEN and fault evidence (2026-10-18)

Local receipts are retained under
`.superpowers/sdd/2026-10-17-hotspot-production-loss-binding/task-3-inventory-20261018-evidence/`.
Each command records explicit cwd, arguments, time, inputs before execution, exit
and output hash. Effective Go package/source/embedded-input selections precede
compilation. Canonical/mirrored sources are byte-identical.

- **RED:** new accounting/capture tests fail on absent lane/helper/summary symbols.
  The new native public assertion against the unchanged old helper fails specifically
  on missing `unproven_collection_sources`, after actual corpus/selected validation.
- **GREEN:** canonical host/race: 219 passing test/subtest events, ten passing
  packages; one upstream `TestEmpty` skip and four no-test packages are explicit.
  Mirrored accounting/capture/model/tooling: 129 passing events/four packages, no
  skips. Definition/auxiliary source-owner host/race: 21 passing events/four
  packages, no skips. Focused Kotlin: 53 tests, zero failures/errors/skips;
  coverage/refusal, legacy copy descriptors, raw diagnostics, zero retention,
  malformed/truncated wire and asset graph refusal checks pass. Kotlin ABI and
  integration-source lint checks pass.
- **Actual definition drop:** the existing test-only fault patch drops exactly one
  genuine symbolized `InlineOpenFixture.pathA` definition, ID538553608960934,
  PID1643430. The existing recovery gate passes: 400 selected/delivered/terminal,
  350 resolved/50 unavailable, 40 recovery attempts/one invalidation. Actual
  map27902 binds12 CPUs; selected complete=true, retired parent/globalfalse and
  all four other lanes remain unproven despite reader lost=0. This is a fault
  variant, not the production helper. The patch is applied as an isolated Go
  overlay to the unchanged prepared Gadget sources; no BPF rebuild or generated
  dependency edit is used. Initial preparation failed on the inactive default
  Podman endpoint, and initial overlay/reference setup failures remain retained.
- **Actual held reader/auxiliary:** native perf drain delivers8/8 and5000/5000
  definitions only after final callback acknowledgment. Held auxiliary callback
  and idle netlink controls pass. Task2's unchanged independently receipted event
  owner binary is reused: held callback retains maps past500ms, deadline refusal
  publishes no selected observation; release/success drains8/8 and
  1000attempts/255delivered/745reserve failures. These physical joins do not
  supply definition/auxiliary admission counters; `definitions` remains unproven.
- **Actual wire write failure:** close the FIFO reader of the current production
  helper's stdout after native ready and one genuine event. The helper exits141
  (SIGPIPE), its host PID1647031 is reaped, containers are checked absent, and the
  three-row prefix has no final summary. No normal parent retirement or complete
  inventory is inferred from the aborted stream. Existing owning Go output-failure
  and Kotlin newline-truncation/protocol controls pass; those host controls are
  not native wire-admission counters. `wire` remains unproven.
- **Actual retention0:** the public native fixture retains zero of502 delivered
  occurrences, accounts for all502 terminals/discards, selected complete=true,
  checked physical drain and no pending owner. It reports the same four unknown
  lanes, including `retention`. This replaces the existing retention1 pressure
  fixture's budget with0; no production budget increases.
- **Actual target exec:** the unchanged owning kernel test observes same-PID exec
  poisoning before the replacement image runs. No post-exec evidence is promoted;
  failed/partial captures cannot prove selected or global completeness.

No skip or host model is used as a native fault demonstration. The above controls
do not establish missing source-specific denominators or repair the separately
retained native identity failure.

## Fresh public selected/inventory coupling

The explicit rootful native acceptance run executes all8 tests with zero failures,
errors or skips (native test time210.643s). The installed public strategy preserves
all400 marked operations and802 occurrences. Its raw selected observation is:

```json
{"schema_version":1,"scope":"owned_openat_handler_entry","session_id":"34863e541be9c3f9fd2ac771cfd70777","host_pid":1616855,"process_start_ticks":53821509,"map_id":26743,"possible_cpus":12,"attempted":802,"submitted":802,"reserve_failed":0,"received":802,"terminals":802,"complete":true}
```

The same raw summary contains exactly the four unproven lanes, resources_retired
parent, all five finality flags=true and parent loss_complete=false. Public
collectionLossComplete=false, pending ownership=false and dedicated-policy
conversion refuses. Retained artifact:
`live-public-artifacts/reports/379ef107bdfb4cfa-public/evidence.json`.
Its new assertion executes on both installed strategy and retention0 paths.
Whitespace-only assertion formatting corrections subsequently pass focused lint;
the live evidence is reused, not claimed rerun after formatting.

The ordinary current-JVM control has60 selected/delivered/terminal. Dense
success-or-rejection diagnostics and their partial/JFR outputs are preserved;
passing that matrix does not promote dense readout, repair prior identity failures
or authorize broader JIT/GC/Loom runtime profiles.

## Reproducible helper and complete graph

Old Task2 build/assets/evidence are preserved before replacement. Current authored
patch/addition/rebuild/BPF inputs are unchanged, so both recomputed patch IDs remain
`270a89f6dafb03afe2131b219f98673e30be79223eb68a11a5703b66d8f6a127`.
No source-audit/provenance key is advanced for these Go-only inventory changes.
Prepared capture/decoder/native objects remain unchanged; no BPF build is needed.

| Identity | Task2 preserved | Task3 current |
| --- | --- | --- |
| helper SHA256 | `25fcd41ce47a0c6f7aae841fa2d807313c796d90d2f9ca74f82772c8a2094ef7` | `dfe4953f303f22a83a23417bd519083e451ff413d346be5828ab06c8e0ec2487` |
| manifest SHA256 | `1fcf764192a645ad237aed82d004162ad793a423554f2d13c4b5ce4ac813764d` | `4bc6918073cf5c403d931aeb98868cae4dc6b4e2e3eaf1db2d8a80e7a00b084f` |
| OCI root | `sha256:5c5791bb47beba9568d98216abf7af4a03abe9093d1d024d511a26e645e051e9` | same actual existing graph |

Two actual `make container-build` executions use locked
`golang@sha256:fb612b7831d53a89cbc0aaa7855b69ad7b0caf603715860cf538df854d047b84`,
actual Go1.26.0. Each has prior compiler/linker/assembler, effective selected source,
embedded-input, module/replacement and environment receipts. The helper is
68,276,386bytes. Both helpers, both manifests and all10 bundle files are byte-identical;
both complete graph verifications pass using the actual existing OCI store, without
rebuilding BPF. CaptureABI2/wire2, dependency pins and source epoch1790985600 stay
unchanged. This proves only the newly built bytes; historical old-helper provenance
gaps remain inferred/unverified. Native and global loss qualification remain scoped
as above.

## Follow-up evidence clarifications (FFR-M02/FFR-M03)

The consolidated correction at base `f22a8df000d2d8f55888532a412add9f0ab3baae`
tightens only the public retention0 assertion and documentation. It reuses the
retained Task3 native receipt, not a new native build, matrix or capture:
`task-3-inventory-20261018-evidence/live-public-artifacts/reports/b0448d32872c4744-retention/evidence.json`
under the local SDD directory named above. Its exact conditions are zero records,
**502 discarded == 502 summary received**, **zero pending**, terminal accounting
and physical drain true, selected complete true and the same four unproven lanes.
The native test now requires equality and zero pending alongside its existing
minimum-workload, terminal, physical-drain and pending-owner checks. Existing
accumulator final-pairing/retention and public compatibility/ABI evidence remains
applicable; assertion compilation is the focused executable check. This receipt
still does not supply an independent global retention denominator.

The [selected contract's corrected CPU boundary](2026-10-17-hotspot-selected-accounting-contract.md#possible-cpu-cardinality-clarification-ffr-m03)
uses the actual selected **Cilium eBPF v0.22.0** dependency: `cpu.go` delegates to
`cpu_other.go`'s `sync.OnceValues` sysfs read. Prepared and final rows use the
process-cached **boot-fixed possible-CPU cardinality**, including offline possible
CPUs; repeated `PossibleCPU()` is **not** an uncached sysfs/permission re-probe.
Map metadata/ID checks and counter lookup are still actual final operations, and
exact row cardinality remains required. The original literal freshness claim is
not evidence of an executed re-probe.

Retained Task2 `task-2-native-20261018-evidence/accounting-kernel-final.log:2–6`
records actual maps **23842/23843**, **12 possible CPUs**, replacement/missing-slot
refusal and the 512-attempt pressure/overflow control. Task3's public selected
receipt above independently records map **26743/12 slots**; retention0 records
**26839/12 slots**. These are reused executions, not a synthetic cardinality or
hotplug test. The official kernel CPU-map documentation retrieved as **7.3.0-rc7**
explains the static-after-boot possible mask; the recorded **7.2.10** host's source
and configuration were not inspected. No native source, dependency, map, pin or
runtime semantics are changed by this clarification.
