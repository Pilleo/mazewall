# Agentless HotSpot integration: operator gate checkpoint

This is a local review checkpoint, not a provider release or Kubernetes acceptance
receipt. It belongs to the existing
[dependency/storage alignment issue](../internals/backlog/issue-20261003-180846-912-align-kubescape-hotspot-contribution-dependency-an.md).
No external checkout, cluster, registry, SDK tag or runtime admission is changed.

**Subsequent operator decision:** The operator explicitly approved only scoped
observed-profile admission after this checkpoint. The
[admission continuation](2026-10-19-hotspot-observed-admission.md) records the new
source and actual rebuilt-CLI acceptance separately. The initial state and
requested decisions below are retained as history; SDK publication, storage live
API, node-agent baseline and cluster gates are not approved by that answer.

## Observed state on 2026-10-19

|Area|Evidence now available|Still not established|
|---|---|---|
|A: provider candidate|[Six-case native qualification](2026-10-19-hotspot-coupled-exec-evidence.md); actual JVM exec/exit/read-denial failures retained; strict native and root gates pass|Operator admission decision and actual rebuilt admitted-CLI acceptance|
|A: Go bridge|Candidate format3 source and host checks; published v0.1.0 remains immutable|A separately approved new immutable candidate release; no format3 release pin exists|
|B: storage|Separate checkout contains typed schema, generated formats, value-preserving aggregation and opt-in API gate preparation|Real reviewed-server JSON/protobuf Create/Get, checked live cleanup, release/module/image identities|
|C: node-agent|Existing Tier E prototype remains operator-owned and dirty|Approved prototype baseline, reviewed A/B handoffs and companion implementation/acceptance|
|D: Kubernetes|Approved design and acceptance plan|Approved cluster/context/namespace/server/image and executed persisted-profile acceptance|

Candidate qualification says only that the controlled observational corpus meets
its own gate. It does not prove metadata/code reclamation ownership, logical
thread identity, whole-collection completeness, policy eligibility or end-to-end
Kubescape integration. The default CLIs still refuse observed collection before
acquisition. A candidate bundle is not an admitted provider handoff.

The coupled-exec/cause-retention implementation is committed locally as
`a7b6e878f3402ddcc07f5716df372668b0ce51d1`. The configured, unchanged
`scripts/hooks/pre-commit` also passes the full build plus portal integration/unit
tasks: 9m46s, 243 actionable tasks (31 executed, 212 up-to-date). Its preserved
`commit-active-full-build.log` is separate from the earlier focused completion
build; neither cached task reuse nor this hook is called a new native matrix.

The candidate bridge also passes an exact committed-source archive check outside
the repository, without a profiler sibling: both race-tested packages pass,
452 test/subtest passes and zero skips. Its Git subtree is
`8ac2d5cfc3b5ae1272e1ce38234e8171ced5eac5`; nonstandard dependencies are only its
own two packages. Receipts live in `build/kubescape-dev/coupled-exec/isolated-sdk*`
and `sdk-dependencies*`. This removes a packaging concern, not the publication gate.

## Storage: do not repeat completed local preparation

Read-only inspection found a normal checkout at
`/home/leanid/Documents/code/java/stack-attribution/storage`, branch
`feat/agentless-hotspot-stack-evidence`, HEAD
`61f0be2f51c54abac7abd3d405f662c550e0ecae`:

- `2c7c691`: typed stack symbols and observational evidence.
- `1e9d93a`: value-preserving profile aggregation.
- `61f0be2`: opt-in live API gate and explicit incomplete rollout handoff.

Its `docs/agentless-stack-evidence.md` and `build/hotspot-schema-handoff.json`
explicitly say **local preparation only**, `b3Accepted:false`. The handoff has no
approved context/server, live API receipts, published module version or server
image digest. Local JSON/protobuf bytes are not API evidence. Existing untracked
build receipts are left in place. This checkpoint reads their status; it does not
independently repeat, approve or certify the separate storage worker's tests.

The old planning-time "storage absent" blocker is therefore superseded by a
**live API/rollout approval** blocker. Do not provision a duplicate checkout,
reimplement B1/B2 or edit the module cache. Keep the external worker's ownership.

## Node-agent: preserve the supplied prototype

The separate node-agent remains on `feat/tier-e-sbob-importer` at
`1c4fb954cdd51edb98a2ad4100be392cd806e44e`, with tracked edits in `cmd/main.go`,
configuration, container-profile monitoring and storage, plus untracked examples,
Tier E importer and stack-attribution sources. This continuation does not stage,
overwrite, baseline or adapt those files. The operator must select their baseline
before C mutates them; qualification of A does not grant that authority.

## Exact remaining decisions and following checks

1. **Review A's observed admission scope.** Decide whether to authorize the
   separately negotiated schema3 profile for the pinned Linux-amd64 Temurin25
   observational claim. Strict2 and false policy eligibility stay unchanged.
   Approval authorizes a scoped admission change, not an assertion that the old
   helper already supports it. Rebuild twice and run actual admitted-CLI/SDK
   acceptance plus strict regression before generating a consumable handoff.
2. **Approve any new bridge release separately.** Candidate format3 cannot be
   published over immutable `hotspot-bridge/v0.1.0`. Select a new version and exact
   reviewed source, then authorize its tag/publication. Only the actual resulting
   revision and checksums may enter hermetic release declarations. Local isolated
   packaging checks are preparation, not publication.
3. **Supply B3's live API authority.** Select the reviewed storage server source,
   build/deployment procedure, explicit absolute KUBECONFIG/current-context,
   pre-provisioned `agentless-hotspot-test` namespace and receipt directory.
   Run the storage plan's tagged JSON/protobuf gate with UID-scoped cleanup;
   missing prerequisites fail before network access. Do not discover or mutate a
   cluster to fill these blanks.
4. **Approve C's prototype baseline before edits.** Use reviewed A/B local source
   only under the existing explicit workspace rules; release builds need real
   published pins. C remains default-disabled and must preserve legacy behavior.
5. **Authorize D's cluster operations only after A/B/C acceptance.** A local
   provider result or storage serialization test cannot substitute for API-fetched
   saved profiles and checked process/container/resource retirement.

Until those decisions and following gates exist, do not create a ready
`build/kubescape-handoff/provider.json`, claim a completed A5 handoff, publish new
support, push/merge, or modify another worker's checkout. Continue only reversible
local verification and review preparation within the approved scope.
