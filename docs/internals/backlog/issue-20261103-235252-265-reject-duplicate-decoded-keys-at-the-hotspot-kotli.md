---
title: "Reject duplicate decoded keys at the HotSpot Kotlin wire boundary"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: [":profiler"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotWireReader.kt"
target_symbols:
  - "HotSpotWireReader"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Reject duplicate decoded keys at the HotSpot Kotlin wire boundary

**Context:**
Repository-wide review, 2026-11-04: [HS09-05](../reviews/2026-11-04-repository-review-findings.md#hs09-05). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P2 · malformed-input/conformance · reproduced, high confidence · optional Kotlin wire admission. NEW.**

- **Trigger/evidence:** `HotSpotWireReader.kt:88–113,116–138` checks depth and then uses `Json.parseToJsonElement`, with no duplicate-key rejection. The exact parser dependency takes the last value. Go scans decoded object keys before typed decoding (`hotspot-bridge/wire/json.go:82–100,272–307`), including escaped/nested duplicates.
- **Reproduction:** Add `"policy_eligibility":true,` immediately before the existing `"policy_eligibility":false` in an observed ready row. Kotlin completes the session; Go rejects `duplicate object key "policy_eligibility"`. The same last-value behavior can hide a contradictory earlier provider pin/PID. Existing case-fold alias tests do not cover duplicate canonical names.
- **Effect:** The two hosts disagree on whether a contradictory identity-bearing stream is admissible. Kotlin loses the earlier member when canonicalizing its raw diagnostics, so this is not faithful preservation of contradictory input. Public policy eligibility remains hardcoded false: no actual policy-promotion bypass is claimed.
- **Smallest complete fix:** Reject duplicate decoded keys at the bounded wire JSON boundary before constructing JsonObject/typed state. Keep genuine unknown extension keys and existing byte/depth limits; add no dependency or alternate permissive fallback.
- **Regression/qualification:** Shared schema2/3 cases for duplicate/escaped/nested provider, target and policy keys in both orders; preserve valid unknown-extension controls and legacy behavior only where explicitly intended. Go's `TestDecoderJSONBoundaries` already supplies starting cases.
- **Mapping/dependencies:** NEW, not a repeat of `issue-20261019-163139-601...` or resolved `issue-20261102-032138-671...` case-fold aliases. Include in the same shared conformance release gate; unrelated incident-demo JSON last-value limits are not a HotSpot wire contract decision.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235252-265-reject-duplicate-decoded-keys-at-the-hotspot-kotli  file: issue-20261103-235252-265-reject-duplicate-decoded-keys-at-the-hotspot-kotli.md -->
