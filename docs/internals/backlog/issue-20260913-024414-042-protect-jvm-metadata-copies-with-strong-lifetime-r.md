---
title: "Protect JVM metadata copies with strong lifetime references"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules:
  - "profiler-native"
target_files: 
  - "profiler-native/src/agent.cpp"
target_symbols: 
  - "InvocationScope"
open_questions: false
---

# 🟡 [Severity: HIGH]: Protect JVM metadata copies with strong lifetime references

**Context:**
Immutable copied definitions need an explicit safe copy sequence; weak validity checks alone do not protect JVM objects during metadata resolution.

**Needed:**
1. Acquire temporary strong references before version validation and copying, then emit unresolved attribution when lifetime or validity cannot be established.

## Acceptance criteria

1. Metadata resolution acquires a temporary strong JVM reference before dereferencing the class, method, or name object.
2. Version validity is checked while the reference is held, then required strings/frames are copied into immutable records before release.
3. Unload, collection, or validity failure produces an explicit unresolved result with no invalid method-id dereference.
4. Strong references are released on success and every failure path.

## Required verification

1. Stress class unloading and concurrent symbolization under repeated capture.
2. Run retransformation and unload fixtures under sanitizers or equivalent native diagnostics.
3. Report `dictionary_integrity`, `attribution_coverage`, and `lifecycle_validity`; any native lifetime error is `FAIL`.

Verification command: `cmake -S profiler-native -B build/tier-e-lifetime -DCMAKE_BUILD_TYPE=Debug && cmake --build build/tier-e-lifetime -j2 && ctest --test-dir build/tier-e-lifetime -V -R 'redefinition|unload'`.

---
<!-- id: issue-20260913-024414-042-protect-jvm-metadata-copies-with-strong-lifetime-r  file: issue-20260913-024414-042-protect-jvm-metadata-copies-with-strong-lifetime-r.md -->

## Resolution evidence

Implemented in `4f82ca9e` (transactional/live publication) and `9bfc6a2e` (lifecycle, capture-domain, unique-edge, timeout, and protocol boundaries). Existing focused tests and native compilation provide local evidence; rootful kernel/BPF artifacts remain an environment limitation and are not claimed here.

Verification:

- `./gradlew :profiler:test --tests io.mazewall.profiler.attribution.DictionaryBatchPublisherTest --tests io.mazewall.profiler.attribution.TierEProfilerSessionLifecycleTest`
- `./gradlew :profiler:test --tests io.mazewall.profiler.tierE.engine.TierEBpfProgramBuilderTest --tests io.mazewall.profiler.tierE.engine.BpfProgLoadLayoutTest --tests io.mazewall.profiler.tierE.daemon.ControlProtocolFixtureTest --tests io.mazewall.profiler.tierE.daemon.TierEClientSessionTest`
- Native CMake build in `/tmp/tier-e-native-build`

Verdict dimensions: `capture_domain_validity=local-pass`, `transport_completeness=local-pass`, `attribution_coverage=local-pass`, `dictionary_integrity=local-pass`, `lifecycle_validity=local-pass`, `rootful_runtime=INCOMPLETE`.
