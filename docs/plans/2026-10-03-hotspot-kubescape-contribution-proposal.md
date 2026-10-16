# Proposal: opt-in event-correlated HotSpot enrichment for Kubescape

**Status:** Local contribution draft, based on standalone milestone `2f1b5ee1`.
No upstream issue/PR, node-agent dependency change or Kubernetes deployment is
claimed. This prepares Task 6 of the original implementation plan; selecting and
validating the actual Kubescape dependency/event/storage contract remains open.

## Intended user-visible result

Optionally attach typed Java call-path evidence to selected synchronous file-open
handler-entry observations, captured externally through Gadget/OTel without JVM
injection. Existing detection events continue independently when enrichment is
disabled, unavailable, skipped or failed. Diagnostic attribution is not an
enforcement boundary, complete reachable-behavior inventory or policy proof.

The companion hook emits its own operation payload and captured stack reference
at the same `openat` entry. It must not timestamp-join a stack onto an unrelated
existing event, use the latest stack for a TID, or treat syscall-name inventory as
an event-context source. Handler entry does not prove the open succeeded or cover
attempts denied before that handler.

## What is implemented and measured

The standalone Linux x86-64 experiment reuses Gadget's event-triggered capture
helper, its compatible profiler fork, native/HotSpot walkers, process metadata,
correlation cache, waits and pruning. A bounded adapter preserves provider frames
and quality, delivering exactly one terminal enrichment outcome for each received
event. The application runs as an ordinary user; the collector is privileged with
host-PID/process visibility and the recorded mounts. Node-agent deployment/mount
requirements still need validation on the selected Kubernetes configuration.

The exact pinned interpreter/Serial profile adds safepoint-epoch validation and
immutable metadata reads. Graceful full stop retires the sole openat producer,
drains event and definition transports, acknowledges final consumption, finishes
enrichment and separately joins auxiliary/prune workers under one remaining stop
budget. Errors and held callbacks prevent certified retirement.

| Evidence | Actual result | Claim boundary |
|---|---|---|
| Stable C2/tiered/interpreter, 1/4/16 platform workers | 18000 intended tokens observed, zero wrong/order substitutions; availability varies by case | Controlled stable corpus, not general JIT reclamation safety |
| Controlled C2 inline fixture | Application shared-leaf inlining backed by compiler evidence | Demonstration, not full inline-chain/BCI equivalence |
| Independent USER_NOTIF reference | Candidate 1986 useful/14 unavailable; reference 2000 ordered, zero wrong | Separate runs; semantic BCI equality explicitly unverified |
| Strict epoch disposable loaders | 1580 checked/correct measured segments; all 80 generations represented in acceptance gate | Exact pinned interpreter/Serial lifetime profile |
| Final paced full capture | Three repeats each 1200/1200 correct, zero measured wrong/missing/skipped/unavailable | Same host/fixture; full loss remains partial |
| Longer finite paced control | Three repeats each 12000/12000 correct; approximately 19-second sampled resource windows | Finite observations, not a leak-free or long-running memory guarantee |
| One-route pressure control | All 3000 events delivered; one repeat 2969 correct plus 31 explicit skips | Reduced diversity does not eliminate admission pressure |
| Rejected 10000-operation burst | 4523 missing, 2984 correct, 2493 skips; partial reserve failures match missing count | Successful draining cannot recover failed publication |
| Genuine first-definition drop | Subsequent occurrences recover; original unavailable outcomes remain unchanged | Real cache invalidation, not substitution from another event |
| Native shutdown controls | Queued definition8/5000, held perf callback and idle netlink wake/join pass | Scoped transport/worker ownership, not every native loss/FD domain |

Detailed source/runtime/compiler artifacts and rejected controls:
[verification index](../../experiments/hotspot-agentless/results/README.md),
[pressure/resource windows](../../experiments/hotspot-agentless/results/full-pressure-envelope.md),
[auxiliary finalization](../../experiments/hotspot-agentless/results/auxiliary-worker-retirement.md).
GraalVM JVM mode has no accepted pinned-runtime integration matrix here. Virtual
thread identity, asynchronous parents, OpenJ9, Native Image and ARM64 are outside
this first integration scope. The normal C2 corpus is distinct from strict epoch
mode and must not be presented as a universal safe production profile.

