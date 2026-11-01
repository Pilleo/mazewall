# HotSpot companion: approved baseline and host-side wiring evidence

## Authority and preservation

After requesting continuation through integration, the operator explicitly selected
**Use current baseline** for the dirty Tier E prototype at node-agent
`1c4fb954cdd51edb98a2ad4100be392cd806e44e`. The promise is additive development,
recoverable custody, and exclusion of all pre-existing work from our commits.

`build/kubescape-dev/node-companion/baseline-custody.json` records a private archive,
working/index binary patches and hashes for all34original dirty/untracked files.
Initially only the two intended config files receive additive changes; C5 also adds
four proved main.go wiring operations. All other original files remain byte-identical.
Reverse-applying our own additions in owned verification copies reproduces the
original hashes; the original main.go is separately snapshotted before wiring.
The original prototype is neither committed nor discarded.

## C1: opt-in config and typed projection

Local node-agent commit: `94bce4852ca382fb607d0b87f8801be1cd6fdf12`.
Exactly six task-owned files/hunks are committed: the two additive config edits,
new config type/validation/tests, and new HotSpot projection/tests. The staged
tree16b6e4e267ae91b9e86ede80a7cfde47ee336876 is independently exported and race-tested
without the user prototype, rather than assuming a mixed-worktree pass certifies
the committed bytes. The checkout has no active pre-commit hook to bypass.

- Actual Go1.27.0; explicit local workspace selects node-agent, the reviewed bridge
  source and storage61f0be2. No fictional release requirement or absolute module
  replacement is committed. This is **local development**, not a hermetic release.
- The first offline command fails on an uncached existing dependency; it is not
  called RED. After fetching pinned dependencies with checksum verification,
  `task1-network-red.log` reaches real undefined config/projection API compile RED.
  Node/storage go.mod and go.sum remain unchanged.
- Focused and complete config/projection race suites pass, including the exact
  exported index snapshot. Logs/source inventories are retained in the evidence
  directory. Formatting and scoped whitespace checks pass.
- Disabled configuration ignores unused paths; enabled validation requires
  application profiling, absolute directories and a lowercase trusted SHA256 pin,
  without probing assets or storage. The four-field config defaults disabled.
- Projection uses the shared canonical content ID, not event/session/correlation
  IDs. It preserves caller order, full decimal positions/addresses, optional
  nil/zero/max source locations, BCI, quality and genuine legacy file/line values.
  Returned pointer fields are independent copies. Duplicate observable content
  can coalesce without removing unavailable occurrences from the original capture.
- Wrong profile/provider and unbound container/image reject; unavailable/skipped,
  empty and error-frame stacks never become fabricated profile rows. New evidence
  has explicit false policy/global flags. Projection is not process/native finality
  certification and does not infer missing Java identities.

## C2: physically owned companion supervisor

Node-agent commit `bf44378e` adds the verified-asset supervisor and disposable
synthetic process controls. Exact staged tree
`b1ff0786c27b771585c3cc165dce14620a88403a` independently passes Go1.27.0 race checks
without the prototype; mixed-worktree config and all stack-attribution package
race suites also pass. The three committed files contain no release pin or native
qualification claim. Original prototype bytes remain excluded and preserved.

Owned OS pipes precede Start. Stdout has one bounded decoder reader, stderr drains
into a64KiB tail, and exactly one Cmd.Wait runs independently of the manual pipes.
Logical summary cannot replace EOF/readers/Wait. Ready is bounded to10s and total
session to60s including2s cleanup, with one explicit stop write. Failures retain
decoder/native errno, exit status, bounded prefix/tail and truncation markers.
No failed outcome returns a publishable Capture.

