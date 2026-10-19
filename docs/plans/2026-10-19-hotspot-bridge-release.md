# HotSpot bridge v0.2.0 release and provider handoff

The operator requested "continue all the way" after the explicit SDK-publication
gate was explained. This authorizes progressing the new SDK release and remaining
integration through the existing review path. It does not bypass required review,
select unknown deployment credentials/context, discard prototype changes or move
an existing immutable tag. No default-branch push or broad orchestrator dispatch.

[Owning issue](../internals/backlog/issue-20261019-071745-155-release-hotspot-bridge-v0-2-0-and-pin-actual-provi.md).
[Admitted CLI evidence](2026-10-19-hotspot-observed-admission.md).

## Split review routing (2026-10-19)

The cumulative PR540 review is superseded by the stacked
[SDK PR542](https://github.com/Pilleo/mazewall/pull/542),
[collector/qualification PR543](https://github.com/Pilleo/mazewall/pull/543), and
[integration-records PR544](https://github.com/Pilleo/mazewall/pull/544).
Required exact-SDK release review now belongs to PR542; splitting the reviews
does not grant approval, publish a tag, change source bytes or waive any gate below.

## Exact release scope

- Module `github.com/Pilleo/mazewall/hotspot-bridge`, Go1.26, no external dependency.
- New subdirectory tag `hotspot-bridge/v0.2.0`; never change `v0.1.0`.
- Relative to v0.1.0, the SDK delta includes format3 bundle verification/Expected
  binding and the bounded schema3 `Lifecycle.ErrnoCauses` DTO, validation,
  error-joining, deep-copy and regression tests added by PR540 follow-up Task2,
  plus the subsequently reviewed OCI media and canonical-provider guards below.
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
  That exact28-file inventory is historical; the current30-file SDK below is not
  byte-identical and cannot inherit its exact-source claim.
  Old helper hashes/native receipts do not qualify the changed Go helper; fresh
  released-graph build/runtime gates below still apply. A local tag is not remote publication.

## Ordered gates

1. Record the exact candidate commit and archive inventory; preserve operator
   configuration bytes. Run the unchanged complete configured root/portal hook once
   after combined source/docs finish. Focused host checks do not replace that gate.
2. Publish the feature branch for provider/release review, not the default branch.
   Preserve the cumulative provider scope and independent SDK delta in the PR.
   Keep merge/review under the existing serial control plane; no unattended daemon
   that may dispatch unrelated backlog items. Required review precedes publication.
3. Obtain owner confirmation of the SDK license and include the approved legal
   material in the actual selected module; the unchanged missing-notice check must
   remain fail closed. After exact-source release review passes, publish only the
   new SDK tag at that reviewed source.
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

**Historical PR540 status:** release preparation; no v0.2.0 publication or provider
handoff was claimed. Tasks1–2 have task-scoped independent PASS/APPROVED
reviews; local Task3 verification/documentation is not the separate exact-source
release/control-plane approval. Actual review/publication receipts must follow.

**Task3 checkpoint, pending at authoring:** independent broad code/documentation
review is PASS/APPROVED, not release approval. The original full hook failed;
the subsequent exact focused diagnostic passed1/0/0 in2.208s, with real ENOSYS
cleanup errors and the initiating pre-direct-connect delay still unproved. One
controller-authorized unchanged full commit-hook checkpoint is pending; its actual
result/SHA belong to the local Task3 report, not a predicted success in these docs.

## Current shared-review checkpoint

- Local SDK tip `ed0c3f9628c55ae80b61eab3741a154ab709220d` has30files, subtree
  `7472a715b8aab93fb71c5e0ff9f1c55b3de73fe2`; collector tip
  `b45333b52629bd061d7870830aa1165e1750b8ee` imports it and retains the Kotlin fixes.
  The [shared ownership contract](2026-10-18-hotspot-observed-profile-contract.md#shared-contract-and-ownership)
  applies to both consumers without merging their host lifecycles or Gadget graphs.
- A1 actually reproduced and fixed OCI media contradictions in Go **and Kotlin**.
  The Go16-case matrix and complete bundle race suite pass; Kotlin assets select17,
  pass16 and skip the optional installed-native case. A2 fixes shared provider-key
  aliases: all98matrix cases pass, the complete SDK race run records607passing
  test/subtest statuses with zero skips, and Kotlin's exact-key control passes7/7.
  These task-scoped reviews/checks are not exact-source release approval.
- The [OCI](../internals/backlog/issue-20261019-163139-343-reject-contradictory-oci-descriptor-media-before-d.md)
  and [provider](../internals/backlog/issue-20261019-163139-601-reject-provider-key-aliases-that-override-canonica.md)
  issues remain open for control-plane handling despite the local repairs.
  [License approval](../internals/backlog/issue-20261019-163139-921-include-approved-bridge-license-material-in-select.md)
  is still missing: actual published v0.1.0 and candidate modules lack legal
  material and fail the selected-module notice check. A README declaration is not
  owner confirmation, and v0.1.0 cannot be edited.
- [Whole-node review](2026-10-19-hotspot-companion-local-evidence.md#subsequent-whole-feature-review)
  reproduced three integration defects; they are planned, not fixed. All six new
  review issues remain open; backlog totals are152active (151open/1deferred),714resolved.
- The combined configured root/portal hook remains **pending**. Known operator
  overrides are preserved, not claimed equivalent to pristine configuration.
  No changed-helper native qualification, published v0.2.0/sums, ready A/B/C handoff,
  node-current-main compatibility, native environment readiness or default-branch
  merge follows from these local results. Historical full-hook/native receipts
  above retain only their original exact-input scope.
