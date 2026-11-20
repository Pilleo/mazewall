---
title: "Bind packaged HotSpot executables to their actual build and source provenance"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: ["issue-20261103-235250-240-verify-materialized-hotspot-dependency-sources-bef"]
component: "profiler"
target_modules: []
target_files:
  - "profiler/hotspot-agentless/internal/tooling/distribution.go"
target_symbols:
  - "release"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Bind packaged HotSpot executables to their actual build and source provenance

**Context:**
Repository-wide review, 2026-11-04: [HS-03](../reviews/2026-11-04-repository-review-findings.md#hs-03). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P2 · confirmed source defect, no current-graph end-to-end reproduction · confidence high · optional native distribution.**

**Trigger:** an operator passes an older/differently built collector to the documented `pack --collector …` command. For release, an otherwise compatible prepared source root exists and the binary's linked module paths overlap that root, but versions/replacements or local overlay bytes differ. This is a build-input mix-up within the documented trusted-operator model; it does not require forging a trusted manifest.

**Evidence:**

- `profiler/hotspot-agentless/internal/tooling/assets.go:158–167` checks only regular-file/executable status of the collector; lines198–205 copy it, and line244 stamps the current `acceptedPatch`, upstream revisions, platform and requested collector/wire version onto it.
- Existing `internal/tooling/assets_test.go:29,63–75` intentionally exercises pack/verify with the literal bytes `ELF fixture`. That supports the source interpretation; this lane did not run that suite.
- `internal/tooling/distribution.go:354–370` verifies the just-stamped manifest, current preparation identity, and presence of Go build information. It does not compare the actual collector path, linked patch, platform or build graph to those claims.
- At `distribution.go:392–431`, modules are matched by `linked.Path` only. License/source selection and the versions/replacements/sums written to provenance come from the **current** graph, without comparing `linked.Version`, `linked.Sum` or `linked.Replace`.
- `distribution.go:473–497` archives current engine/bridge/Gadget/profiler source and emits provenance. The stronger, separate `footprint.go:50–71` checks are not called by pack/release and do not perform this full graph comparison either.
- This is an advertised operator route, not an unreachable helper: `profiler/hotspot-agentless/README.md:124–138` documents direct pack/release and describes corresponding source/selected-module notices.

**Consequence:** a checksum-valid bundle can misstate what executable it contains. A client may subsequently refuse readiness, or an old executable with unchanged protocol/overlay identity may be distributed with source/notices for different linked versions. Archive checksums prove internal byte integrity, not that the packaged source built that binary. This finding does not claim that the documented manifest is a signature or protects against a malicious directory owner.

**Smallest complete correction:** before publication, bind the supplied executable to its actual build identity and expected collector/profile/platform, compare linked module version/sum/replacement identities to the selected source graph, and use that verified selection for notices/source. Local replacements need a content-bound build/preparation receipt (HS-04); path equality is not enough. Reuse the non-size-related footprint checks rather than making footprint-reduction verification a hidden release prerequisite. Reject a mismatch instead of relabeling it.

**Regression:** pack must reject an executable fixture and a real stale/wrong-profile binary; release must reject the same module path at a different version/replacement and a differing local overlay. A genuine matching stripped release must still package and verify successfully. Demonstrate rejection before destination publication.

**Mapping / dependency:** **NEW mismatch gap within completed distribution work**, related to resolved `docs/internals/backlog/resolved/issue-20261005-122913-554-package-verified-hotspot-distribution-provenance-a.md`. The later resolved issue173 genuinely selected and qualified one matching bundle manually; it does not fix generic packaging admission. Do not relabel historical bundles or invalidate genuine exact-byte receipts. HS-04 is needed for full local-replacement/source binding. The current WIP source/prepared-pin mismatch already blocks a normal current release; this finding concerns what happens once the outer preparation/manifest check legitimately matches.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235249-904-bind-packaged-hotspot-executables-to-their-actual-  file: issue-20261103-235249-904-bind-packaged-hotspot-executables-to-their-actual-.md -->