A stop deadline is not retirement. Pending errors retain real process/reader
owners and expose cleanup retry; retry never turns rejection into success.
Controls include early EOF, foreign session/provider/target, synthetic EACCES/
target loss, malformed/oversized rows, stderr pressure, hung stop, exit7, caller
cancellation, stdin EOF, readiness expiry and strict-profile refusal. A real
duplicate stdout writer is held by the test: even after the child exits, reader
ownership remains pending until that writer closes and cleanup joins readers and
Wait. Actual child PID absence is then checked.

The first hung-stop assertion incorrectly assumed every failed capture was already
retired; its failed result is retained. Corrected assertions verify the explicit
pending-owner contract and real cleanup, not a larger native budget. Context7
Go1.27.1 os/exec docs were consulted; the actual compiler is1.27.0. These synthetic
sessions do not prove native callbacks, JIT lifetimes or collection completeness.

## C3 prerequisites: target, served capability and evidence publication

Node-agent commit `e7c9ab51bb08f3da0bfeea0b12aa6a0ae418dcb5`, exact staged tree
`c882283209179ee3edf08b1826511f94e0d14a00`, passes independent config/stack-attribution
race suites without the preserved prototype. Mixed-worktree suites also pass.
This is a prerequisite slice, not a running Controller or completed C3.

- The process-tree resolver checks uncached container membership before probing,
  preserves raw uint64 birth ticks, requires an actual TGID/libjvm mapping and
  matching cgroup/mount inode, and rereads birth/membership before returning.
  Multiple JVMs reject; missing metadata does not initiate probes. Inspection
  permission errors, including errors joined with ENOENT, remain fatal. An
  inspector result cannot substitute a different PID from the checked tree member.
  It never changes namespaces or invents metadata/code lifetime protection.
- Capability queries the selected REST client, bounded to10s/32MiB, and checks the
  served profile-to-frame/evidence reference chain and complete version1-compatible
  fields. Locally linked types are not proof of server support. Old/pruned/wrong
  schemas,401/403, caller deadline, corrupt/truncated/oversized bodies and required
  formerly optional locations reject. Body close/read errors remain errors.
- Private0700, current-user-owned evidence roots are opened without symlinks and
  held under an exclusive writer lock. FD-relative no-symlink opens, exclusive
  temporary files, checked sync/close and no-replace rename precede publication.
  Raw/typed content is bounded to32MiB each and the node inventory to256MiB; retained
  staging prefixes count toward quota and are never automatically deleted.
  Manifest digests bind actual bytes. SDK protocol replay rejects altered typed/
  raw content but does not replace the runner's physical readers/Wait proof.
- Real-filesystem controls cover collisions/concurrent publication, symlinks,
  traversal, quota, closed writers and injected rename/close/ENOSPC boundaries.
  Synthetic protocol/proc fixtures do not establish a native runtime qualification.

Linux man-pages6.19 openat2/renameat2/flock contracts were consulted. The code uses
existing x/sys wrappers, not new dependencies or a fallback for denied operations.

## C3 streaming custody and restart claims

Node-agent commit `15b9db856eda694d78ec64bc742efb0a4e4adcb3`, exact staged tree
`eabd84fbe94dfdc14255d22a138f51be9af86f47`, passes independent and mixed-worktree
config/stack-attribution race checks. It closes the streaming retention boundary
and adds diagnostic bundles and durable attempted-window claims.

A real disposable child emits more than32MiB of valid bounded rows. The initial
control returned a successful Capture with5discarded rows; that failed result is
retained. The supervisor now requests stop at streaming exhaustion, drains within
the original budget and returns ErrEvidenceExhausted with no publishable Capture.
Actual stop and physical retirement are checked, rather than waiting until disk
publication to discover an already-discarded raw stream.

Diagnostic bundles use the same checked no-replace writer, retain original causes,
binary stderr, bounded prefix/tail and truncation, and never invent missing process/
session/image identity. A reported session is labeled as reported, not validated
completion. Both authority flags remain false. Exclusive `.window-PID-birth`
claims reserve an attempt before collection, survive restart, and do not certify
a lifetime or successful collection. Existing claims must match the exact target;
partial/failed claims stay ambiguous and prevent reuse. Different raw birth ticks
are distinct. Claims and failed prefixes count toward quota, without auto-deletion.

