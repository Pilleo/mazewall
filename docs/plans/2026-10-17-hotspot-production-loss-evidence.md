# Structured production loss-source inventory

> **2026-11-09 qualification:** Existing accepted evidence below is preserved for
> its named historical graph and cases. Earlier “current”, “fresh”, “complete graph”
> and qualification results do not certify the changed candidate or all-source
> Definitions. The dated Task3a feasibility disposition appended below governs
> source feasibility; it is not new execution evidence or acceptance of A1 leaves.

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

## Critical-path feasibility recheck (2026-11-11)

The original feasibility gate still requires **STOP** for all-source Definitions
promotion, native instrumentation and image rebuild. Ownership, immutable receipts
and rejection of unqualified data are prerequisites, not the missing production
witnesses or the required positive MODEL forwarding proof.

- **Report/PID:** the current BPF sites are instrumentable, but existing backoff,
  map-update and shared-reader metrics do not supply a per-site, session-bound
  attempt/outcome denominator. Mailbox coalescing/overwrite, ignored trigger
  success and closure of every shared writer still require explicit witnesses.
- **Kobject:** local admission begins after `Receive`. Owned socket shutdown and
  joined workers do not establish independent kernel-to-socket admission, loss or
  closure. No such mechanism is established by the inspected source. A received
  sequence number alone would not prove its upstream domain or final boundary.
- **Registry and cleanup:** fifteen local kind rows do not establish exhaustive
  enabled-producer authority. Unused-executable cleanup/retention accounting also
  remains unresolved. Adding a native success reason or a test issuer would not
  supply any of these facts.

Source audit: `.superpowers/sdd/2026-11-08-hotspot-production-accounting-proof-plan/reports/task-3a-critical-path-authority-audit.md`,
SHA256 `91a7221ab5d01cba291176a1a319038efadfa63c3a92b0d57d2533aeade619c2`.
It retains the inspected source anchors and distinguishes implementable report
hooks from the absent kobject proof mechanism. The next operator decision is
whether to authorize a separately bounded, source-only kobject-witness feasibility
investigation without narrowing Definitions. This grants no instrumentation,
rebuild, runtime probe or qualification authority.

Existing enrichment, wire-prefix and client-accounting code has not been
requalified by this audit. No compiler, tests or native campaign ran. Definitions
remains UNKNOWN and issue042 remains OPEN; all historical evidence below keeps
its original limits.

### Authorized kobject witness study (2026-11-12)

The bounded source-only study did **not establish feasibility** from available
inputs. The cached socket library parses SEQNUM, but the profiler ignores it and
the raw read path discards sender/flags metadata. Neither received sequences nor
local socket shutdown establish an independent selected-domain denominator,
loss partition or ordered epoch seal.

Report: `.superpowers/sdd/2026-11-08-hotspot-production-accounting-proof-plan/reports/task-3a-kobject-witness-feasibility.md`,
SHA256 `dec4b565f6951d7fee1f68dd9937f250ea9773d1455fce9258b17a2c44f9d3ea`.
Missing implementation source and the unchanged-reader EPERM are retained; no
alternative acquisition route or runtime probe was used. This is not proof that
a witness is impossible or that kernel source is absent everywhere on the host.

The next required input is an already-local, operator-designated **exact-target
kernel source/build/profile bundle**: `lib/kobject_uevent.c`,
`net/netlink/af_netlink.c` and referenced paths, matching release/commit,
configuration/patch identity, and the intended socket namespace/subscription/filter
profile. Until those semantics are established, all-source promotion,
instrumentation and image rebuild remain stopped. Definitions remains UNKNOWN;
no implementation or execution authority follows from the study.

### Official Ubuntu source follow-up (2026-11-12)

The operator authorized locating/downloading the required Ubuntu source. The
implementation is now retained from Canonical's Resolute repository at tag
`Ubuntu-7.0.0-43.43`, commit `f53b359683af7918c5436499948763cb726d5082`.
Static boot links/header/module names identify this as a candidate only; the
running kernel and capture profile are not attested, and Xanmod alternatives
remain installed. No packages were needed or installed. This supersedes the
earlier missing-source/path request, not the original all-source STOP gate.

