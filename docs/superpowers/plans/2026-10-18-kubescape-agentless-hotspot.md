---
document_type: execution_plan
base_revision: 16fab8e6f36f3f1fdb57964003affd30a420219c
status: provider_admitted_pending_sdk_release_and_cross_repository_gates
---

# Kubescape Agentless HotSpot Forward Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. This entrypoint schedules four separately reviewable subprojects; extract tasks from their owning plans, not from an unrelated completed ledger.

**Goal:** Deliver verified event-correlated Java stack evidence into Kubescape for ordinary JVM workloads, using Elastic/OTel externally and no application agent.

**Architecture:** Reuse the qualified collector as a bounded node-agent-owned companion with its own Gadget/OTel graph. Share asset/wire validation, add typed storage evidence, then wire container lifecycle/profile publication and prove the complete path in Kubernetes. Existing baseline sensors and strict schema2 remain independent.

**Tech Stack:** BPF C/OTel Go collector, a standard-library Go bridge module, Go1.27 node-agent, Kubescape storage0.0.303-based additive API/codegen, Kubernetes; existing JDK25 fixtures and Gradle gates.

**Spec:** [Governing design](../specs/2026-10-18-kubescape-agentless-hotspot-design.md).

**Execution approval (2026-10-18):** The user's explicit Subagent-Driven selection
after the six-document plan handoff approves P0 implementation/design scope by
reference. Task0 makes that authority durable before implementation. Native
qualification/admission, SDK publication, writable storage provisioning, the dirty
node-agent baseline and cluster operations retain their separate gates; no profile
or A/B/C/D deliverable is qualified or completed by this checkpoint.

**Current provider checkpoint (2026-10-19):** Shared SDK extraction/decoder and
separate observed negotiation are implemented. The
[actual six-case candidate gate](../../plans/2026-10-19-hotspot-coupled-exec-evidence.md)
now qualifies the bounded observational corpus; source/native/strict regressions
pass. This does not authorize observed admission, a new SDK release or the A5
provider handoff. Writable storage provisioning, the node-agent prototype baseline
and cluster access remain separately controlled. No B/C/D implementation or end-to-end
Kubescape integration is claimed by the candidate result.

**Read-only cross-project checkpoint (2026-10-19):** The separate storage checkout
now exists at `61f0be2f51c54abac7abd3d405f662c550e0ecae`, with local schema,
aggregation and opt-in API gate preparation. Its own handoff explicitly has
`b3Accepted:false`; real API/rollout/release evidence remains missing. Do not
reprovision S or redispatch B1/B2. The node-agent prototype remains dirty and
operator-owned. See the [specific remaining gates](../../plans/2026-10-19-hotspot-integration-gate-checkpoint.md)
before selecting more work; this observation grants no external edit or cluster authority.

**Later admission approval:** The operator explicitly approved the scoped A4
observed-profile admission change. Its [actual-CLI continuation](../../plans/2026-10-19-hotspot-observed-admission.md)
has new executable/manifest/native receipts, not relabeled candidate artifacts.
SDK publication, B3 live API, C's dirty baseline and D's cluster gates remain
separate. No external-repository edits or deployment follow implicitly.

## Global Constraints

- JDK25 idioms; library compatibility floor JDK22. Supported host Linux-amd64.
- Production remains pinned Temurin25.0.4.1+1-LTS, `-Xint -XX:+UseSerialGC`, synchronous `openat_handler_entry` until a separately approved capability gate passes.
- Readiness <=10seconds, session <=60seconds, stop <=2 remaining seconds, event enrichment deadline1second; no larger worker/pending/native buffer budgets.
- Kotlin retention4096 occurrences/33,554,432 wire-payload bytes, max line1,048,576bytes, max wire rows65,536, max frames4096, max tracked occurrences32,768.
- Rehash every asset byte and validate the complete local OCI graph; no size/mtime/inode digest cache or trusted-helper self-verification shortcut.
- No dependencies or breaking public APIs; additive native/public interfaces require explicit approval and legacy ABI verification before promotion.
- No swallowed/downgraded EPERM/EACCES. Fallback is explicit operator choice only.
- Raw FFM remains behind NativeEngine/io.mazewall.ffi; confined arenas, immediate errno capture, acquired descriptor ownership and checked close.
- Captured occurrence/session/process generation/BOOTTIME identity is immutable; never substitute a later stack or infer logical identity from host TID alone.
- Physical drain, selected-stream accounting, whole-session collection and policy eligibility remain separate. Policy coverage stays incomplete for selected openat evidence.
- Never install seccomp from a virtual thread or change JVM coordination syscall floors; do not touch GITHUB_TOKEN.
- Native builds remain optional, reproducible and operator-invoked; no startup compiler/download/sudo or registry fallback.
- Preserve user work, scope commits to the relevant backlog item and use the orchestrator-managed review path; no default-branch push.
- Native enrichment workers4, pending events1024, pending payload16MiB, producer LRU256, frame cache16384, frame data3072words and perf data524288bytes/possible CPU remain fixed.