### Measured overhead and bounds

Final `benchmark.ab5812c8`, eight routes, 600/600 preparation, 1200 operations and
500000 ns requested pacing: baseline 1553.83–1656.24 operations/s; full
1291.61–1444.07 operations/s. Baseline p99 70812–159255 ns; full p99
181878–405760 ns. Runs are independent and rotated; these ranges do not establish
a universal overhead ratio or a production capacity threshold.

Collector CPU/RSS observations include readiness/preparation and are documented
with their actual attach-to-target-done windows. Kernel memory is not included in
sampled process RSS. Prototype bounds remain four enrichment workers, 1024 pending
events, 16 MiB pending payload and a one-second original BOOTTIME deadline; event
ring256 KiB, definition perf512 KiB/CPU, producer LRU256 and frame cache16384.
Strict sessions are capped at 60 seconds, with a two-second remaining native
finalization budget. These are test limits, not agreed node-agent recommendations.

## Genuine record projection and semantics

This projection pairs a real measured event and its enrichment from
`benchmark.ab5812c8/full-0/events.jsonl`. The full provider record has **25 frames**;
only two application frames are displayed below, in original leaf-to-root order.
The actual adapter preserves the complete sequence. This display projection is
not a proposed Kubescape storage schema.

```json
{
  "session_id": "e600a9611e1c4b50a2929989785c1399",
  "event_id": "e600a9611e1c4b50a2929989785c1399:8:61",
  "event": {
    "event_kind": "openat_handler_entry",
    "host_pid": 1683723,
    "host_tid": 1683746,
    "process_start_ticks": 25550810,
    "capture_boot_ns": 255512878396890,
    "clock_domain": "CLOCK_BOOTTIME",
    "correlation_id": 255512878481428,
    "filename": "/fixture/mw-bench-f2a72c85f2af6777-000000-r0",
    "path_truncated": false
  },
  "evidence": {
    "attribution": "event_correlated",
    "walk": "returned",
    "scope_lookup": "mixed",
    "hotspot_identity": {
      "state": "safepoint_epoch_checked",
      "epoch": 0,
      "profile": "temurin25_xint_serial"
    }
  },
  "frame_excerpt": [
    {
      "frame_type": "jvm",
      "function_name": "void BenchmarkFixture.leaf(java.lang.String)",
      "source_file": "BenchmarkFixture.java",
      "source_line": 9,
      "bci": 5,
      "bci_state": "observed_in_range",
      "scope_lookup": "interpreter",
      "scope_walk": "unknown"
    },
    {
      "frame_type": "jvm",
      "function_name": "void BenchmarkFixture.route0(java.lang.String)",
      "source_file": "BenchmarkFixture.java",
      "source_line": 17,
      "bci": 1,
      "bci_state": "observed_in_range",
      "scope_lookup": "interpreter",
      "scope_walk": "unknown"
    }
  ],
  "provider_frame_count": 25
}
```

The random session/occurrence ID is distinct from the transient correlation ID
and from a normalized stack-content ID. None becomes `CallID` automatically.
`walk=returned` does not assert walk completeness. BCI `observed_in_range` is a
provider observation, not opcode equivalence proof; BCI, source line and native
address retain distinct optional fields. Unknown values are not fabricated zeros.
Collection summaries preserve `complete=false` for native loss even when buffers
and workers acknowledge completion. Unavailable and admission-skipped outcomes
must remain visible alongside useful frames.

## Dependency alignment: verified source mismatch

Standalone tested inputs:

- Gadget `7097db144ccd8dab15373f27ec98d2c3d22e4588`.
- Profiler root replacement `github.com/inspektor-gadget/opentelemetry-ebpf-profiler`
  at `ef6cf29492c8a980f889052903fa2dd0c0765cc0`.