The source establishes that global SEQNUM allocation precedes delivery and some
failure paths, spans namespace-routed events and privileged userspace
rebroadcasts, and is not a per-socket denominator. ENOBUFS reporting coalesces;
failures before socket delivery need not appear in socket overrun accounting.
Reading the sequence counter does not flush deliveries, and a later numbered
marker can overtake an earlier allocation. Preserving raw sender/flags remains
necessary but does not supply admission or closure by itself.

**Disposition: requires a new independently bound admission/closure witness;
original-contract feasibility remains unestablished.** A complete, authenticated
global-interval identity set is a conditional alternative to an ordered marker,
not ruled out by this study. It must independently bind the counter epoch and
reconcile the entire original owned-source lifetime, including allocations
crossing bind/read/close boundaries. Missing identities stay UNKNOWN; silently
discarding out-of-interval work or narrowing Definitions is not authorized.
No necessity or approval for a new kernel patch/BPF program is asserted.

The existing study report now contains the source anchors, counterexamples,
configuration-policy limits and full acquisition manifests; current SHA256 is
`6d867fde87e195210214fe4302677d0d9e7943843539cfaadd2972c0bf87e116`.
The earlier report hash above identifies the preserved pre-download study.
Commit-pinned files/URLs/hashes are retained at
`/tmp/opencode/master-merge-mr5fp6z4/inspection-h54u5iw3/commit-pinned-source-acquisition.json`;
static integrity/history checks at `inspection-ohcw6nm_` verify all seven files
and identical tag/commit bytes for both critical implementation files. Ubuntu
source annotations are not a measured build configuration. No instrumentation,
build, tests, native preparation/capture or runtime probes ran. Definitions stays
UNKNOWN, issue042 OPEN, and all-source promotion/image rebuild remain stopped.

### Continued study: membership cut and native-witness boundary (2026-11-12)

Further inspection found an existing **socket-domain closure primitive**:
NETLINK_DROP_MEMBERSHIP waits for reserved multicast fanouts, clears membership
under table exclusion and leaves the socket/receive queue open. At the pinned
Ubuntu commit, `af_netlink.c:413-455,1554-1561,1652-1708` support this ordering.
Actual successful return can therefore precede a finite final drain; it is not a
global sequence seal or closure of arbitrary unicast/unknown writers.

This removes the need to assume a later sequence marker orders earlier delivery.
It does not supply the missing admission denominator. A genuine native observer
must count eligible recipient attempts before clone/queue failure, their outcomes,
and the upstream failures that never reach recipient dispatch. The latter cannot
be silently excluded by moving the boundary. Socket diagnostic cookies can bind
an actually owned socket, but diagnostic memory/drop fields do not provide the
independent attempted/enqueued counters. These counters and origin/epoch proof
are absent from the current production implementation.

The study now specifies the complete required order and rejecting controls:
prepare an unjoined owned socket and witness; join once; on Stop obtain the actual
membership-cut return; drain and join consumers **before** existing admission
sealing; read stable independent counters; retire. Expiry during the kernel's
potentially uninterruptible cut must quarantine without closing an in-use handle
or accepting a late result. Current kobject Client hides its raw connection and
current OwnedScope seals before closers, so this is not a one-line closer fix.

Static unchanged-reader attempts for the installed Ubuntu config/System.map were
denied (EPERM/EACCES), retained without an alternate read or artifact-download
route. Source names do not prove lossless probes attach to optimized kernel
binaries. Exact-target static build/profile/observer qualification and a separately
scoped native-witness proposal are the next material boundary; no kernel patch,
new BPF graph or native execution is authorized by this source-only study.

Current study SHA256:
`6c28728499ffa0df2ec875b3975e77a0f2ac50e261f03db90610e8afea94628a`.
Prior study versions remain intact. Additional source manifests and full integrity
checks are retained at `/tmp/opencode/master-merge-mr5fp6z4/inspection-1mglf25n`;
static-build denial records at `inspection-8sjqgj01`. No packages, code changes,
compiler/parser/tests/builds or runtime probes ran. Full-source admission remains
unestablished; Definitions UNKNOWN/issue042 OPEN and all-source STOP are unchanged.