## Scope and execution order

|Subproject|Owning plan|Independent deliverable|Dependencies|
|---|---|---|---|
|A|[Provider and bridge](2026-10-18-hotspot-observed-provider.md)|Verified shared Go contract + explicitly qualified ordinary-JVM observational collector|P0 approval|
|B|[Typed storage evidence](2026-10-18-kubescape-stack-evidence-schema.md)|Backward-compatible JSON/protobuf/OpenAPI typed stack metadata|P0; writable storage checkout|
|C|[Node-agent companion](2026-10-18-node-agent-hotspot-companion.md)|Default-disabled, bounded container-owned capture + exact profile publication|A+B; operator baseline of dirty N|
|D|[Kubernetes acceptance](2026-10-18-kubescape-agentless-hotspot-acceptance.md)|Packaged artifact and real persisted-profile acceptance/failure receipts|A+B+C; explicit cluster approval|

A and B have no shared source and may be scheduled independently only if the
operator selects parallel work. Default is serial execution. C may be developed
against local reviewed A/B source in an owned `go.work`; release builds/CI use
actual published revisions and recorded sums, not invented hashes or absolute
replacements. Direct Gadget embedding/backport is a later optimization, not a
dependency of the first working slice.

## Preflight before any implementation task

- [x] P0 implementation/design scope approved by the user's 2026-10-18
  Subagent-Driven selection after the six-document handoff: shared public Go module,
  schema3 observation semantics, additive storage fields and companion placement.
  This does not admit unqualified JIT/GC behavior or authorize overwriting N.
- [ ] Inspect all assigned repository statuses, active orchestrator slots and
  applicable AGENTS. Preserve Codanna settings and the dirty Tier E prototype.
  Ask the operator to checkpoint/approve the prototype baseline before editing
  its existing files. Do not create worktrees or branches by assumption.
- [ ] Provision S only with operator permission. Its baseline commit is known;
  never edit the Go module cache. Record `git rev-parse HEAD`, dirty diff, actual
  toolchains and source hashes before new builds.
- [ ] After P0, deliberately commit these six governing documents together with
  their scoped backlog reference before executable changes. The directory is
  ignored by default; use exact-path additions only and the unmodified commit
  hook. Do not leave an executor depending on uncommitted private plan authority.

```bash
M=/home/leanid/Documents/code/java/jseccomp
N=/home/leanid/Documents/code/java/stack-attribution/node-agent
S=/home/leanid/Documents/code/java/stack-attribution/storage
git -C "$M" status --short --branch
git -C "$N" status --short --branch
test -d "$S/.git" && git -C "$S" status --short --branch
```

## Task 1: Execute A and preserve its release handoff

**Files:** Owning A plan; implementation scope is declared there.
**Interfaces:** Produces `bundle.Verified`, `wire.Capture`, manifest3 and a real
module/provider release receipt. Consumed by C and D.

- [ ] Read the specification's A contract, execute A tasks with RED/GREEN/native
  checks, and complete its task review before C consumes the result.
- [ ] Check the actual release handoff rather than guessing a future checksum:

```bash
cd "$M"
cat build/kubescape-handoff/provider.json
test -s build/kubescape-handoff/provider.json
```

Required keys: `bridge_module`, `bridge_commit`, `bridge_version`, `collector_commit`,
`wire_schema`, `runtime_profile`, `manifest_sha256`, `collector_sha256`,
`gadget_digest`, `qualification_status`, `qualification_report`, `assets_dir`; profile must equal the approved schema3
profile and qualification must be `qualified`, not merely a build success.

## Task 2: Execute B and preserve its schema handoff

**Files:** Owning B plan; no edits in cached storage.
**Interfaces:** Produces actual optional `FrameSymbol`/`CallStackEvidence` JSON,
protobuf and OpenAPI, plus reviewed storage revision/server image receipt.

- [ ] Execute B independently; its tests must cover protobuf and actual server
  round trips, not only Go JSON.
- [ ] Validate the actual handoff:

```bash
test -s "$S/build/hotspot-schema-handoff.json"
cat "$S/build/hotspot-schema-handoff.json"
```

