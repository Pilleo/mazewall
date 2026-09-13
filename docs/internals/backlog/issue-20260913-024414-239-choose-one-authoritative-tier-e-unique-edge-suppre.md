---
title: "Choose one authoritative Tier E unique-edge suppression boundary"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules:
  - "profiler"
  - "profiler-native"
target_files: 
  - "profiler/src/main/kotlin/io/mazewall/profiler/attribution/AttributionCollector.kt"
target_symbols: 
  - "AttributionCollector"
open_questions: false
---

# 🟡 [Severity: HIGH]: Choose one authoritative Tier E unique-edge suppression boundary

**Context:**
Kernel-side suppression and acknowledgement-based collector suppression can disagree after downstream delivery failure, allowing an edge to be suppressed before its commit meaning is satisfied.

**Needed:**
1. Document and test the authoritative suppression boundary, including failed delivery followed by a repeated edge.

## Acceptance criteria

1. Exactly one layer owns unique-edge commit state; the other layer cannot silently suppress an unacknowledged candidate.
2. The recommended first implementation makes collector acknowledgement authoritative, or documents a complete kernel rollback/idempotence protocol.
3. A failed first delivery permits the repeated edge; only successful acknowledgement suppresses subsequent duplicates.
4. Token ownership remains session-local and foreign, stale, or duplicated acknowledgements are rejected.

## Required verification

1. Test kernel-emitted edge plus failed downstream delivery, repeated edge, acknowledgement, and final suppression.
2. Retain candidate, acknowledgement, and suppression transitions in a machine-readable artifact.
3. Report `transport_completeness`, `attribution_coverage`, `dictionary_integrity`, and `lifecycle_validity` separately.

Verification command: `./gradlew :profiler:test --tests io.mazewall.profiler.attribution.AttributionCollectorTest`.

---
<!-- id: issue-20260913-024414-239-choose-one-authoritative-tier-e-unique-edge-suppre  file: issue-20260913-024414-239-choose-one-authoritative-tier-e-unique-edge-suppre.md -->