### Approved static artifact inspection and concrete witness candidate (2026-11-12)

The operator explicitly expanded the study to official matching build/debug
artifacts and **file-only readelf/bpftool** analysis, without installation,
compilation, instrumentation, live probes or retries of denied local files.
Canonical's librarian returned 503, but the official HTTPS Ubuntu archive
provided matching image/modules/buildinfo/BPF-header packages. All four hashes
match the retained exact-version Packages index; its hash matches retained
InRelease metadata. The OpenPGP signature was **not** verified, and none of this
attests the running kernel or qualifies a native certificate.

Static decompression of the small published image provided the ELF/BTF without
downloading the 2.09 GB debug package. Candidate GNU build ID:
`842f66651381e59dc5beb4cb7ad95ce0b869e3bb`. Source, BTF, System.map and recorded
ftrace entries establish a decisive limit: recipient sites `do_one_broadcast`,
`__netlink_sendskb`, `netlink_overrun` have ftrace entries; the four upstream
kobject source sites do **not**. `lib/Makefile` deliberately removes function
tracing there. A pure-fentry proposal is therefore rejected, not qualified merely
because BTF names exist. The failed all-entry assertion remains retained.

Classic entry/return probes are a conditional alternative for those four source
sites: the candidate immutable blacklist/protected ranges do not exclude them.
That is not actual registration or lossless coverage, and kprobe-multi's fprobe
implementation is not an automatic workaround. No runtime fallback was attempted.

The refined stock-kernel design candidate now has concrete components: independent
upstream/recipient counters, the real membership cut, a single-use atomic source
admission gate, and a supported **MEMBARRIER_CMD_GLOBAL** RCU grace period before
loss readout. The pinned source supplies the grace and mmapable-map primitives;
the actual runtime supports/RCU flavor, probe misses, counter widths/non-wrap,
nesting/migration, origin/crossing work, ownership and full producer registry still
need implementation and qualification. GLOBAL_EXPEDITED is not the same grace
period. The config enables NO_HZ_FULL, so GLOBAL availability cannot be assumed.
No membarrier/map/probe operation ran. New native graph/source work requires a
separately scoped approval; no kernel patch or source exclusion is implied.

The existing study contains the exact seven hook roles, closed-epoch protocol,
native loss/quiescence prerequisites and full artifact manifests. Current SHA256:
`4e5e3065e070cc4d3c9e87836fdeed06f96c04f00e5dfa68554f4f845be4fc7f`.
Static validation at `/tmp/opencode/master-merge-mr5fp6z4/inspection-a6jnztvk`
preserves all prior report bytes, checks package/index agreement and verifies 18
additional whole kernel source inputs. Only the approved static parser commands
executed; no Go/compiler/tests/build/preparation/capture or live kernel probes.
Other original producer/cleanup/registry and positive all-source MODEL/native
obligations remain open. Definitions UNKNOWN/issue042 OPEN/all-source STOP remain.

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

## Task3a per-source feasibility disposition (2026-11-09)

This installs the original plan's first Task3a item, not a replacement plan.
Authority is `2026-11-08-hotspot-production-accounting-proof-plan.md`, SHA256
`c1ba748ffe9de3083f39cd49ca75ba6d7749848b0ede02bc7ad31b8686bd2958`,
lines 13–24 and 169–177. The proposed
`.superpowers/sdd/2026-11-08-hotspot-production-accounting-proof-plan/reports/task-3a-l5-feasibility-rows.md`
is a lead, not admission authority. The strategic-reset review (SHA256
`5e30b302ef3ae4180f15970ca983a235e7dee964ac6689434ee19969c8c41d10`)
correctly identifies upstream feasibility as the postponed gate; its proposal
for further recovery approval is not a new serial gate on unrelated host work.

### Profile, identities and classification rules

The source-local profile is the full owned `openat`/`capture_openat` path:
`profiler/hotspot-agentless/internal/capture/collector.go:244–271` selects
Observed25 versus strict options, installs `AfterReadout` only for `DrainFull`,
and binds session, target TGID and birth. This does **not** attest a running
OCI image, attachment set or map generation. Each concrete run must separately
identify strict versus observed profile, preparation/patch and embedded-image
digests, actual attachments/program replacements, original map IDs and CPU
cardinality, session/TGID/birth, and each owner/scope generation. Expected
bindings must come from independent preparation, not a returned receipt.