Required keys: `storage_commit`, `module_version`, `server_image_digest`,
`symbol_schema_version`, `protobuf_test`, `openapi_test`, `api_roundtrip_test`.
All three checks must be executed/passing; a missing tool is a blocker, not a skip.

## Task 3: Execute C against the real A/B contracts

**Files:** Owning C plan; current user work preserved.
**Interfaces:** Consumes the actual two handoffs; produces node image/build receipt,
completed capture artifacts, typed profile publication and baseline-safe opt-in.

- [ ] Execute C using its exact feature-disabled, process, identity, queue,
  storage-capability and publication tests. Never adapt agentless rows into Tier E.
- [ ] Check its handoff and existing regression suite:

```bash
cd "$N"
cat build/hotspot-node-handoff.json
go test ./pkg/config ./pkg/stackattribution/hotspot ./pkg/containerprofilemanager/v1 ./pkg/objectcache/callstackcache
```

Use verified Go1.27 and the explicitly selected dependency/workspace graph. Native
checks remain explicit; a compiler/unit pass does not certify attached collection.

## Task 4: Execute D and integrate the complete vertical slice

**Files:** Owning D plan and its example/CI/evidence files.
**Interfaces:** Produces an actual saved-profile/SBoB bundle and acceptance report
binding all three commits, all image/source hashes and original failures.

- [ ] Run D only on the approved cluster. Require plain-JVM capture, exact saved
  symbols/CallIDs and checked resource retirement before declaring integration.
- [ ] Perform one final cross-project review, one consolidated correction wave
  and one scoped re-review; reuse valid same-revision checks. Integrate each repo
  through its operator-managed review path; no default-branch push or cleanup of
  evidence/user files is implicit.

```bash
cd "$N"
go run ./examples/agentless-hotspot-sbob/cmd/verify --bundle build/agentless-hotspot-sbob/result
```

The verifier is implemented in D, not present today. Its success requires API-fetched
profile data and complete receipts. A complete profile bundle with an unqualified
runtime, failed child cleanup or lost symbols is a failed gate.

## Completion checklist / non-claims

- [ ] A/B/C/D deliverables and their independent gates exist, not just plans.
- [ ] Applications use no collection agent or collector-specific JVM flags.
- [ ] Baseline detection remains unchanged when disabled or enrichment fails.
- [ ] Full symbols survive API/protobuf storage and evidence is policy-ineligible.
- [ ] Node/container/process generations, unknown/lost outcomes and owners reconcile.
- [ ] Actual module/native/image identities and toolchain versions are recorded.

Existing allocator, strict-schema2, source-audit and finite native-drain work is
complete and must not be redispatched. Ordinary-JVM support, SDK/storage changes,
node wiring and cluster acceptance are future deliverables, not claims made by
this forward plan. The existing USER_NOTIF cleanup issue stays with its owner.

## Author self-review / coverage map

|Specification requirement|Planned implementation/check|
|---|---|
|A1 separate observation semantics, old strict compatibility|A Task3 negotiation/reader/owners; A Task4 candidate-only gate and rebuilt admitted CLI|
|A2 common verified assets and exact bounded wire|A Tasks1-2 extraction, complete positive/adversarial streams, source-byte and ABI parity|
|A3 ordinary JVM without agent/forced runtime|A Task4 plain tiered/G1/compiled-churn cases and actual verdict|
|B typed JSON/protobuf/OpenAPI/file persistence|B Tasks1-3 generated formats, typed aggregation and real negotiated API round trips|
|C identity/readiness/stop/queue/caps|C Tasks2-3 process controls, pending-owner and exact target tests|
|C content IDs/profile-safe observation|C Tasks1+4 typed projection/hash/legacy parity and false-policy enforcement-index exclusion|
|C default-disabled lifecycle|C Task5 constructor-zero, failure and watcher/stop tests|
|D actual saved-profile/SBoB acceptance|D Tasks1-3 packaged bytes, independent semantic negatives, approved cluster and raw/API/retirement receipts|

Self-review corrections included the real `make binary` target, isolated default
Docker build, matching four-argument Java fixture, pre-admission test-only native
entry, SDK type ordering, valid-before-corrupt fixture, storage value-preserving
aggregation and protobuf generation. Plan/code examples receive syntax/reference/
constraint checks; they are proposed code and have not been compiled/executed as
an implementation. P0 approval is recorded above; writable S, the operator's N
baseline, real dependency publication and the native/cluster gates remain explicit
execution prerequisites, not filled-in successful outcomes.
