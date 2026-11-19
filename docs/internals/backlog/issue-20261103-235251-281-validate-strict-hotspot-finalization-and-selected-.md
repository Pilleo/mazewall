---
title: "Validate strict HotSpot finalization and selected accounting before completion"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: [":profiler"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotEvidenceAssembler.kt"
target_symbols:
  - "HotSpotEvidenceAssembler"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Validate strict HotSpot finalization and selected accounting before completion

**Context:**
Repository-wide review, 2026-11-04: [HS09-03](../reviews/2026-11-04-repository-review-findings.md#hs09-03). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P2 · false completion/accounting · reproduced, high confidence · optional Kotlin strict profiler. NEW.**

- **Trigger/evidence:** `HotSpotWireMapper.kt:65–84` computes physical drain from booleans only. `HotSpotProtocol.summaryEvent` (`80–86`) checks that derived flag, terminal accounting and top-level `summary.errors`, but not `event_finalization_observation.error`, finalization scope/phase/cause or selected-accounting binding/equations. Full summary validation is called only for observed3 (`HotSpotEvidenceAssembler.kt:74–76,147–158`; `HotSpotObservedWire.kt:118–207`). Go validates these for both schemas (`hotspot-bridge/wire/validation.go:15–68`).
- **Reproduction:** A valid strict fixture still reaches `Completed, physicalDrain=true` after either removing `selected_event_accounting` entirely or adding `"error":"native EACCES"` to `event_finalization_observation` with its other flags unchanged. Go rejects both: selected aggregate/binding absent, or native owners not final.
- **Effect:** A corrupt/contradictory strict summary can produce successful collection with a checked physical-drain claim even though the native finalization object explicitly reports failure. Missing selected proof is not kept unknown/rejected consistently with the shared contract. This does not overcome public `collectionLossComplete=false` or incomplete coverage.
- **Smallest complete fix:** Reuse a common negotiated summary validator for strict2 and observed3, with only genuine profile differences kept separate. Validate original finality errors/scopes, selected target/session/CPU binding, overflow-safe conservation and unknown-global-loss fields before constructing healthy completion. Preserve schema1 diagnostic replay separately.
- **Regression/qualification:** Both-language single-field mutation matrix for nested native error, missing/mismatched selected proof, changed finalization scope/cause, incorrect native-loss equations and unsupported global-completeness claims. Drive the Kotlin lifecycle through healthy summary/EOF/exit/resource joins, not only test a raw parser helper.
- **Mapping/dependencies:** NEW host consumption gap adjacent to open `issue-20261005-193140-042...`. That issue's accepted selected-event baseline and honest unknown all-source inventory must not be mistaken for coverage by this weaker strict consumer. Related failed-summary suppression (`issue-20261019-123201-317...`) changes the producer; it does not make host acceptance of contradictory summaries correct.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235251-281-validate-strict-hotspot-finalization-and-selected-  file: issue-20261103-235251-281-validate-strict-hotspot-finalization-and-selected-.md -->
