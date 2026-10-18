# Ordinary HotSpot observational qualification — rejected

## Decision

**A-OBS is rejected. Production admission remains closed.** The first real
non-root ordinary-JVM capture met the route-only diagnostic threshold, but native
definition drain rejected the observed profile. No completed SDK capture, final
selected accounting or native retirement proof was produced. Successful route
prefixes do not override that failure.

Owning execution report:
`.superpowers/sdd/2026-10-18-hotspot-observed-provider/task-4-report.md`.
Raw receipts remain in `build/kubescape-dev/task-4/`; old assets and every initial
failure are retained. This is local candidate evidence, not a published release.

## Executed source and artifacts

Base: `6334972afd55c410f1a3f193301fdf7b21c27f8d`, existing
`feat/kubescape-agentless-hotspot` checkout. Native source remains
`473acb5ab31168cdba2ad390c105ca83a163e91d9443e53ce45cfecd6f30c58b`.
No native patch, ABI, current production pin, public Kotlin API or admission
source was changed. The matrix and native entries compile only with
`mw_hotspot_qualification`; route DTOs/verifiers and pure tests compile normally.

| Artifact | SHA256 / identity |
|---|---|
| Actual static capturing test binary | `9c49b90e12a8d0f4aec4805f02b755218beefc97b14c2f33ff07417e623bb0b5` |
| Candidate packed collector, both builds | `4481a9e4a110fb412579dc8a459ffbc4e1dc0ed23984594f9042de9f4391ccaf` |
| Observed manifest, both builds | `fb41e47537840ba24a2550b603e0d3f476e417afbb0bcfee87efd47f4d538471` |
| Strict manifest, both candidate-workspace builds | `5220d3ee304b146e979803699984bc225b75c8bd73051d514d367a60bbb0f59b` |
| Gadget OCI digest, both builds | `sha256:4ec354d5ec6c38f60d89192fb0f90a3a25bb7974c240854199ed6934d41182b4` |
| Actual rejected native report | `75b4cede8bedad099ed1dfb4c9ddc59d0a01fa32febceb48f0908cd7e9f68486` |

The test compiler was host **Go1.26.8**, CGO disabled, not the compiler of its
execution image. Packed helpers used the locked image's actual **Go1.26.0**.
All commands used `-buildvcs=false`; compiler/module/source/image receipts precede
compilation and executable hashes precede invocation. Explicit one-owner local
workspaces select candidate SDK format3. Those workspaces use Go1.26.0 directives
compatible with the locked compiler; no runtime toolchain download was allowed.

Two fresh complete candidate-workspace builds have identical bytes, names, modes,
sizes and all reachable OCI blobs (10 files per bundle), checked against actual
hash output and both manifests. These are **candidate GoWork reproducibility**
results. Separately, actual `GOWORK=off -modfile build/experiment.go.mod` strict
CLI builds select immutable published SDK v0.1.0 and its original checksums; they
are not evidence of published format3 support. There was no SDK retag or release.

## First cases and independent routes

The exact ordinary target command was:

```text
java -cp /fixture/classes ObservedWorkload /fixture/gates 400 4 20
```

The pinned target image was
`eclipse-temurin@sha256:119a3d18f160a3e7655a66034d0f43beee31cd7b3b9142d57a5de29772011de6`.
Its `javac 25.0.4.1` compiled the actual maintained source. All workload cases used
four platform workers, two routes and 40 total warmups before readiness. Each
worker requested 20ms pacing; the aggregate measured marker span was about two
seconds. No agent, forced interpreter or forced GC option was supplied. Actual
VM flags confirm compiler/tiered/G1 enabled and SerialGC disabled. The ordinary
runtime input-arguments list was empty; proc command lines, maps and flags remain
available independently.

| Actual case | Marked raw / terminal | Useful prefix | Wrong route | Actual outcome |
|---|---:|---:|---:|---|
| Ordinary tiered/G1 | 400 / 400 | 372 (93%) | 0 | Rejected native drain |
| Compilation/code-cache churn | 400 / 400 | 376 (94%) | 0 | Rejected native drain |
| Class loading/unloading | 400 / 400 | 384 (96%) | 0 | Rejected native drain; 7,577 unload log entries observed |
| Target exit | 400 / 400 | 300 (75%) | 0 | Target loss and pending drain rejected; wrapper exit137 retained |
| Real permission denial | No marked work released | Unavailable | Not a success claim | Native read EPERM; candidate permission error retained |
| Same-PID exec control | Not a JVM capture | Unavailable | Not a success claim | Actual exec barrier poisons; full JVM/readout exec coupling unqualified |

The raw ordinary stream also contains one nonmarked `done` open: total event and
terminal counts are 401/401, distinct from the marked 400/400 denominator. The
real SDK accepted actual schema3 readiness and prefix rows, then rejected Finish
with the original collector error. Independent replay checks every marked token
against its preexisting source oracle and exact worker TID, plus ordered
`ObservedWorkload.leaf` / `routeA` or `routeB` frames. Real captured stack swaps
between workers, duplicate tokens and missing tokens all fail the verifier.
No nearest-TID, time/CPU-sampling join or inferred logical thread identity is used.

