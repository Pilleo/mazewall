# Separately negotiated HotSpot observation profile — Task 3

## Admission and compatibility

`otel_hotspot25_observed_v1` is a separately negotiated schema3 observational
profile. After the six-case candidate gate, the operator explicitly approved
[scoped admission](2026-10-19-hotspot-observed-admission.md). The rebuilt CLI and
`capture.RunObserved`/`RunObservedControlled` now validate and run the same owned
implementation that was qualified, rather than returning the former admission
refusal. Invalid negotiation still exits2; failed capture configuration/runtime
exits1. CLI schema3 requires parent control and the independently supplied target
tuple. The tagged `RunObservedCandidate` is a compatibility alias, not a bypass.
SDK publication and end-to-end Kubescape acceptance remain separate gates.

Strict schema2 remains pinned Temurin25.0.4.1+1-LTS, `-Xint -XX:+UseSerialGC`,
stable safepoint epoch and full owned drain. Its Kotlin API/ABI, absent schema3
fields and decoder/admission rules stay schema2. Schema1 remains a separate
experimental branch. The current source patch is
`89b3599935df5206ff710292574e4480a09da535f8529b4b0462b8c29a7e0f50`;
this source identity does not qualify ordinary-JVM runtime behavior.
The initial Task3 source473 and rejected Task4 captures remain frozen in their
evidence. The subsequent [owned-drain repair](2026-10-19-hotspot-observed-drain-evidence.md)
does not change admission or the observation claim.

## Request, identity and lifetime

The pure CLI parser accepts `--capture --protocol-version=3
--runtime-profile=otel_hotspot25_observed_v1 --pid=<host-pid>
--expected-start-ticks=<raw-ticks> --expected-cgroup-id=<id>
--expected-mntns=<inode> --hotspot-identity=unknown --drain-full --control-stdin`.
All expected fields are nonzero and the PID fits the real exec barrier's signed
PID domain. Duration is positive and at most60s; enrichment budgets remain the
existing four workers/1024pending/16MiB/1s. No filesystem or kernel inspection is
performed by parsing. Unknown enums, incompatible fields and profile/schema
cross-pairings are errors, not fallbacks.

The candidate checks the expected PID/birth/cgroup/mount tuple before acquiring
target owners, then owns the existing real pidfd and exec-poison barrier before
profile discovery. It compares the mapped JVM inode/device, ELF ABI, full SHA256
and GNU build ID with the existing pinned Temurin25 fingerprint. Runtime flags
must show compiler enabled, tiered compilation enabled, G1 enabled and Serial
disabled. No safepoint counter is read or advertised by this profile. Expected
identity is rechecked through discovery, before ready and by the exec/pidfd
watcher; every captured event must match PID/birth/cgroup. Cgroup checks require a
visible unified hierarchy under `/sys/fs/cgroup`; inaccessible, ambiguous or
namespace-relative escaping paths fail closed and need an explicit A4 environment
receipt rather than guessed identity.

Owned full sessions construct the exec barrier independently of strict epoch
mode. Ready, lookup/recovery and AfterReadout keep that real barrier. Existing
native `EventFinalizationOptions` negotiates ObservedFull separately from StrictFull:
mixed modes are rejected, observed requires counter0 plus accounting/handoff, and
strict retains its nonzero-counter requirement. Both modes perform the definition
handoff after the real reader/worker joins and before native counter sampling.
Existing
physical event/definition drains, callback completion, enrichment workers and
auxiliary owners retain the remaining two-second stop budget. Final counters and
fatal snapshots follow the existing real joins; no extra buffer, object count,
timeout-as-retirement or plausible boolean proof is introduced.

## Reader, upstream guards and error routing

The process manager's optional HotSpot-specific attach capability wraps the actual
instance's original `RemoteMemory.ReaderAt` before runtime metadata reads. The
observer borrows that reader and owns no native descriptor. It checks nonnegative
addresses, signed-end overflow, at-most1MiB reads and short/invalid counts without
allocating a second buffer. The real transport is `ProcessVirtualMemory` /
`process_vm_readv`, not an invented closeable FD; a negative native count is
normalized without replacing its original nonnil errno cause. Synchronized bounded first-cause ownership reports
the original error immediately to the existing fatal callback, also retaining the
first EPERM/EACCES if an earlier nonpermission failure happened. Upstream `%v`
formatting cannot erase that independently routed native cause.

