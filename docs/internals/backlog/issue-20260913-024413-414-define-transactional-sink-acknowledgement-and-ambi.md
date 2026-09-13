---
title: "Define transactional sink acknowledgement and ambiguous delivery recovery"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules:
  - "profiler"
target_files: 
  - "profiler/src/main/kotlin/io/mazewall/profiler/attribution/DictionaryBatchSink.kt"
target_symbols: 
  - "DictionaryBatchSink"
open_questions: false
---

# 🟡 [Severity: HIGH]: Define transactional sink acknowledgement and ambiguous delivery recovery

**Context:**
The architecture review distinguishes local rollback from receiver acceptance and durable persistence; ambiguous partial delivery must be terminal unless the transport is explicitly idempotent.

**Needed:**
1. Specify Committed and RetryableFailure acknowledgement boundaries, batch identity, payload validation, and terminal handling for ambiguous delivery.

## Acceptance criteria

1. `Committed` means the documented receiver acknowledgement boundary has been crossed; local buffering alone is insufficient.
2. `RetryableFailure` is returned only when replaying the identical batch is safe and idempotent.
3. Partial or ambiguous delivery is terminal unless the transport proves session-qualified idempotence and rejects the same identity with different bytes.
4. Queue contents, watermark, and encoded bytes remain unchanged until commit.

## Required verification

1. Add sink tests for accepted delivery, partial write, lost acknowledgement, reconnect, and ambiguous delivery.
2. Retain raw payloads and receiver acknowledgements in a machine-readable artifact.
3. Report `transport_completeness`, `dictionary_integrity`, and `lifecycle_validity` independently; ambiguous delivery is fail-closed.

Verification command: `./gradlew :profiler:test --tests io.mazewall.profiler.attribution.DictionaryBatchPublisherTest`.

---
<!-- id: issue-20260913-024413-414-define-transactional-sink-acknowledgement-and-ambi  file: issue-20260913-024413-414-define-transactional-sink-acknowledgement-and-ambi.md -->

## Resolution evidence

Implemented in `4f82ca9e` (transactional/live publication) and `9bfc6a2e` (lifecycle, capture-domain, unique-edge, timeout, and protocol boundaries). Existing focused tests and native compilation provide local evidence; rootful kernel/BPF artifacts remain an environment limitation and are not claimed here.

Verification:

- `./gradlew :profiler:test --tests io.mazewall.profiler.attribution.DictionaryBatchPublisherTest --tests io.mazewall.profiler.attribution.TierEProfilerSessionLifecycleTest`
- `./gradlew :profiler:test --tests io.mazewall.profiler.tierE.engine.TierEBpfProgramBuilderTest --tests io.mazewall.profiler.tierE.engine.BpfProgLoadLayoutTest --tests io.mazewall.profiler.tierE.daemon.ControlProtocolFixtureTest --tests io.mazewall.profiler.tierE.daemon.TierEClientSessionTest`
- Native CMake build in `/tmp/tier-e-native-build`

Verdict dimensions: `capture_domain_validity=local-pass`, `transport_completeness=local-pass`, `attribution_coverage=local-pass`, `dictionary_integrity=local-pass`, `lifecycle_validity=local-pass`, `rootful_runtime=INCOMPLETE`.