For the finite source inventory below, `H=profiler/hotspot-agentless` in this
checkout. `P=/tmp/opencode/task3a-l4-profiler-fix1-terra-v4m8/final/source` and
`G=/tmp/opencode/task3a-l4-gadget-format-cert-terra-v4agrffp/gadget-final`
are the retained accepted L4 profiler and gadget source roots named by the L5
handoff. Their source facts are not a newly built/running candidate. File:line
references below use these roots. No unverified A1 leaf is accepted or substituted.
The current candidate retains C0–C2 missing-only behavior
(`H/internal/capture/definition_local.go:43–48,57–96` and
`inventory.go:71–90`); Definitions is not admitted to `MissingEvidence`.

**Measurable** means a defensible source-local admission/terminal boundary exists,
not that current execution has proved it. **Active-unmeasured** includes a
potentially enabled producer whose exact-profile inactivity is not established.
**Authoritatively-disabled-for-exact-profile** requires independent preparation
and actual attachment/runtime evidence for that profile; no row earns it here.
An empty observation, loaded-but-unattached source path, disabled stack-frame
use, missing getter, or successful Stop is neither that authority nor zero loss.

In every row closure means: all upstream producers stopped, reader drained and
joined where applicable, actual downstream consumers joined, snapshot while
original owners/maps remain live, then strict resource retirement before final
proof publication. A held resource or timeout is not final success. The specific
closure gaps below are additional to this common requirement.

### Enabled-source and consumer matrix

