# Profiler route and repository review — 2026-11-03

## Recommendation

Keep one canonical event collector and a versioned evidence contract, with
separate mazewall and Kubescape adapters. Tighten the delivery critical path:
profiler capability, product integration, and historical recovery/release need
separate outcome tracking. Two independent profilers sharing only ideas would
duplicate the hardest correctness and maintenance work.

The operator requested this review and its documentation while the existing
cold-runner implementation continued. This note records architectural findings
and recommended scheduling adjustments. It does not certify that implementation,
close outstanding review findings, change live authority, or authorize a new
repository, migration, deployment, rebuild or recovery route.

## Evidence inspected

- [Original collector/Kubescape plan](../../plans/2026-09-29-agentless-hotspot-kubescape-plan.md),
  especially the event-correlated collector, optional enrichment and schema gates.
- [Current delivery recovery plan](../../superpowers/plans/2026-11-02-profiler-delivery-critical-path.md),
  especially M1, M4/M5 and its effort/reassessment checkpoints.
- [Canonical collector](../../../profiler/hotspot-agentless/README.md),
  [shared Go bridge](../../../hotspot-bridge/README.md), and
  [mazewall public integration](../../../profiler/HOTSPOT_AGENTLESS.md).
- [Native fixture evidence](2026-10-23-hotspot-public-native-attempt.md) and
  [coverage/performance analysis](../research/hotspot-sbob-coverage-and-performance.md).
- Actual Kotlin `MazewallProfiler` and `HotSpotCoverageAdapter`, bridge module
  dependencies, and the local node-agent supervisor, projection and profile sink.
- Local external checkouts under
  `/home/leanid/Documents/code/java/stack-attribution/`: node-agent HEAD
  `72034696017511e05f44d115e4081f39821cbd4d`, storage HEAD
  `4c2e1cf1b64b6169b02b7a9b0ceacafd985aad5f`. These had 21 and 7 tracked changes
  respectively; conclusions describe inspected working-tree code, not newly
  qualified immutable releases. No external files were edited.
