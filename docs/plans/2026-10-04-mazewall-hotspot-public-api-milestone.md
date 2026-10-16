# Public HotSpot strategy/result/session — milestone 5

The additive public surface now includes `ProfileStrategy.HOTSPOT_AGENTLESS`,
`HotSpotOptions`, `HotSpotProfilingSession`, `HotSpotProfilingException`, immutable
typed `StructuredProfileEvidence` and its event/frame/provider/summary projections.
`MazewallProfiler.attach(Pid, HotSpotOptions)` uses the real owned backend.
`open(ProfileOptions, HotSpotOptions)` configures current-JVM workload profiling;
an explicit `profile(HotSpotOptions) { ... }` overload is also available.

## Selection, admission and ownership

AUTO remains USER_NOTIF; recorded EBPF/Tier E/native ACK behavior is unchanged.
Only explicit HotSpot admits process attachment/options, requires processWide/
captureStacks and refuses recorded-log/io_uring overrides. It skips the parent's
eBPF authority probe: actual helper authority/runtime/generation/ready are admitted
by the collector handshake. Missing assets and unsupported/default JIT JVMs fail
before the workload block, with no USER_NOTIF fallback.

Sessions remain independent cleanup owners; the selector's close prevents new work
but does not claim returned sessions retired. A returned session must be finished/
closed. Public exceptions retain cause, partial immutable result, native errno,
bounded diagnostics and pending owner; explicit cleanup retry remains failed
collection, never successful attribution or policy completeness.

## Compatibility and evidence

Existing ProfileOptions constructor/copy descriptors are unchanged. ProfilingResult
uses an additional private immutable owner constructor plus the original public
five-argument/default-mask constructor and copy/default-copy descriptors. Copies
and value mapping retain the same immutable evidence owner; all pre-existing public
API snapshot symbols remain present (zero removed descriptors). New enum value is
appended after existing values. Both Kotlin/legacy ABI snapshot gates pass.

Public evidence preserves ordered uint/uint64 frame domains, optional BCI/source/
native address, raw provider JSON, raw syscall arguments and defensive filename
byte copies. Loss/retention/pending/physical-drain facts stay distinct. Legacy stack/
BoB projections do not fabricate paths or successful syscall outcomes.

Coverage's HotSpot strategy is intrinsically incomplete; copied structured results
force that strategy/integrity even if supplied another complete coverage record.
Worker/policy conversion therefore remains rejected. Existing ProfileDocument
format/behavior/coverage-only contract is unchanged; the enum name roundtrips there.
An independent bounded structured diagnostic report preserves optional values and
raw positions on roundtrip, rejects forged collection-loss completeness and does
not act as a kernel-resource/policy certificate.

## Verification evidence

- Intended red tests observed absent strategy/options/evidence APIs. Public strategy
  cases cover unchanged AUTO, independent helper authority, scope/options rejection,
  missing assets/block-not-run/no fallback. Workload cases use real OS child/pipes
  through an injected session factory to verify value mapping, ready-before-work,
  immutable copy ownership and workload plus cleanup cause preservation.
- Result cases reflectively verify the original public/default constructors and
  copy/default-copy JVM descriptors, compare provider rawPosition/optional BCI on
  report roundtrip, test immutable records/raw dirfd, and reject dedicated-worker/
  policy promotion through both normal and supplied-complete copies.
- Explicit guarded public native acceptance launches a fresh ordinary-user pinned
  interpreter/Serial target with the installed helper and cached-image operator
  Docker prefix. The public selector/session captures 400 marked pairs with genuine
  InlineOpenFixture frames, returns HOTSPOT_AGENTLESS structured results, finishes
  native drains and owned resources, and retains incomplete coverage. A privileged
  helper also refuses current host-JVM workload admission before the block runs.
- Full profiler tests, public facade/API-dump regressions, production Detekt and
  warning ratchet, additive ABI inventory/checks and `./gradlew build` pass.
  Collector/OCI pins, native layouts and Go/BPF engine are unchanged.

Operator guidance: [profiler/HOTSPOT_AGENTLESS.md](../../profiler/HOTSPOT_AGENTLESS.md).
Milestone 6 broadens real public fault/compatibility acceptance; milestone 7 finishes
supported distribution/operator release guidance. General JIT, full syscall/loss
coverage and logical virtual-thread identity remain future capability gates.