| Source / actual owner and exact-profile identity | Independent admission denominator → terminals/loss | Producer / reader / consumer closure and rejecting oracle | Feasibility and exact missing witness |
| --- | --- | --- | --- |
| **trace_events**, common native perf output; `P/support/ebpf/tracemgmt.h:375–392`, `P/tracer/definition_accounting.go:48–134,169–206`. Original counter/perf map IDs, possible CPUs, target lifetime and generation, plus actual selected program/replacements. | Native per-CPU Attempted = Submitted + OutputFailed + RejectedSize rows (exact checked conservation; native `rejected_size` terminal and Go `RejectedSize` loss distinct from output-failure) → reader returned, handoff/abort and downstream partitions. Selected openat counters are not this denominator. | Close every enabled caller of the common output; trace Flush/read join and consumer join, then live-map sampling. Reject unknown caller closure, missing CPU, identity mismatch, overflow, size rejection/mismatch (nonzero RejectedSize/rejected_size), output/read loss, undelivered or store loss. | **Measurable** at the instrumented output boundary. Missing current independently bound native rows and all-producer/drain/consumer/retirement witness; does not cover auxiliary sources. |
| **GenericPID report: unknown PID**, native entry `P/support/ebpf/tracemgmt.h:778–783`; shared `report_events` reader `P/tracer/events.go:128–178,411`. Per-site program/hook plus original report/inhibit/mailbox map identities. | Actual calls before PID backoff → suppressed, mailbox update failed/overwritten/accepted, report inhibited/submitted/output-failed → received records. Those site-specific upstream totals are absent. | Close entry producer and all other writers to shared maps; cumulative drain/join then PID consumers. Reject receive count as upstream admission, residual/lost/error, reset metrics or unclosed writers. | **Active-unmeasured**. Need per-site native admission/outcome partition, independent identities, complete report drain and consumer closure; `.Swap(0)` diagnostics and reader Close are not that witness. |
| **GenericPID report: wrong text section/unwind stop**, `P/support/ebpf/interpreter_dispatcher.ebpf.c:319–327`; same maps/reader but distinct emitting site. | Actual unknown-PC report calls → backoff/mailbox/inhibition/output outcomes → shared report receives. Shared receives cannot partition this producer. | Stop its actual interpreter-dispatch callers, then shared report/PID barriers. Reject aggregate reader balance as this site's denominator. | **Active-unmeasured**. Missing per-site attempted/outcome denominator and independently demonstrated caller closure for selected replacements. |
| **GenericPID report: sched_process_free**, `P/support/ebpf/sched_monitor.ebpf.c:27–50`; tracer hook owner `P/tracer/tracer.go:60–64,349–353,411`. Selected v1/v2 hook/program and original shared maps must be identified. | Actual tracked-PID reset/report attempts → suppression/update/inhibition/output outcomes → shared reader. | Retire the real sched hook as well as capture hook; shared reader drain and PID processor join. Reject capture-only detach, still-live sched writer or unpartitioned shared receives. | **Active-unmeasured**. Missing selected attached-variant/runtime identity, site-specific denominator and all-hook closure. |
| **ReloadKallsyms report / private kprobe__kallsyms**, `P/support/ebpf/kallsyms.ebpf.c:5–9`, loaded by `P/tracer/tracer.go:738–747`; reader dispatch `P/tracer/events.go:101–116`. | Native trigger attempts → inhibited/submitted/output failure → received R record (kind16) → separately queued/coalesced K request and execution. Record completion is not execution. | Establish whether this loaded private program is attached in the actual profile; if active, close it and shared report writers, drain reader and join actual K worker. Reject loaded-only evidence as runtime-disabled authority, or R/K unit receipts as integration. | **Active-unmeasured** for unverified exact runtime; inspected load path alone does not establish attachment. Need authoritative exact-profile inactivity, **or** native admission/outcomes and closure; additionally real R→K queue/coalescing integration witness (R F1). |
| **reported_pids backoff / pid_events mailbox**, `P/support/ebpf/tracemgmt.h:115–198`, `P/tracer/pid_mailbox.go:64–86,435–495`. Original PID32 backoff and PIDTID64 mailbox map IDs, session/TGID/birth/generation. | Upstream per-site attempts before suppression and `BPF_ANY` overwrite → local enumerated-key admissions → queued/deletion-failed/aborted/held residual. Enumerated keys cannot count overwritten or suppressed attempts. | Stop all kernel writers, final complete mailbox poll, queue/processor join while maps live. Reject ScanIncomplete, UnpolledResidualUnknown, overflow, wrong identity or unpartitioned keys. | **Active-unmeasured** upstream; local-key accounting is measurable. Missing suppression/overwrite/admission partition and final writer-closure/poll witness. No loss-free claim from map length or final empty map. |
| **PID processing / mapping synchronization**, original tracer pidEvents channel and PM, `P/tracer/pid_process.go:27–46,66–95`; `P/processmanager/processinfo.go:856,901–928`. | Actual received PIDTID operations → existing no-mappings/error/context/synchronization classifications, not automatically retained target definitions. | Mailbox/queue closure, actual processor end/seal before PM retirement. Reject queued≠received, unread work, context/mapping failures or treating ordinary synchronization as guaranteed mapping retention. | **Measurable** local processing; upstream remains active-unmeasured. Missing current joined receipt and target-mapping retention witness. Ordinary mapping-read errors can call `processPIDExit` (`processinfo.go:911–917`); local success cannot erase that gap. |
| **kernel→netlink kobject socket**, kallsyms `StartMonitor` and poller `P/kallsyms/kallsyms.go:645–689`, started by `P/tracer/tracer.go:1166–1182`. Actual socket instance, subscription, owned-scope generation and selected image/profile. | Kernel events/sends admitted to the promised boundary → socket delivery/loss/overflow/truncation/read failure → local receive-kind9. `Receive()` counts only delivered messages, not upstream sends. | Upstream admission closure or defensible bounded boundary; poll/reload workers join before socket finalizer. Reject unknown upstream sequence/admission, ENOBUFS, truncation, reader failure or cancellation as success. | **Active-unmeasured**, **included in Definitions**. Missing defensible independent kernel-to-socket denominator/loss and closure witness. Frame skipping does not disable this source. No exclusion or narrower Definitions contract is adopted. |
| **K module reload queue/coalescing**, `P/kallsyms/kallsyms.go:563–642,654–665`, same real scope/worker and channel. | Actual nonblocking enqueue/coalesce (kinds10/11) → separately classified module execution kind14. | Actual reload worker exit, freeze pending/terminal ledgers before owner retirement. Reject enqueue=execution, coalesced=executed, pending, failed/aborted/held or missing owner. | **Measurable** local boundary; missing current frozen real-worker receipt, upstream kobject admission and integrated dispatch witness. |
| **K symbol reload queue/coalescing**, `P/kallsyms/kallsyms.go:563–642,695–704`; original symbol queue/scope. | Actual enqueue/coalesce kinds12/13 → separate symbol execution kind15; R record-kind16 is another stage. | Same worker/freeze/retirement barrier. Reject stage substitution, pending work or independent R/K tests as integration. | **Measurable** locally; missing current worker receipt and R→real-K integration; report upstream denominator remains absent. |
| **Periodic map metrics**, `P/tracer/tracer.go:1212–1235`; actual callback and OwnedScope generation, kind2. | Each actual owned callback admitted → completed/failed/aborted/skipped/incomplete. This is not native report admission. | Stop timer/admission, join worker, freeze kind book before map finalizers (`P/periodiccaller/owned_scope.go:667–711`). Reject open admission, held/incomplete/overflow, reset diagnostics as cumulative truth. | **Measurable** locally; missing current bound frozen callback receipt, with actual enabled interval/configuration. |
| **Periodic PID cleanup and interpreter metrics**, same tracer/PM owned scope, separate kinds3/4 and actual callback owner; `P/tracer/events.go:41–87`, `P/processmanager/manager.go:141–200`, `P/periodiccaller/owned_scope.go:622–711`. | Each actual callback invocation, separately by kind → its classified terminal; not number of entries left or successful map mutations. | Stop callback producers and join before PM/maps retire, freeze each kind. Reject missing kind, missing binding, failed/partial callback or zero substituted for unobserved callback. | **Measurable** local ownership design; missing exact enabled callback/interval manifest and current terminal/freeze/retention witnesses. PID cleanup may affect mappings; no retention guarantee follows from worker join. |
| **Periodic unused-executable cleanup**, PM's actual executable-info manager/timer, `P/processmanager/manager.go:83–93`; owned timer dispatch `P/periodiccaller/periodiccaller.go:15–35`, `owned_scope.go:453–463`. | Actual `CleanupUnused` invocations and their cleanup effects → completed/failed/partial and retained definition state. The call currently logs its error; generic worker ownership is not a per-invocation source terminal receipt. | Stop timer admission, join callback/worker before executable/map owner retirement; retain target mapping/definition state through required consumers. Reject logged error or worker join as successful cleanup/retention proof. | **Active-unmeasured** at the per-cleanup/retention boundary. Missing exact timer/EIM binding, cumulative invocation/outcome receipt and retention witness. This actual owner supplements the proposed rows rather than inheriting their completeness. |
| **Prune cycle**, `G/pkg/symbolizer/symbolizer.go:154–162`, `prune_shutdown.go:11`; actual symbolizer KindBook generation, kind5. | Each actual prune cycle admitted → classified terminal, not object count. | Stop pruning and join real pruneDone; freeze before resolver retirement. Reject no-start/unfrozen/partial cycle or absent binding. | **Measurable**; missing actual configured resolver set and current joined/frozen receipt. |
| **OTel prune scan**, `G/pkg/symbolizer/otel/otel.go:140–141`, kind6, actual resolver/book. | Actual scan invocation → classified terminal including partial/failure. | Cycle stop/join plus resolver consumer closure; reject partial scan or unobserved-as-zero. | **Measurable**; missing exact-profile resolver enablement and current complete scan/retention receipt. |
| **Symtab prune scan**, `G/pkg/symbolizer/symtab/symtab.go:92–93`, kind7, actual resolver/book. | Actual scan invocation → classified terminal. | Same real pruneDone/resolver barrier; reject missing binding, partial scan, held/overflow. | **Measurable**; missing exact-profile enabled-resolver and current frozen terminal/retention witness. |
| **Debuginfod prune scan**, `G/pkg/symbolizer/debuginfod/debuginfod.go:81–82`, kind8, actual resolver/book. | Actual scan invocation → classified terminal. | Same stop/join/freeze/retirement requirement; reject missing/partial scan, timeout, held/overflow. | **Measurable**; missing exact-profile enablement and current frozen terminal/retention witness; not deemed disabled because not observed. |
| **Async executable/map update queue**, actual `ebpfMapsImpl.updateWorkers`, `P/processmanager/ebpf/queue_proof.go:108–130,307–443`; original worker pool/maps/scope. | RequestsOwned → QueueAccepted/Refused/AdmissionHeld; accepted → Applied/AppliedPublished/AppliedNotPublished/Failed/Aborted, with pending retained. These stages are not additive throughput. | Close request admission, join actual workers, freeze before maps finalize. Reject caller bool as admission, unknown pending, publication mismatch, cleanup failure, overflow or mixed owner. | **Measurable** locally; missing current independent binding and frozen real-worker/publication receipt. Does not prove upstream PID/report delivery. |
| **Trace channel**, actual OTel resolver channelBook, `G/pkg/symbolizer/otel/structured.go:118–187`. | Actual nonnil dispatch, separately from nil barriers → processing/close/cancel exits. Reader HandedOff is not consumer admission. | Real consumer join then snapshot before accounting retirement. Reject retired/overflow/missing consumer, undelivered or barrier-as-definition substitutions. | **Measurable**; missing current joined live-owner receipt and independent channel/PM/store linkage. |
| **PM trace processing**, actual PM and independently prepared target/native binding, `P/processmanager/hotspot_epoch.go:31–35,148–end`. | Admitted → PreReporterRejected/ReporterOK/ReporterError/InFlight, separately target/foreign/unknown. ReporterOK does not prove stored definitions. | Trace consumer join, snapshot before PM.Close. Reject InFlight, overflow, errors, unknown target partition or copied expected identity presented as independently actual. | **Measurable**; missing current final receipt and independent binding/store linkage. |
| **Definition store**, actual resolver storeBook and scalar target identities, `G/pkg/symbolizer/otel/structured.go:23–51,83–115,198–end`. | Actual store inputs → seven distinct terminals (invalid identity, identity validation, invalid checked, mismatch, new distinct, preserve checked, dedup) plus InFlight. Distinct-entry count is not admission. | Consumer join, snapshot before retireAccounting. Reject invalid transitions, overflow, identity mismatch, pending, preserve-checked relabelled dedup or upstream omission. | **Measurable** locally; missing current joined receipt, complete upstream-source linkage and mapping/definition retention witness. |