A4-001 correction: semantic wrong/reordered application-prefix errors now retain
token context instead of becoming unavailable. A pure 400-pair control detects a
single reversed/duplicated prefix while preserving genuine unavailable outcomes.
Corrected-verifier replay remains 372/376/384/300; the original capturing binary,
captures and rejected verdict are frozen, not rebuilt or re-executed.

Useful means the independent application route was available. It does not upgrade
native or JVM frame precision. Ordinary marked frames include 60 out-of-range
BCIs, 684 unavailable BCIs, 312 method-only and 372 stub lookups; 6,768 frames
have no source line (counts include native/kernel frames). All raw positions and
missing/quality fields remain unchanged. See `native-diagnostics/*-prefix.json`.

## Actual identity, namespace and ownership boundary

The controller explicitly used rootful Docker29.9.1, `--privileged --pid=host
--cgroupns=host`, host `/sys` read-only and the rootful API socket. Targets used
host PID/cgroup namespaces and a distinct container mount namespace, with UID/GID
65534. Receipts compare actual PID/cgroup namespace symlinks with `/proc/1`;
host PID does not stand in for host cgroup namespace. The ordinary target's path
was `/system.slice/docker-982bb78bdd8f1f4c56fa22821a244f7a77958b4e82740032226969bffe905b0f.scope`,
visible under the controller's host cgroup mount with inode999550.

Ordinary target PID3201703/birth60558710/mountNS4026532964 was associated with
that exact container ID through Docker's actual State.Pid, image digest, mounts
and proc receipts, not container names. Source TIDs were
3201760/3201761/3201762/3201763, checked under the target's task directory before
collection. Other cases retain the same complete independent association data.
Target PID/birth/cgroup/mount fields were nonzero; legitimate zero CPU/correlation
values were not treated as absent JSON fields.

Actual event Join reached the definition handoff and failed at
`DrainDefinitions`' strict `HotSpotEpochTarget.PID` precondition. Observed mode
intentionally has no epoch target. No guard was weakened or fake target/lease
created. Definition quiescence, reader/consumer join, selected counter finality
and complete native resource retirement therefore remain unavailable.
Selected-event scope is not whole-session collection; definitions, enrichment,
wire and retention remain the four unproven collection lanes. No summary was
invented to replace the missing final observations.

Every target/compiler container has the existing owned cleanup receipt, with
actual IDs and wrapper joins. Outer controller absence is checked after exit.
Container retirement does **not** establish native collector retirement. On the
pending-error path the harness releases its target after Run returns although
native resources remain retained; this lifetime-coupling gap is part of the
rejection, not successful finalization. It is tracked with the drain blocker in
[issue-20261018-212457-115](../internals/backlog/issue-20261018-212457-115-observed-hotspot-capture-rejects-native-definition.md).

The controller's writable owned-output bind also covered its candidate bundle
subtree. Post-run complete-byte checks still match the pre-run manifest, and target
mounts exposed only fixture/gates, but immutable read-only asset-mount custody was
not proved for this rejected run. A future qualification must mount assets
separately read-only throughout verification/capture.

Two earlier setup failures remain separate: the initial read-only-parent mount
lacked `/fixture/gates`; the next source revision launched UID0 targets, correctly
rejected by preflight. Each harness defect received a discriminating RED/GREEN
regression before its minimal fix and a fresh, separately named output directory.
No first real non-root native case was retried, no pacing/budget tuning occurred,
and the native rejection was not retried until green.

## Admission and remaining gates

Fresh actual canonical default/released-SDK and qualification-tagged/local-SDK
CLIs both reject observed requests with exit2, empty stdout and the A-OBS/operator
review message before acquisition. Their respective hashes are
`55fe2e41527254f6dbbd6c19a85eee2c286ae6a71ecfa6cfeb8114ec2157b3a2` and
`f01d0fd99457ecb24d441a1e2ef5ffd98d0d573a6064e1f5692a3cf53f6d14e9`.
Experimental default/tagged CLIs also reject. Actual full-byte bundle controls
accept matching strict/observed formats and reject wrong profiles/manifest pins;
native strict tooling accepts strict and rejects observed.

The next operator decision is review of this **rejected** report and the scoped
drain/lifetime follow-up, not admission or SDK publication. A4 admitted-CLI
handoff and A5 stay blocked. Future format3 release requires a separately approved
new SDK version, then the actual qualified gate, operator admission review,
rebuilt admitted CLI and public acceptance. General code/GC/metadata lifetime,
virtual-thread/TID ownership, global collection and policy completeness remain
unqualified. The existing cold USER_NOTIF/ENOSYS investigation remains open under
[issue-20261003-131111-752](../internals/backlog/issue-20261003-131111-752-investigate-intermittent-process-wide-profiler-int.md).