The actual OTel nmethod compile-ID cache comparison and fresh-read comparison,
interpreted pointer checks, raw frame/task guards and existing caches stay in
place. No symbolizer algorithm is copied. Cache checks do not establish current
metadata/code lifetime, GC exclusion, class-loader identity or a safepoint lease.
Missing/evicted metadata is an explicit unavailable terminal; native read denial
fails the session. Unknown-identity definitions now share immutable correlation
insertion: conflicting task/frames cannot replace an existing definition, and
the real reporter/consumer routes original errors to the fatal owner, including
legacy unknown-identity collection. Recovery never substitutes a later occurrence
for the original unavailable one. The isolated drop-definition fault patch uses
that same insertion point and remains absent from normal source.

## Wire and candidate SDK graph

Every schema3 row explicitly emits `policy_eligibility:false`. Ready binds the
actual target/JVM fingerprint and `validation_mode=upstream_generation_checks`.
Original event/terminal identity and summary finality/unknown-global flags are
preserved. Neither selected accounting nor successful retirement grants policy,
global collection, GC/code/metadata or virtual-thread ownership.

The common byte/OCI verifier negotiates strict collector2/wire2 separately from
observed collector3/wire3, both capture ABI2. `Verified.Expected(target)` binds the
verified profile and an owned target value. Native strict verify/install/release
continue to require strict2 and current provenance plus actual `unix.Access(X_OK)`;
packing format3 does not activate runtime admission. SDK schema3 positives and
packer fixtures are **SYNTHETIC**, not observed native captures.

Published `hotspot-bridge/v0.1.0` is immutable at
`1a6d58d2c73f5b089588add7364bf8d7913c5604`, Sum
`h1:tWCzIDVBquWo6tWwuqIT5oKlfRPP+XMshdJ8B7hFcIs=`, GoModSum
`h1:FEiVroH+pQOmKfe6YXTFkvkpAF3z799E4hFDPza6e/s=`. It rejects observed
bundles and hardcodes Strict25 in Expected. Native go.mod/go.sum pin those actual
released bytes; Task3's common verifier/Expected changes are an explicit local
candidate, not altered v0.1.0. A new candidate SDK release (proposed v0.2.0) needs
separate operator approval/publication before hermetic format3 release use.

Use one explicit workspace per canonical/experimental owner, with the local SDK
and actual prepared replacements. The owners have the same ModulePath and cannot
share one workspace. Do not combine GOWORK with `-modfile`. Task3 evidence records
the workspace files under `build/kubescape-dev/task-3/{profiler,experiments}/`.
`GOWORK=off -modfile build/experiment.go.mod` selects published v0.1.0, never the
candidate's new SDK bundle behavior.

## A4 handoff

Task3 host/source checks are separate from A-OBS. A4 must build/hash actual
candidate assets from its recorded graph and run the ordinary tiered/G1, code-cache
churn, class unloading, exec/exit and permission cases with the required route
denominators; then preserve the real qualified/rejected verdict. The initial Task4
rejection and subsequent owned-drain repair have separate evidence: three repaired
controlled cases initially completed with a rejected full verdict. The later
[coupled JVM exec qualification](2026-10-19-hotspot-coupled-exec-evidence.md) completes
the controlled candidate gate, preserving all earlier rejected reports. Candidate
qualification/bundle reproducibility is not SDK publication or runtime admission.
The unchanged inherited USER_NOTIF ENOSYS socket/directory shutdown investigation
remains open under issue20261003-131111-752.

Governing [spec A1/A2](../superpowers/specs/2026-10-18-kubescape-agentless-hotspot-design.md#a1-profiles-and-wire-versions)
and [execution plan](../superpowers/plans/2026-10-18-hotspot-observed-provider.md#task-3-add-the-separately-negotiated-observation-profile)
retain all later gates. Local detailed Task3 evidence lives in the owning SDD
`task-3-report.md` and `build/kubescape-dev/task-3/`.