## C3 local controller lifecycle

Node-agent commit `3aa32df6e6531181604cadfd36402cbdd805c864`, exact staged tree
`6580200c4e760e0f9ef6f749a369d667aa2c2824`, passes independent and mixed-worktree
config/stack-attribution race suites. It implements the Controller/Runner/Sink
boundary and serial discovery/publication, not manager or main wiring.

Capability admission precedes probes, evidence ownership or children. Callbacks
copy only scalar identity metadata. Discovery reconciles at1s with a10s bound,
explicit unavailable diagnostics, raw-birth/current-membership confirmation and
one15s window per incarnation. One active child and eight pending jobs are allowed;
overflow is explicit. Each launch rehashes all configured assets before acquiring
its durable claim. Removal/re-add generations invalidate stale capture publication.
Atomic evidence publication precedes the typed sink, and every accepted physical
owner is retained until joined, including failed/stop-pending runs.

Real disposable-child controls cover storage403 before effects, callback mutation,
removal/stop, permission failures joined with unavailable/cancellation, the full15s
successful window, restart claim reuse, delayed metadata, one-active/eight-queued,
overflow and10s unavailability. A held real stdout writer keeps Run/Stop pending;
after its actual close, cleanup returns a retained non-successful rejection with
no pending physical owner. It does not admit another child. Before-ready cancellation
can require a reaped kill; that original rejection is retained rather than relabeled
as a graceful capture. Earlier failed controls/timeouts remain in the receipt ledger.

## C4 local checked profile publication

Node-agent commit `e1d0c368`, exact staged tree
`22682127b77e6f5fca0cac4e018b8efa48e1c498`, passes independent connected race tests
for config, profile manager, callstack/cache consumers and all committed stack
attribution packages, with **zero skips** and no dirty prototype included.

`ReadyForHotSpot` and `PublishHotSpot` check the current container/image binding
under map-then-entry locks, matching `Close` lock order. The complete batch is
validated, deduplicated, deep-copied and checked against the existing size budget
before any mutation. Invalid/provenance-mismatched/promoted/cyclic/over-budget
inputs leave both content and size unchanged. Publication does not synthesize
syscall policy; physical capture finality remains the controller's responsibility.

Typed content hashes include complete scalar symbols/evidence and ordered tree
shape. Exact untyped legacy hashes, equality boundaries and keys remain unchanged.
Symbol-only frames are not dummy roots, and explicit false-policy observations
are excluded before enforcement indexing, including cache/reconciliation callers.
Controls preserve source-location nil/zero, unsigned decimal precision, pointer
ownership and unchanged legacy behavior; concurrent publish/Close completes.

Connected testing exposed additional before-ready test assumptions and a synthetic
string-decoding load, not accepted native captures. All PID-before-ready controls
now allow only known cancellation/reaped-kill leaves and still reject pending,
permission and unknown causes, with actual PID absence checked. The raw-byte
fixture uses valid JSON whitespace instead of huge extension strings; its32MiB
volume and15s/2s budgets are unchanged. Timed-out/pending controls explicitly join
retained owners before failing. Every prior rejection/cancelled run stays frozen.

## C5 local startup and disabled-baseline wiring

C5 commit `72034696017511e05f44d115e4081f39821cbd4d` now has startup wiring, admission/failure ownership and
bounded atomic terminal status receipts. Disabled startup returns before touching
dependencies; enabled admission rejects old schema, invalid assets and original
403/EACCES without registering receivers or probing processes. Existing containers
are replayed after storage/assets/evidence admission, which is not collector_ready.
Runtime errors reach main explicitly while baseline receivers continue. Stop
unregisters only this receiver before cancellation/drain; original failures remain
failed after physical cleanup. A regression proved terminal-status write EACCES
could be lost after pending-reader retirement; it now stays with the actual owner
through cleanup retry. Its original RED and focused GREEN are retained.

