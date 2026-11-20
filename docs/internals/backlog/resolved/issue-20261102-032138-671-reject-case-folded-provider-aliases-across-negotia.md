---
title: "Reject case-folded provider aliases across negotiated HotSpot wire rows"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotWireReader.kt"
target_symbols:
  - "HotSpotWireReader"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Reject case-folded provider aliases across negotiated HotSpot wire rows

**Context:**
Current Kotlin provider decoding accepts correct canonical name/gadget_revision/profiler_revision/patch_sha256 plus an extra case-folded alias, contrary to the documented shared Go/Kotlin boundary. Exact-key checks still reject a wrong canonical pin, so no pin override is demonstrated. Strict schema2 enrichment bypasses validateProvider, so a validateProvider-only fix is incomplete. Preserve strict2/explicit observed3 negotiation, schema1 diagnostic replay, genuine unknown extensions and raw JSON. Source investigation and connected callers are recorded in /tmp/opencode/profiler-resume-dfx7y2wp/provider-alias-investigation.md.

**Needed:**
1. First reproduce canonical-plus-alias acceptance, then reject only case-folded aliases of the four canonical provider keys on lifecycle and enrichment paths for schemas2/3, retaining unknown-extension raw bytes and existing type/pin checks.

## Resolution — 2026-11-02

The shared wire reader rejects aliases of the four canonical provider keys on
schema 2/3 lifecycle and enrichment rows, including strict enrichment. Genuine
unknown extensions, raw evidence, schema 1 replay, negotiation and canonical pin
checks are preserved. This closes a malformed-wire contract mismatch; no pin
override was demonstrated.

The new regressions failed on actual alias acceptance before the fix. The seven
focused classes passed 47 tests; after test-only formatting, all four new tests
and profiler test-source lint passed. Independent review found no actionable
findings. The unchanged full build and portal gate then passed: 233 tasks in
13m 1s with configuration-cache reuse.

Evidence: `/tmp/opencode/profiler-resume-dfx7y2wp/provider-alias-fix.md`,
`provider-alias-review.md`, `provider-alias-format-check.json`, and
`completion-gate-formatted-buvjg_wv/result.json` in that evidence directory.

---
<!-- id: issue-20261102-032138-671-reject-case-folded-provider-aliases-across-negotia  file: issue-20261102-032138-671-reject-case-folded-provider-aliases-across-negotia.md -->