- Go1.26 and Cilium eBPF0.20.0, with the exact root replacements and generated
  patch bundle `e908bb2689936481f8a696dd2347457cc44b6344c1fa78ca65b37772032703eb`.

Source inspection at node-agent
[`44c4cb2951d5de2ce09231920d9b21c61329eaca`](https://raw.githubusercontent.com/kubescape/node-agent/44c4cb2951d5de2ce09231920d9b21c61329eaca/go.mod)
shows Go1.27, Cilium eBPF0.22.0, storage0.0.303 and a Gadget root replacement to
Kubescape fork `v0.0.0-20260826074832-06b0d12baca0`. At that exact fork,
[`go.mod`](https://raw.githubusercontent.com/kubescape/inspektor-gadget/06b0d12baca0/go.mod)
uses Go1.25.0/Cilium0.22.0 and contains no profiler requirement/replacement. The
inspected `pkg/symbolizer/otel/otel.go` path returns404, and its
[`user_stack_map.h`](https://raw.githubusercontent.com/kubescape/inspektor-gadget/06b0d12baca0/include/gadget/user_stack_map.h)
does not contain the standalone OTel helper `gadget_get_user_stack_from_kprobe`.

Thus this is not a demonstrated drop-in dependency-only node-agent change.
Maintainers must choose the bridge/fork revision or a reviewed backport strategy;
the standalone corpus must then run on the selected root graph. Required Go
module replacements are not inherited from dependencies. No unrelated dependency
modernization or root-module change is included in this local proposal.

## Storage and baseline event contract

Pinned storage
[`v0.0.303/types.go`](https://raw.githubusercontent.com/kubescape/storage/v0.0.303/pkg/apis/softwarecomposition/v1beta1/types.go)
defines `StackFrame` with `FileID string`, `Lineno string` and `FrameType int64`.
`IdentifiedCallStack` has `CallID` and `CallStack`. Those fields alone cannot preserve
method name, optional source information/BCI, provider identity and evidence quality.
Method names must not be hidden in FileID, BCI must not be written as Lineno, and
correlation IDs must not be treated as stable call identities.

The proposed integration needs an additive versioned representation, agreed
orientation/identity/deduplication semantics, profile-size limits and coordinated
schema/generated-artifact round trips. Preserve raw provider order in fixtures so
an accidental leaf/root reversal is observable. Reuse node-agent lifecycle and
reporting/deduplication where their semantics fit. Preserve ordinary syscall
inventory and baseline detection; only companion enrichment may be skipped under
pressure, with explicit reason/counts.

## Review units and integration decisions

[Patch inventory](../../experiments/hotspot-agentless/patches/README.md) identifies
the independent local review families. Small structured capability/immutable-frame
changes should be reviewed separately from quality annotations, recovery, loss
dispatch, strict epoch/native ABI changes and lifecycle work. The entire local
bundle must not be described as one small upstream patch.

Required decisions before node-agent implementation:

1. Exact selected companion hook and handler-entry semantics; target/container
   lifecycle, host/namespace PID generation and occurrence identity.
2. Actual Gadget/OTel fork graph, bridge support and root replacements.
3. Versioned frame/evidence schema, leaf/root order, call identity and profile
   deduplication; old/new schema compatibility.
4. Default-disabled configuration, per-container/session budgets and observable
   enrichment failure behavior; tested runtime profile admission.
5. Node-level process/native-read privileges, visibility and deployment mounts.

Following agreement, verify disabled means no extra probe/profiler; unavailable/
failed enrichment never suppresses baseline events; two containers with overlapping
namespace PIDs and target restarts remain isolated; overload/profile limits stay
bounded; stale/reordered/lost definitions affect availability rather than attaching
another event's stack. Re-run native/corpus/quality/oracle/pressure/teardown controls
on the exact node-agent-selected graph before claiming the Task6 integration gate.

Tracked locally in the [dependency/storage contract issue](../internals/backlog/issue-20261003-180846-912-align-kubescape-hotspot-contribution-dependency-an.md).