The periodic rows describe distinct kind boundaries; shared roots/maps/readers do
not merge their denominators. In particular `event_send_trigger` inhibits before
output (`P/support/ebpf/tracemgmt.h:58–84`), `report_pid` can suppress or overwrite
before its notification (`:174–198`), the report monitor closes without the trace
reader's drain protocol and resets diagnostics (`P/tracer/events.go:128–178`),
and `StartMapMonitors` starts the kobject monitor (`P/tracer/tracer.go:1166–1182`).
These are source facts, not assumptions that auxiliaries disappear for this profile.

### Bounded disposition, not production completion

Native source instrumentation, image rebuild and all-source Definitions promotion
remain **blocked** by active-unmeasured report/PID and kobject admission/closure,
and unused-executable cleanup/retention accounting.
Need exact-profile preparation/runtime identities, per-site report/PID upstream
attempt/suppression/overwrite/output partitions, complete writer/drain/consumer
barriers, and a defensible kernel-to-socket loss boundary. A genuine disabled
source needs exact-profile authoritative evidence, not a missing receipt. Material
new native authority or contract weakening requires the operator's decision; no
such authority, exclusion, rebuild or run is inferred here.

Normal scoped source/host tasks, including original Tasks3b/c/d, may continue
without a separate recovery-document approval when they preserve the existing
strict condition and C0–C2 missing-only Definitions adapter: no new live
Definitions getter/readout, callback/lifetime changes, or A1 dependency. Actual
conflict with that condition must stop the conflicting slice, not be disguised.
The current adapter's enrichment finish and auxiliary stop are existing behavior
(`H/internal/capture/definition_local.go:73–94`), not a positive local Definitions
readout. Existing lease ownership preserves resources until an entered action
returns; it is not hard cancellation or success on expiry. Adjudication of
post-Stop-return continuation remains unresolved for a **future positive
Definitions adapter**, not a blocker to this untouched host slice.

