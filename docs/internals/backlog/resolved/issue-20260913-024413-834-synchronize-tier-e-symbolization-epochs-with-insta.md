---
title: "Synchronize Tier E symbolization epochs with installed class replacements"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules:
  - "profiler"
  - "profiler-native"
target_files: 
  - "profiler-native/src/agent.cpp"
target_symbols: 
  - "InvocationScope"
open_questions: false

paperclip_issue_id: "8a9b91af-c239-4c06-b061-e2eecf05d725"
paperclip_identifier: "MAZ-1419"
---

# 🟡 [Severity: HIGH]: Synchronize Tier E symbolization epochs with installed class replacements

**Context:**
A ClassFileLoadHook notification precedes replacement installation, so a monotonic epoch alone does not prove that captured frames belong to the installed class version.

**Needed:**
1. Define the replacement transition protocol and exercise capture during a paused transformation with old frames still active.

## Acceptance criteria

1. The epoch transition has a defined relationship to replacement installation, not merely hook notification.
2. Captures during an unproven transition are unresolved or causal-uncertain and cannot be published under the replacement epoch.
3. Old active frames and new invocations remain distinguishable across retransformation.
4. Logical stack bytes remain stable for equivalent installed versions and distinct for non-equivalent versions.

## Required verification

1. Pause `ClassFileLoadHook` between notification and installation while workload threads capture old and new frames.
2. Assert no cross-version cache reuse and retain before/transition/after dictionary records.
3. Report `dictionary_integrity`, `attribution_coverage`, and `lifecycle_validity` independently.

Verification command: `cmake -S profiler-native -B build/tier-e-epoch -DCMAKE_BUILD_TYPE=Debug && cmake --build build/tier-e-epoch -j2 && ctest --test-dir build/tier-e-epoch -V -R redefinition`.

---
<!-- id: issue-20260913-024413-834-synchronize-tier-e-symbolization-epochs-with-insta  file: issue-20260913-024413-834-synchronize-tier-e-symbolization-epochs-with-insta.md -->

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
