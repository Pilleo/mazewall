---
title: "Bind strict HotSpot terminal evidence to negotiated provider and captured epoch"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: [":profiler"]
target_files:
   - "profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotProtocol.kt"
   - "profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotEvidenceAssembler.kt"
   - "profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotWireMapper.kt"
   - "profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotWireReader.kt"
   - "profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotEvidenceAssemblerTest.kt"
   - "profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotSessionLifecycleTest.kt"
   - "profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotObservedConsumerTest.kt"
target_symbols:
  - "HotSpotProtocol"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Bind strict HotSpot terminal evidence to negotiated provider and captured epoch

**Context:**
Repository-wide review, 2026-11-04: [HS09-02](../reviews/2026-11-04-repository-review-findings.md#hs09-02). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P2 · provenance/identity correctness · reproduced, high confidence · optional Kotlin strict profiler. NEW.**

- **Trigger/evidence:** `HotSpotProtocol.kt:40,57–62,67–76` validates enrichment provider pins only for observed3. The strict mapper merely copies provider strings and requires nonempty frames for a correlated result (`HotSpotWireMapper.kt:22–35`); it never validates `hotspot_identity`. The accumulator's strict path likewise has no captured/terminal epoch check (`HotSpotEvidenceAssembler.kt:110–145`). Go has both checks at `hotspot-bridge/wire/decoder.go:239–252` and `identity.go:6–27`.
- **Reproduction:** Starting with one valid schema2 session, change only the enrichment `patch_sha256` to 64 nines, or change checked terminal epoch 2 to 4 while captured epoch remains 2. Both reach Kotlin `Completed` with `event_correlated` evidence. Go rejects the equivalent rows as terminal-provider mismatch and captured/checked-epoch mismatch respectively.
- **Effect:** Correct lifecycle provenance does not protect individual strict stack evidence: contradictory provider data or evidence from another epoch is presented as correlated success. Current incomplete-policy guards prevent this from becoming an enforcement certificate, but diagnostic attribution is wrong.
- **Smallest complete fix:** Apply negotiated provider checks to strict enrichment too. Retain bounded captured strict epoch/profile scalars per occurrence, validate stable/checked native-visible identity and require matching epochs for correlated strict success. Do not impose strict epoch semantics on observations or legacy schema1 replay; zero is a valid even epoch.
- **Regression/qualification:** Port the Go strict identity matrix to the Kotlin live interpreter, including zero epoch, wrong provider, absent/odd/mismatched epoch, unavailable/skipped terminals and retention-zero paths. Keep the same corpus through both language consumers.
- **Mapping/dependencies:** NEW relative to resolved `issue-20261102-032138-671...`, whose context already noted strict enrichment bypassing `validateProvider` but whose accepted repair intentionally covered aliases only. Related strict epoch backlog concerns native capture/metadata correctness, not this missing host validation. This host fix cannot qualify native metadata lifetime.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

## Validated original-occurrence follow-up — 2026-11-05

[Recursive review D-03](../reviews/2026-11-05-state-complexity-review.md):
`HotSpotEvidenceAssembler.kt:110–145` requires original-event-first and captures
CPU/correlation only for observed3. Strict2 can admit terminal-before-event with
matching final counts. Its mapper at `HotSpotWireMapper.kt:11–19` ignores CPU and
sequence; prefix checking in `HotSpotWireReader.kt:95–99` is not full identity
validation. This is malformed-stream admission by source analysis, not a proven
malformed native producer or policy-eligibility bypass.

- In strict2 as well as observed3, construct one bounded immutable original
  occurrence binding before terminal admission: session/ID, CPU, positive sequence,
  target generation, captured correlation and profile-specific epoch/identity.
  Require exact `session:cpu:sequence` identity; CPU zero and stable epoch zero
  remain valid. Keep schema1 out-of-order diagnostic replay explicitly separate.
- Use a private `AwaitingTerminal(binding) | Paired(binding)` or equivalent single
  guarded owner. Keep validity/accounting evidence even with zero payload
  retention, and reuse it for this issue's existing provider/epoch validation.
  A wire witness proves consistency, not native metadata lifetime.
- Feed terminal-first and single-field CPU/sequence/ID contradictions through
  the real strict interpreter and complete summary/EOF/exit/join lifecycle;
  require no healthy Completed outcome at normal and zero retention.
- Preserve valid pre-ready original→terminal traffic, observed equivalents,
  CPU0/epoch0 and schema1 terminal-first replay. The canonical producer already
  emits each original before its terminal; no global terminal ordering is needed.
  Existing pre-ready and final-summary owners remain separate. Run focused host
  protocol/lifecycle checks and the implementation gate; native production
  behavior qualification remains additional, not established by this review.

---
<!-- id: issue-20261103-235250-806-bind-strict-hotspot-terminal-evidence-to-negotiate  file: issue-20261103-235250-806-bind-strict-hotspot-terminal-evidence-to-negotiate.md -->