Preserve no post-retirement access, no new native queries after expiry, no success
on timeout, Validate outside borrow and locks, and no extra `ctx.Err` or user error
callbacks on immediate-return paths. No source result here relaxes those rules.
Definitions UNKNOWN/global false remains an interim refusal, not issue042
completion. The original independent reconciliation, all five admitted lanes,
actual EOF, strict retirement and coherent current production campaign stay binding.


### Retained A1 source rework and concrete defect resolution (2026-11-10)

Under original issue042 Task3a authorization, the 11 selected A1 additions and connected tracer/test files
were retained and reworked in FIX1 (R1-R7) at source level without native preparation, compilation,
image rebuild, or test execution:

1. **R1 / F1 / F2 (Conservative entry authority without unpinned external queries):**
   - In `owned_scope.go.txt`, public `AuxiliaryBinding()` deterministically rejects stopped/sealed/expired/retired
     scope under `s.mu` with zero external calls, and live scope returns only already-retained scalar facts.
   - Initial unpinned `BindLifetime()` is deterministically rejected (`auxiliary enrollment lacks retained validated identity`)
     with zero external calls; re-binding the same lifetime reuses retained facts.
   - In `freezeSnapshot()`, fresh Validate/Session/HostTGID/Birth calls are completely eliminated; zero actual binding
     and explicit unavailable error are recorded into observation and kinds book without external queries.
   - In `pid_mailbox.go.txt:seal`, fresh Validate, infoFn, and getter calls are eliminated; missing fresh readout
     is marked explicit UNKNOWN/failed with zero returned flags. `preparePIDMailbox` avoids unpinned info calls.
