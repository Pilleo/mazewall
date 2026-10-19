# HotSpot companion: approved local baseline and first host unit

## Authority and preservation

After requesting continuation through integration, the operator explicitly selected
**Use current baseline** for the dirty Tier E prototype at node-agent
`1c4fb954cdd51edb98a2ad4100be392cd806e44e`. The promise is additive development,
recoverable custody, and exclusion of all pre-existing work from our commits.

`build/kubescape-dev/node-companion/baseline-custody.json` records a private archive,
working/index binary patches and hashes for all34original dirty/untracked files.
Only the two intended config files receive additive changes; every other original
file remains byte-identical. Reverse-applying our own config additions in an owned
verification copy reproduces the original hashes for those two files as well.
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

## Remaining work and independent gates

C2 supervision, container targeting/queueing, atomic evidence publication,
storage-capability negotiation, profile-manager wiring and C5 disabled-baseline
acceptance remain to implement. C1 is not a running node-agent companion or a
native/cluster qualification result. Actual SDK/storage publication and reviewed
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

SDK review is [PR540](https://github.com/Pilleo/mazewall/pull/540), now ready for
review. No review/check success, v0.2.0 tag, module checksum or ready provider
handoff is fabricated while that gate is outstanding.