- Context7 returned unversioned Kubescape documentation, including conflicting
  historical ApplicationProfile and current ContainerProfile descriptions.
  API conclusions were therefore checked against official source snapshots:
  [storage `de45e332`](https://github.com/kubescape/storage/blob/de45e3328934056417397e7cc7f094d99bbee8cc/pkg/apis/softwarecomposition/v1beta1/types.go#L204-L233)
  and [node-agent `9c65699a`](https://github.com/kubescape/node-agent/blob/9c65699af3d088385dc282f1e321ad3333e37f43/go.mod).
  These are upstream commit snapshots observed on this review date, not a claim
  that the selected integration was tested against those versions.

## Findings

### 1. The existing collector boundary fits both products

```text
Gadget/OTel event collector
        |
versioned evidence protocol and verified bundle
        +-- mazewall: Kotlin session/result adapter
        +-- Kubescape: container lifecycle/profile-storage adapter
```

The canonical Go collector lives in `profiler/hotspot-agentless`.
`hotspot-bridge` is a separate Go module with only standard-library dependencies;
it provides bundle verification and schema2/3 wire consumption. The node-agent
adapter supervises the verified external collector rather than embedding its
Gadget/OTel runtime. Kotlin has its own process/session implementation with the
same evidence semantics. Different host-language adapters are appropriate.

The experiment tree is an explicitly materialized evidence snapshot with recorded
source provenance, not another product implementation to evolve independently.

Preserve this separation. Embedding the collector into node-agent now would add
dependency/lifecycle coupling without establishing better evidence. Share
protocol fixtures, identities, quality semantics and release artifacts; keep
Kubernetes discovery/storage and JVM policy workflows in their respective hosts.

### 2. Recovery infrastructure is dominating the product critical path

The November plan correctly identifies itself as a recovery schedule, but its
headline goal conflates that work with implementing the profiler. The current
Python entry exceeds 300 KB and owns substantial compilation, custody and
retained-session machinery. Size alone does not establish that a guard can be
removed; it does expose a significant cost before a new user-facing result.

There is already useful native collector evidence: the retained ordinary-JVM
fixture observed 400/400 marked operations, useful attribution for 380/400 and
zero swapped routes. That bounded result is separate from the NEW recovery
route, image delivery and persisted Kubescape acceptance. Progress reporting
should distinguish these rather than imply that no profiler exists yet.

Recommended scheduling adjustment:

1. Finish the current cold-runner task with its actual PASS or exact blocker.
2. At that boundary, report remaining recovery work and actual effort, and
   reassess before another major recovery subsystem is started.
3. Track collector capability, each product's integration, and recovery/release
   as separate outcomes with explicit dependencies.
4. Preserve accepted artifacts, consumed authority and required recovery order.
   A shorter alternative route would require its own explicit scoped decision;
   renaming a task does not remove those obligations.

The existing plan's day-one checkpoint and two-active-engineering-day M1
reassessment remain useful. Calendar time is not measured engineering effort.
The provisional 7–9-day allocation should not be presented as a reliable ETA
before the remaining M1/M2 work is measured. Component test totals alone are
insufficient delivery indicators.

### 3. mazewall can reuse observations without treating them as enforcement proof

`MazewallProfiler.observeHotSpot` already provides explicit ordinary-JVM
observation. `HotSpotCoverageAdapter` sets coverage incomplete; its generic
`BillOfBehavior` is empty while structured evidence is retained separately.
`AUTO` still selects USER_NOTIF. The observed profile explicitly denies policy
eligibility. These distinctions should survive integration and documentation.

The current backend is useful for selected event/stack evidence. It cannot be
advertised as complete syscall/path discovery or a replacement for
coverage-qualified policy generation. Retain existing mazewall policy/compiler
and JVM coordination guarantees instead of broadening observation claims.

### 4. Kubescape has a concrete schema/release gap, not a need for a second engine

The inspected node-agent fork pins `hotspot-bridge v0.2.0`, with canonical storage
requirement `v0.0.303` replaced by the selected Pilleo storage revision
`6815ab7d489114310ecb8e22b45909f8c86e944f`. Its typed symbol/evidence contract is
an additive local integration, not a drop-in upstream feature.

At the inspected upstream storage commit, `IdentifiedCallStack` still contains
only `CallID`/`CallStack`, and `StackFrame` only `FileID`/`Lineno`/`FrameType`.
The fork's typed symbol/evidence fields are absent. Upstream node-agent has also
advanced to storage `v0.0.382` and Gadget fork revision `9864428b73f8`, beyond the
selected fork baseline. This is compatibility work to plan explicitly, not a
reason to update dependencies mid-qualification.

First complete acceptance against the deliberately selected fork graph, then
handle upstream reconciliation as a bounded contribution task. Acceptance must
include actual ContainerProfile persistence/reload, correct container/image/birth
identity, target restart, schema round trips and expected field preservation.
Local DTO tests and historical retired storage API receipts are complementary,
not current-server or end-to-end acceptance.

Preserve optional enrichment: disabled means no extra collector, and unavailable
or overloaded enrichment must leave baseline collection functioning. The current
native configuration has significant explicit host visibility/capability needs;
the integration is opt-in on supported nodes, not universal default deployment.

### 5. Broader SBOB coverage needs measured scope expansion

Current observation covers selected `openat` handler entry on a pinned
Linux-amd64/HotSpot configuration. An entry event is not successful access,
complete application behavior, class-loader identity or virtual-thread ownership.
The inspected node-agent policy uses one bounded observation window per container
incarnation; that is not continuous whole-lifecycle coverage.

After the dual-product integration works, prioritize the existing research
questions: early attribution gaps, cold-start/lifecycle coverage, event bursts,
multi-container overhead/loss, then additional behavior classes and runtimes.
Keep behavior scope, delivery, useful attribution, exercised workload and
persistence coverage separate. A successful or loss-accounted window cannot
silently establish unobserved-path coverage.

## Repository decision

Recommended now: one canonical collector and shared protocol/bridge; host-specific
adapters remain in mazewall and the existing Kubescape node-agent/storage repos.
There is no present evidence that two new independent profiler repositories would
solve the actual delivery, schema or coverage bottlenecks.

A neutral shared-core repository can be considered after first dual-product
acceptance if maintainers require independent governance or release ownership.
That would extract the existing collector/bridge with compatibility/provenance
preserved, rather than fork two engines or share only design ideas.

## Next outcome checkpoints

| Checkpoint | Evidence required |
| --- | --- |
| Current cold runner | Actual retained history/status result, or exact blocker and measured remaining work |
| Product contract | Supported runtime/kernel/profile/dependency matrix and explicit observational guarantees |
| mazewall acceptance | Actual public API result with target identity, useful frames, quality/loss and owned cleanup |
| Kubescape acceptance | Selected node-agent/storage versions produce and reload the correct typed ContainerProfile |
| Operational behavior | Disabled/failure paths, restart, overload, bounded overhead/loss and multi-container behavior |
| Recovery/release | Existing required authority, provenance, imports and persisted image acceptance |

The same selected collector release and conformance corpus should serve both
adapters. Reusing valid identical-revision evidence is appropriate; corpus replay
does not replace actual child/runtime or Kubernetes acceptance.

## Limits

This was a source, documentation and architecture review. No new build, native
benchmark, live deployment or acceptance run was performed. Existing blocked
provider re-review and integrated independent approval remain outstanding; this
strategy review is not a replacement for them.