The ordinary package-based binary and real legacy/IG guards pass locally. Make and
both Dockerfiles now build ./cmd rather than the now-incomplete cmd/main.go alone;
the original compile failure is retained. The stale legacy-guard Make path was
also corrected without changing the guard test. Reviewed tree
`d958b50de57b3550ef31163f81b5dbda80a802ca` independently passes the connected
config/manager/cache/stack/cmd race suite with zero skips, both owning guards and
the binary build. Final committed tree `7670e4e903185f24df6a8fa79c7fec41378a0149`
adds only a save-path test; that exact exported tree passes its added race check.
All other tested source/test bytes and binary inputs are identical, so their
existing checks are reused. The new test drives the existing manager save and
real disk-backed queue into the storage boundary; symbols, caller order, nil/zero
locations and false-policy evidence survive, without synthesizing syscalls. Its
explicitly image-bound host-manager fixture is not a collected native JVM.

Original main.go is snapshotted separately. An exact four-operation additive edit
proof constructs the staged file from HEAD, independently of the preserved Tier E
prototype; it also reconstructs the current worktree from the original snapshot.
No prototype hunks or module declarations enter the commit. Source inventories,
binary build info and truthful `release_ready:false` are in `task5-final.json` and
the linked test receipts. Ordinary release/image and native gates
are not claimed: published bridge/storage pins and baseline tracer assets remain
required, and there is no permission to bypass the required release review.

The separate node-agent `build/hotspot-node-handoff-local-20261019.json` records
these actual source/binary/local test identities with `releaseReady:false`, zero
connected race skips and null unpublished module/image identities. Its ordinary
Docker/native acceptance fields remain false. It is a local evidence manifest,
not the ready C handoff consumed by final acceptance.

## Remaining independent gates

C1–C5 local host-side implementation is not a packaged running node-agent companion
or a native/cluster qualification result. Actual SDK/storage publication and reviewed
release pins are still required before release/CI handoff.

The operator separately approved a disposable local kind environment and building
reviewed storage61f0be2. The uniquely named cluster and its actual node UID/image,
private kubeconfig path (never credential bytes), verified kind binary and pinned
Kubernetes1.35.8 are recorded under `build/kubescape-dev/local-cluster/`.
The storage image is built from an exact committed-source archive, not the other
worker's untracked build files. Setup failures remain recorded. A Ready cluster
or correct image graph is not a successful B3 API or final D native acceptance.

The subsequent [local B3 API gate](2026-10-19-hotspot-storage-live-api.md) passes
against storage46c8444a after the separately approved full Create-response change,
with both formats and independently checked profile cleanup. Published storage
identity, node-agent runtime wiring and final D acceptance remain incomplete.

