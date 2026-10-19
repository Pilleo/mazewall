# Local HotSpot storage API acceptance

The operator explicitly approved a disposable local kind environment and then
selected **Return full Create object** when the actual API gate identified the
metadata-only Create contract. Existing node-agent prototype work is preserved.

## Executed result

Storage commit `46c8444a4d9a0c78ca82058ee7d9563a00605ced` returns the persisted
ContainerProfile from Create under its existing write lock, without expanding
other resource responses, metadata watches/LISTs or Update behavior. Its regressions
exercise actual persistence, independent response ownership, metadata watches and
original persisted-read permission failure. Complete host tests, focused race
tests and tagged gate-helper race checks pass.

That exact source archive was rebuilt and deployed in owned context
`kind-mw-hotspot-1ae391a3`, namespace `agentless-hotspot-test`:

- Local image index: `sha256:cc66eb5c9540f91197ffb14a375d68e1247997db27beff1c490fcc83de223768`.
- amd64 manifest: `sha256:24c5d96ba68ed6c27b6307eae7ea6802a173b9bc8e38532093d11db2fc3c7c19`.
- CRI configuration: `sha256:2a9a814788709894f1226d7585a78cefd426e1dbd3eb709cf717822fb4682838`.
- Server pod UID: `2725a9d0-0e80-4388-bfb6-1bfd166a3681`.

The exported image graph and actual CRI identity were checked, not equated across
different digest domains. APIService UID/CA remained unchanged; TLS and permission
failures were never bypassed. Only confirmed APF catalog reads were added to the
owned test-server role. Pull policy stays Never.

The real tagged API test passes **JSON and Kubernetes protobuf Create/Get**.
Complete typed caller paths/evidence, false flags, optional nil/zero/max fields and
decimal positions survive. Comparison ignores only canonical dummy roots and
group/sibling ordering, never caller order, missing frames or typed values. The
original30s combined Create/Get and5s UID Delete budgets are unchanged. Six actual
transport exchanges have exact media types, complete bodies and201/200/200 statuses;
protobuf retains real binary envelopes, not JSON fallback.

After the gate's cleanup, independent inventory found zero ContainerProfiles.
JSON Create/Get/Delete ownership matched and the Ready server's UID/image was
rechecked. Evidence is under `build/kubescape-dev/local-cluster/`, principally
`live-api-full-create-checked.json`, `live-api-full-create.log` and
`storage-create-upgrade/ready.json`. Raw bodies stay private, without credential data.

## Preserved failures and limits

The first gate at61f0be2 remains **failed**, with both causes and independently
checked cleanup. Setup/image/reference/readiness failures and permission denials
are retained separately. A broad storage race run exposes an independently
reproduced baseline watcher-test race at unchanged61f0be2, recorded in a
[separate open issue](../internals/backlog/issue-20261019-083248-888-fix-pre-existing-storage-watcher-identity-test-dat.md).
No clean full-race result is claimed.

Storage's separate `build/hotspot-schema-handoff-live-20261019.json` records
`b3LocalAPIAccepted:true`, **releaseReady:false**, real server provenance and null
unpublished module/version fields. The prior incomplete handoff stays frozen.
The isolated cluster/server remain owned for follow-up: profile deletion is not
cluster retirement, and no existing cluster was touched.

No bridgev0.2.0 tag, published storage module/image, ready provider release handoff,
node-agent runtime/capability integration or final native Kubernetes acceptance is
claimed. [PR540](https://github.com/Pilleo/mazewall/pull/540) still requires the
prescribed review before SDK publication.

The first root checkpoint hook's backlog-index rejection was corrected by marking
the resolved row Archived. Its next unchanged full build rejected a Tier E delayed
artifact fixture with72expected/71recovered edges and missing path43. That XML,
build log and retained owner states are frozen in
`checkpoint-tier-e-coverage-failure/`. The exact focused test then passed without
any source, budget or coverage-threshold change. Its cause remains a
[separate open investigation](../internals/backlog/issue-20261019-084828-251-investigate-tier-e-delayed-artifact-coverage-dropp.md);
neither the later test nor this local B3 result relabels the original rejected run.
