---
title: "Reject contradictory OCI descriptor media before digest deduplication"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "hotspot-bridge/bundle/verify.go"
target_symbols:
  - "OCIGraph"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Reject contradictory OCI descriptor media before digest deduplication

**Context:**
PR542 whole-SDK review reproduced order-dependent acceptance of a missing reachable layer when one digest has manifest and opaque descriptors. This is incomplete graph validation, not a trusted-manifest SHA bypass. The shared verifier serves MazeWall tooling and node-agent; the Kotlin counterpart needs its own regression.

**Needed:**
1. Add both descriptor orders, same-media shared-blob positives and missing or unlisted child negatives; reject conflicting effective media before deduplication without changing budgets.

**Local evidence / remaining gate:**
A1 locally repaired Go and Kotlin before deduplication: SDK `efc09330`, collector
`a956c433`, retained in current SDK `7a714bfe6ca514679c9997f4e734f6398f2f1f7d` and
collector `1f89ea42829efff873b11a80590542e6f593e19e`. Actual semantic RED/GREEN,
16-case Go matrix/full bundle race and Kotlin17selected/16pass/1optional-native
skip are independently SpecPASS/QualityAPPROVED. Both conflicting orders reject;
same-media sharing and existing child/path/hash/budget checks remain. Current
committed31-file SDK archive passes offline607race PASS records,0failures/0skips;
configured combined source tree `6d3b6a639ff5314a60e050a097f98122d91f0944` also passes.
See the [current release checkpoint](../../plans/2026-10-19-hotspot-bridge-release.md#current-shared-review-checkpoint)
for receipts and cache/configuration/native limits. This issue stays open for
control-plane handling: fixes are unpushed, published v0.1.0 unchanged; exact-source
release approval and real released-graph/native qualification remain gates.

The subsequent PR542/543 review found a separate selected-root boundary gap:
Go ignored matching top-level descriptors' size/media; Kotlin already rejected
wrong sizes and conflicting references but admitted a sole opaque-media root.
The scoped follow-up now feeds every selected Go root reference through existing
graph validation and requires supported document-root media in both loaders.
Public Go Verify regressions cover strict/observed profiles, index/manifest roots,
optional Go media/size defaults, same-media sharing, size/media contradictions and
old trusted-pin rejection. The real Gradle HotSpotAssetsTest class covers the
opaque-root rejection and existing sharing/size/conflict controls while preserving
opaque config/BPF children. Focused checks and the full SDK race suite pass;
the prior configured source-gate receipt above is not qualification of these new
bytes. Independent scoped review and the final configured gate remain pending,
alongside the unchanged publication, pins and released-graph/native gates.

---
<!-- id: issue-20261019-163139-343-reject-contradictory-oci-descriptor-media-before-d  file: issue-20261019-163139-343-reject-contradictory-oci-descriptor-media-before-d.md -->