2. **R2 (Normal PID owned seal):**
   - In `pid_process.go.txt:seal`, public `AuxiliaryBinding()` and validation hook calls are removed; scalar shape
     is validated locally via `libpf.ValidateAuxiliaryBinding(p.expectedBinding, p.expectedBinding)`.
   - `preparePIDProcess` creates a local accounting object even when public identity is unavailable.
3. **R3 / R4 (Sticky cached parent cancellation):**
   - Standard-library cancellation probe (`parentSignal`) distinguishes external parent cancellation from internal worker shutdown.
   - Existing watcher and stop goroutine sample probe outside `s.mu` and cache as `parentErr`. `ParentErr()` consults
     only cached state under lock with zero external context method calls.
4. **R7 (Private tracer projection assembly):**
   - Private `auxiliaryLocalSnapshot` and `auxiliaryLocalCache` assemble detached scope, kinds (15 slots), mailbox,
     and process caches at the `StopAuxiliary` boundary. No exported getter, cross-package transfer, or live queries.
5. **R5 / R6 (Written test regression repairs):**
   - Fixed hanging Validate barrier in scope tests; assertions updated for conservative initial bind rejection and model fixtures.
   - Fixed PID process test worker enrollment (`scope.Go`), interval stubbing (`stubPIDIntervals`), and override handshake.
   - Fixed definition capture stopper fixtures, actual receipt fields (`cause: errReadoutPending, raw: ...`), and phase enum (`localCompleted`).
   - Fixed repository root calculation in `prepare_test.go`.

**Remaining all-source blockers and unmeasured obligations:**
- Report unknown-PID, unknown-PC/unwind-stop, and sched-free emission sites remain separate upstream
  denominators with rate-limiting suppression and `BPF_ANY` overwrite in `pid_events`.
- Kobject netlink socket remains active-unmeasured without an independent kernel-to-socket loss denominator.
- Ordinary PID mapping classifications do not establish retained mapping/store delivery.
- Unused-executable cleanup (`em.CleanupUnused`) lacks a dedicated per-invocation accounting row in the 15-kind vocabulary.
- Definitions lane remains **UNPROVEN / UNKNOWN**. Initial proof enrollment, fresh auxiliary readout, and public
  cross-package frozen transfer remain unavailable; no positive Definitions adapter or covering lease is installed.
