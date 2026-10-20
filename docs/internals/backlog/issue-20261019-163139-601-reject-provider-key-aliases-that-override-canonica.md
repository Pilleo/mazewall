---
title: "Reject provider key aliases that override canonical HotSpot pins"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "hotspot-bridge/wire/json.go"
target_symbols:
  - "requiredRecordFields"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Reject provider key aliases that override canonical HotSpot pins

**Context:**
PR542 review reproduced successful start, ready and enrichment consumption with a wrong canonical provider pin overwritten by an uppercase alias accepted by encoding/json. Preserve Provider comparability and unknown raw extensions.

**Needed:**
1. Add both-schema all-provider-field alias/order regressions, reuse canonical-key validation on lifecycle and evidence providers, require no Capture on contradiction.

**Local evidence / remaining gate:**
A2 SDK `ed0c3f9628c55ae80b61eab3741a154ab709220d` rejects provider aliases at the
shared raw boundary before typed decoding; current `7a714bfe` differs only by LICENSE.
Real RED/GREEN covers98leaves (both schemas/all four fields/three placements/both
orders), preserving canonical/wrong-canonical/unknown-extension controls and sticky
empty Capture on rejection. Kotlin's unchanged-production exact-key control passes7/7,
0skips. Reviews are SpecPASS/QualityAPPROVED. The committed31-file SDK archive
passes offline607race parent/subtest PASS records,0failures/0skips; final configured
combined tree `6d3b6a639ff5314a60e050a097f98122d91f0944` passes with explicit cache/
configuration limits. Provider remains comparable; schema2 absence, APIs and budgets
are unchanged. See [current release evidence](../../plans/2026-10-19-hotspot-bridge-release.md#current-shared-review-checkpoint).
Issue remains open/unpublished: unchanged v0.1.0 is not repaired by local receipts;
release approval, real pins/sums and released-provider/native gates still apply.

---
<!-- id: issue-20261019-163139-601-reject-provider-key-aliases-that-override-canonica  file: issue-20261019-163139-601-reject-provider-key-aliases-that-override-canonica.md -->