SDK review moved from the historical [PR540](https://github.com/Pilleo/mazewall/pull/540)
to the [split review stack](2026-10-19-hotspot-bridge-release.md#split-review-routing-2026-10-19).
No exact-source release approval, v0.2.0 tag, module checksum or ready provider
handoff is fabricated while that gate is outstanding.

The owned kind node has a nested PID identity chain316557→1, recorded in
`build/kubescape-dev/local-cluster/native-acceptance-topology.json`. It is valid
for the local B3 API gate but does not satisfy the collector's kernel-global PID
contract for final D. The denied initial namespace symlink read remains recorded;
no namespace operation or permission bypass was performed. Final native topology
requires a separate explicit environment decision before any replacement setup.

That decision is now supplied: the operator approved a disposable local KVM VM,
4vCPUs/8GiB RAM/up to30GiB disk, Ubuntu26.04 and K3sv1.35.8+k3s1 inside the guest.
The operator noted existing MicroK8s; it is not being used or modified. K3s is a
guest-local checksum-pinned binary, not a host installation. Existing bundled
QEMU8.2.1 was inspected read-only without contacting the Multipass daemon/its VMs.
Preparation/download receipts are under
`build/kubescape-dev/native-vm/`; keys and configuration remain in the owned private
`/tmp/opencode/` directory. Host forwards are loopback-only, guest root stays in
the VM, and actual PID/BTF/cgroup/kernel support must pass before native tests.
The guest booted with pinned SSH identity and verified initial PID namespace,
cgroupv2, vmlinux BTF and BPF/uprobe kernel options. Guest-only K3sv1.35.8+k3s1 reached
Ready with acquired node UID and CA-verified API access; actual kernel is
`7.0.0-34-generic`. Receipts are `local-guest-ready.json` and `k3s-ready.json`.
The initial bundled QEMU lacked user networking and failed before creating a VM;
its original failure is retained. QEMU10.2.1 and its runtime libraries were instead
checksum-verified and unpacked into the owned private directory, not host-installed.
No existing MicroK8s service, Multipass VM or cluster was touched. A Ready environment
is not final D success and does not waive SDK review or ordinary image qualification.

## Checked disposable-resource retirement

While required release review remains unavailable, both owned disposable resources
are retired rather than left consuming memory indefinitely. Before guest retirement,
the original node UID/Ready and preflight evidence were rechecked and retained.
Only the guest's owned K3s unit was stopped; private QMP quit and an acquired kernel
PIDfd prove the exact QEMU process physically exited. The original executable and
raw process-birth identity were checked. `native-vm/retire-result.json` is successful
with no cleanup errors; private disks/keys and all receipts remain retained.

Before kind retirement, the original Docker container ID, cluster label, node UID
and independent empty ContainerProfile inventory were rechecked. Scoped kind
deletion removed the exact node/server; the recorded foreign container IDs all
remain present. `local-cluster/retire-result.json` proves no owned cluster containers
remain. The initial read-only cleanup attempt used the ambient missing Podman socket
and failed before mutation; that receipt stays separate. Successful cleanup uses
the same explicit Docker endpoint as original creation/deployment, without changing
host configuration or bypassing any permission error. Images/evidence are retained.

No native capture acceptance occurred in the VM. After release handoffs exist,
provisioning/revalidation must acquire new actual owner/node identities; retired
Ready receipts are history, not a currently available acceptance environment.

## Subsequent whole-feature review

Read-only review of all45committed paths through node-agent
`72034696017511e05f44d115e4081f39821cbd4d` reproduced three Important defects with
actual failing host controls, not native acceptance:

- [Watcher registration](../internals/backlog/issue-20261019-170844-967-deliver-hotspot-container-lifecycle-events-after-w.md)
  after startup misses add/remove events; unregister does not remove the frozen callback.
- [Late admission failure](../internals/backlog/issue-20261019-170845-352-retain-late-hotspot-admission-failures-in-the-acti.md)
  can lose active main delivery of original EACCES after the real10s admission/2s
  cleanup bounds, while a physical owner remains pending.
- [Publication after final save](../internals/backlog/issue-20261019-170845-693-reject-unsaveable-hotspot-publication-after-the-fi.md)
  can return success while the actual disk-queue/storage payload contains no stacks.

Those original failures remain historical evidence. The initial current-main
correction `2fd19c150b7ad916ff9d25bf370d9b2256528c4d` passed525connected race
parent/subtest status records, but its complete53-file review was **SpecFAIL /
QualityNOTAPPROVED**: I1 still replayed Add after a pre-registration Remove while
the pinned collection retained its pointer for another held subscriber. I2's late
original-cause delivery and I3's final-save linearization passed that review;
the old525receipt does not prove the missing interleaving or become retrospective
approval. Original failing proofs, fixture failures and earlier temp-path failures
remain retained separately.

The scoped correction is local, unpushed node-agent
`fd108c3b410dbc06e53984e311b6abc9b9a0fe29`, sole parent `2fd19c15`, tree
`709498df846316ed786cc78717dbbfb7f6d02348`. Its five existing paths repair the shared
watcher boundary: private current membership, registration, selected initial replay
and live submissions order under the existing mutex/queue; no raw stale collection
replay or command wrapper mutex remains. Genuine assembled watcher/pinned-collection
barriers cover both filter modes and a positive initial-replay control, including
the pre-delete window. The original assembled proof goes3fail→3pass. Unregister,
selectors/host exemption, independent callbacks and baseline delivery remain intact.
No API, time/buffer budget, dependency or authority expansion follows.

Scoped re-review is **I1ADDRESSED / SpecPASS / QualityAPPROVED**,0findings, retaining
the whole-feature review for unchanged code. All three node issues are locally
addressed but remain **open**, not published/merged. Actual final-source host gates:
529connected and30covering race PASS records including parents/subtests,
0failures/0skips, actual RuntimeController/late-EACCES/terminal/Completed-host controls,
ordinary binary and both guards pass (legacy guard explicitly from Go's cache).
Publication after the final real-container snapshot rejects without mutation;
accepted-before-final data reaches the actual disk queue/storage interface, and
the monitored Completed-host exception remains. Queue acceptance is **not** server
acknowledgment. Strict/observed guidance is corrected without relaxing production
pins or false policy/global-completeness limits.

The complete53-path feature is based on actual upstream main
`02f0bf7b21fd98da0d6f6935d3c49503d6978738`, still current in the retained remote
snapshot. It excludes the preserved dirty Tier E prototype. Go1.27.0 development
checks select immutable SDK `ed0c3f9628c55ae80b61eab3741a154ab709220d`, subtree
`7472a715b8aab93fb71c5e0ff9f1c55b3de73fe2`, and local storage
`4c2e1cf1b64b6169b02b7a9b0ceacafd985aad5f`, tree
`6c1a84c22005bd123aaddb99a1cefbd255790cae`. SDK's30files/26Go files match current
`7a714bfe` except its newly added LICENSE is absent from this old immutable input;
no new released SDK was selected. Node `go.mod`/`go.sum` still equal upstream and
contain no new SDK requirement. These are explicit workspace inputs, not release
pins or GOWORK=off evidence. Original N/S HEADs, raw indexes, refs/prototype and
immutable exports remain protected; the local feature ref is separate from HEAD.

Local execution evidence is in `.superpowers/sdd/2026-12-16-shared-hotspot-forward/`:
`task-C2-report.md`, the original failed `task-C2-review.md`, approved
`task-C2-fix1-review.md`, `C2-fix1-candidate-committed.json`, covering/connected race,
selected-module/guard/binary receipts and original C1 proofs. Local SHAs/source
paths identify unpublished inputs; no working GitHub blob links are presumed.

The [shared SDK/host contract](2026-10-18-hotspot-observed-profile-contract.md#shared-contract-and-ownership)
and [current release limits](2026-10-19-hotspot-bridge-release.md#current-shared-review-checkpoint)
retain independent host ownership, locally packaged existing license terms and
separate exact-source release approval/real pins. The final configured M/portal
source gate passed tree `6d3b6a639ff5314a60e050a097f98122d91f0944`; its cache/skip,
operator-configuration and unresolved ENOSYS qualifications are recorded there,
not claimed as node native acceptance. Real released SDK/storage origin/sums and
workspace-off gates, full privileged upstream CI/security, ordinary/derived images
and actual native/K8s persisted-profile acceptance remain unproved. Upstream
publication access is READ; `Pilleo/node-agent` does not exist. No fork/new native
environment is approved or assumed. Future identities/handoffs remain null/false/
blocked; no ready C handoff, image, remote PR update or default-branch merge occurred.
