---
title: "Harden Tier E framed receive timeouts across partial frames"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules:
  - "profiler"
target_files: 
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierEClientSession.kt"
target_symbols: 
  - "TierEClientSession"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Harden Tier E framed receive timeouts across partial frames

**Context:**
SO_TIMEOUT distinguishes idle waits from partial framed messages, and periodic bytes can evade an idle timeout without a frame deadline.

**Needed:**
1. Add partial-header and partial-payload timeout fixtures with exact decoder-position or terminal-session behavior.

## Acceptance criteria

1. A timeout between frames rechecks shutdown state without corrupting the decoder.
2. A timeout during a partial header or payload either preserves the exact decoder position or terminates the session; it never restarts framing at the next byte.
3. A peer sending periodic bytes cannot evade a separate whole-frame/handshake deadline indefinitely.
4. Hard socket errors retain their existing fail-closed behavior.

## Required verification

1. Add partial-header, partial-payload, idle, periodic-byte, and hard-error fixtures.
2. Retain decoder state, timeout kind, and terminal state in a machine-readable transcript.
3. Report `transport_completeness` and `lifecycle_validity` independently.

Verification command: `./gradlew :profiler:test --tests io.mazewall.profiler.tierE.daemon.TierEClientSessionTest`.

---
<!-- id: issue-20260913-024414-675-harden-tier-e-framed-receive-timeouts-across-parti  file: issue-20260913-024414-675-harden-tier-e-framed-receive-timeouts-across-parti.md -->

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
