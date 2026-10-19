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
- Relative to v0.1.0, the SDK delta includes format3 bundle verification/Expected
  binding and the bounded schema3 `Lifecycle.ErrnoCauses` DTO, validation,
  error-joining, deep-copy and regression tests added by PR540 follow-up Task2.
  Decoder failure semantics therefore change; native ABI, buffer budgets, strict
  schema2 emission/admission and policy eligibility do not expand.
- Provider Task1 (`5b9d85e7`) also suppresses failed owned summaries before the
  existing error wrapper. Task2 (`be3987a6`) transports primary plus retained
  EPERM/EACCES without manufacturing success. These change Go collector bytes,
  not the native patch/BPF fingerprint.
- The admitted93837185 archive's452passes remain historical, not an inventory-
  equivalence claim for this changed SDK. Candidate SDK subtree
  `c2d85585e636daf56748519264196a00888c0503` at
  `be3987a686dfbe49262b294c7edb253e9e8d2172` has its own complete standalone race
  evidence and dependency-isolation proof; see
  [failure-fidelity evidence](2026-10-19-pr540-failure-fidelity-evidence.md).
  Compare any later release archive to that exact28-file inventory before reuse.
  Old helper hashes/native receipts do not qualify the changed Go helper; fresh
  released-graph build/runtime gates below still apply. A local tag is not remote publication.

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
is claimed by this document. Tasks1–2 have task-scoped independent PASS/APPROVED
reviews; local Task3 verification/documentation is not the separate exact-source
release/control-plane approval. Actual review/publication receipts must follow.

**Task3 checkpoint, pending at authoring:** independent broad code/documentation
review is PASS/APPROVED, not release approval. The original full hook failed;
the subsequent exact focused diagnostic passed1/0/0 in2.208s, with real ENOSYS
cleanup errors and the initiating pre-direct-connect delay still unproved. One
controller-authorized unchanged full commit-hook checkpoint is pending; its actual
result/SHA belong to the local Task3 report, not a predicted success in these docs.
