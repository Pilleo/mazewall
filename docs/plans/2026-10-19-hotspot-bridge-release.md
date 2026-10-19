# HotSpot bridge v0.2.0 release and provider handoff

The operator requested "continue all the way" after the explicit SDK-publication
gate was explained. This authorizes progressing the new SDK release and remaining
integration through the existing review path. It does not bypass required review,
select unknown deployment credentials/context, discard prototype changes or move
an existing immutable tag. No default-branch push or broad orchestrator dispatch.

[Owning issue](../internals/backlog/issue-20261019-071745-155-release-hotspot-bridge-v0-2-0-and-pin-actual-provi.md).
[Admitted CLI evidence](2026-10-19-hotspot-observed-admission.md).

## Exact release scope

- Module `github.com/Pilleo/mazewall/hotspot-bridge`, Go1.26, no external dependency.
- New subdirectory tag `hotspot-bridge/v0.2.0`; never change `v0.1.0`.
- Relative to v0.1.0, the functional delta is only `bundle/verify.go` and its
  `bundle/observed_test.go`; README describes format3 and the release boundary.
  No decoder semantics, native ABI, buffer budget or strict-profile expansion.
- SDK code at admitted commit93837185 has already passed isolated archived-source
  race tests (452test/subtest passes,0skips) and dependency isolation. Preparation
  changes documentation only; compare release archive code bytes to those tested
  before reusing that evidence. A local tag is not remote publication.

## Ordered gates

1. Commit scoped release documentation with the unchanged configured hook; preserve
   operator Codanna bytes. Record the exact candidate commit and archive inventory.
2. Publish the feature branch for provider/release review, not the default branch.
   Preserve the cumulative provider scope and independent SDK delta in the PR.
   Keep merge/review under the existing serial control plane; no unattended daemon
   that may dispatch unrelated backlog items. Required review precedes publication.
3. After review passes, publish only the new SDK tag at the reviewed source.
   Resolve it remotely using Go with GOWORK=off and record actual origin, Sum and
   GoModSum. Never infer or prefill a future checksum from a local workspace.
4. Pin those real release bytes in both native owners, regenerate actual prepared
   modfiles and rebuild strict/observed bundles twice without a local SDK workspace.
   Repeat actual admitted CLI/SDK and strict native/root gates for that graph.
5. Generate a consumable provider.json only from the actual released module,
   selected artifacts and qualified report. No assumed storage release/image.
   Preserve remaining live-storage, prototype-baseline and cluster prerequisites.

Documentation consulted: Context7 Go1.26.0 module tag/download behavior and
[official Go publishing guidance](https://go.dev/doc/modules/publishing).

**Current status:** release preparation; no v0.2.0 publication or provider handoff
is claimed by this document. Actual review/publication receipts must follow.
