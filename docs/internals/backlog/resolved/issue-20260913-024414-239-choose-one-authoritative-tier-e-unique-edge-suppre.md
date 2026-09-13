---
title: "Choose one authoritative Tier E unique-edge suppression boundary"
severity: "HIGH"
status: "resolved"
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

## Resolution evidence

Implemented in `4f82ca9e` (transactional/live publication) and `9bfc6a2e` (lifecycle, capture-domain, unique-edge, timeout, and protocol boundaries). Existing focused tests and native compilation provide local evidence; rootful kernel/BPF artifacts remain an environment limitation and are not claimed here.

Verification:

- `./gradlew :profiler:test --tests io.mazewall.profiler.attribution.DictionaryBatchPublisherTest --tests io.mazewall.profiler.attribution.TierEProfilerSessionLifecycleTest`
- `./gradlew :profiler:test --tests io.mazewall.profiler.tierE.engine.TierEBpfProgramBuilderTest --tests io.mazewall.profiler.tierE.engine.BpfProgLoadLayoutTest --tests io.mazewall.profiler.tierE.daemon.ControlProtocolFixtureTest --tests io.mazewall.profiler.tierE.daemon.TierEClientSessionTest`
- Native CMake build in `/tmp/tier-e-native-build`

Verdict dimensions: `capture_domain_validity=local-pass`, `transport_completeness=local-pass`, `attribution_coverage=local-pass`, `dictionary_integrity=local-pass`, `lifecycle_validity=local-pass`, `rootful_runtime=INCOMPLETE`.

Additional verification evidence:

- `./gradlew :profiler:test :profiler:compileIntegrationTestKotlin` passed.
- `./gradlew :profiler:integrationTest` passed on the available host.
- `ctest --test-dir /tmp/tier-e-native-build --output-on-failure` passed 10/10 native fixtures.
