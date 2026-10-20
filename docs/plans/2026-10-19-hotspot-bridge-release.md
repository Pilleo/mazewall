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
  That exact28-file inventory is historical; the current31-file SDK below is not
  byte-identical and cannot inherit its exact-source claim.
  Old helper hashes/native receipts do not qualify the changed Go helper; fresh
  released-graph build/runtime gates below still apply. A local tag is not remote publication.

## Ordered gates

1. Record the exact candidate commit and archive inventory; preserve operator
   configuration bytes. The unchanged complete configured root/portal hook passed
   the combined source tree recorded below. Documentation-only follow-up needs
   reference/backlog/whitespace and exact source-identity checks, not another build.
   Any executable/configuration change needs its own appropriate gate.
2. Publish the feature branch for provider/release review, not the default branch.
   Preserve the cumulative provider scope and independent SDK delta in the PR.
   Keep merge/review under the existing serial control plane; no unattended daemon
   that may dispatch unrelated backlog items. Required review precedes publication.
3. Retain the documented delegated check of the owner's existing Apache grant and
   its local SDK material below; this is not fresh verbal/title certification or
   publication approval. The selected-module missing-notice check stays fail closed.
   After separate exact-source release/control-plane approval, publish only the new
   SDK tag at that reviewed source; unchanged v0.1.0 still lacks the material.
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

- Local, unpushed SDK tip `7a714bfe6ca514679c9997f4e734f6398f2f1f7d` has31files,
  subtree `57ef06ba7f99602c8ddbd1a3accddfaf9e9b1c11`; collector tip
  `1f89ea42829efff873b11a80590542e6f593e19e` imports it and retains the Kotlin fixes.
  SDK parent `ed0c3f9628c55ae80b61eab3741a154ab709220d` already contains both
  security fixes; the sole new SDK entry is LICENSE. Its other30files/26Go files
  are unchanged. Collector A3 adds only that license and both native owners'
  private notice-helper extraction/regressions, without merging their graphs.
  The [shared ownership contract](2026-10-18-hotspot-observed-profile-contract.md#shared-contract-and-ownership)
  applies to both consumers without merging their host lifecycles or Gadget graphs.
- A1 actually reproduced and fixed OCI media contradictions in Go **and Kotlin**.
  The Go16-case matrix and complete bundle race suite pass; Kotlin assets select17,
  pass16 and skip the optional installed-native case. A2 fixes shared provider-key
  aliases: all98matrix cases pass, the complete SDK race run records607passing
  test/subtest statuses with zero skips, and Kotlin's exact-key control passes7/7.
  A1/A2/A3 reviews are SpecPASS/QualityAPPROVED, not exact-source release approval.
  A3's committed SDK-only31-file archive also passes offline, GOWORK=off Go1.26.8
  race checks:607PASS records including parent/subtests,0failures/0skips. Its
  offline `go list -deps` selects only its own bundle/wire packages and stdlib,
  zero external dependencies, without a profiler sibling.
- The [OCI](../internals/backlog/issue-20261019-163139-343-reject-contradictory-oci-descriptor-media-before-d.md)
  and [provider](../internals/backlog/issue-20261019-163139-601-reject-provider-key-aliases-that-override-canonica.md)
  issues remain open for control-plane handling despite the local repairs.
  [Local license packaging](../internals/backlog/issue-20261019-163139-921-include-approved-bridge-license-material-in-select.md)
  now uses the operator's “please check yourself” delegated check of owner Pilleo's
  project-wide Apache declaration (`c1e83ee34b78464ba85d10d84bc260361e912d47`,
  current line `5af41a04639f299cbd8609ed2644b9215ca3ca1b`), with no SDK override
  found. This packages existing terms locally, not a new license choice or legal/
  title guarantee. `hotspot-bridge/LICENSE` is the unchanged official Apache2.0
  text,11358bytes, SHA256
  `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`.
  No holder or root LICENSE is invented. Both native owners' actual selected-SDK
  missing-license RED becomes6/6GREEN,0skips, in separate548-module workspaces;
  copied-source notice removal still rejects. Published v0.1.0 remains unchanged
  and fails that check; actual released-graph strict distribution remains blocked.
- [Whole-node review](2026-10-19-hotspot-companion-local-evidence.md#subsequent-whole-feature-review)
  and the scoped replay correction now approve the53-path local current-main
  development feature `fd108c3b410dbc06e53984e311b6abc9b9a0fe29`. All three defects
  are locally addressed, not published/merged; the old failed I1 review is history,
  not retroactively PASS. All six issues stay open; totals152active
  (151open/1deferred),714resolved.
- The final unchanged configured root/portal hook **passed** combined tree
  `6d3b6a639ff5314a60e050a097f98122d91f0944` (docs `20dbaeeb` + collector `1f89ea42`):
  full build plus portal integration/unit,7m50s,243actionable tasks
  (31executed/212up-to-date), no degraded flag. Available XML has3615tests,
  0failures/0errors/42skips;68reports were written during the run, not all fresh.
  Source/modes/custody stayed stable. Known operator overrides are preserved,
  not pristine/default equivalence. The externally reappeared1596-byte CLAUDE
  file (SHA256 `82a8a25352180860e78feaa3937a883955dc650efaf5723cdbf45f8a51d1f52c`)
  remains untracked beside the original staged deletion/raw index; its existing
  collector blob is not a task change. Writer attribution remains unproved.
  Earlier20dbaeeb/20m38sPASS is a separate pre-A3 receipt, not this source gate.
- Real ENOSYS child-shutdown errors remain: three socket/directory cleanup pairs,
  final full log lines750/771/786/807/821/842. Neither physical endpoint retirement
  nor the original cold-network root cause is proved repaired. This configured
  source gate is not changed-helper native, released-provider or K8s acceptance.
- The retained read-only publication snapshot still has public draft PR542/543/544
  at `6b4d6baf`/`f7c30f15`/`d614ae6c`; local fixes are unpushed. Remote master
  `b5dd8e6e` and fast-master `16fab8e6` are unchanged. Immutable v0.1.0 tag
  `b615271cc836d80352e66d67d96b99f5e2afcdb8` peels to
  `1a6d58d2c73f5b089588add7364bf8d7913c5604`; v0.2.0 is absent. SDK/storage
  releases, real sums/pins, images and ready A/B/C/native handoffs remain
  null/false/blocked. No new native environment, fork, publication or merge is
  authorized by these local implementation approvals.

Local execution receipts in `.superpowers/sdd/2026-12-16-shared-hotspot-forward/`:
`license-research/result.json`, A3's reports/archive/race receipts,
`B2-sdk-release-review-preparation.json`, `final-source-gate.json`,
`final-source-verification-summary.json`, full/hook logs and captured XML,
`task-C2-fix1-review.md`, and `publication-readiness-remote-snapshot.json`.
These are contextual local evidence, not shipping-plan links or release approvals.
