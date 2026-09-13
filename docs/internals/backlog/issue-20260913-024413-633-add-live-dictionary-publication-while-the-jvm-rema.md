---
title: "Add live dictionary publication while the JVM remains active"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules:
  - "profiler"
target_files: 
  - "profiler/src/main/kotlin/io/mazewall/profiler/attribution/TierEProfilerSession.kt"
target_symbols: 
  - "TierEProfilerSession"
open_questions: false
---

# 🟡 [Severity: HIGH]: Add live dictionary publication while the JVM remains active

**Context:**
The default unload-time definition file does not prove that a live external consumer can resolve an attributed operation before JVM termination.

**Needed:**
1. Define and fixture an optional live dictionary producer while preserving the existing file source and v2 wire format.

## Acceptance criteria

1. A running JVM publishes a definition before unload when a live producer is enabled.
2. An external consumer resolves an attributed operation while the workload JVM remains alive.
3. The unload-time definition file remains the default compatibility source and the v2 record format is unchanged.
4. Live publication loss produces an explicit incomplete transport/dictionary verdict and never fabricates a resolved frame.

## Required verification

1. Run a native workload that remains alive after an attributed operation and assert live definition receipt before exit.
2. Exercise producer disconnect and bounded pending memory, retaining dictionary bytes and watermark transitions.
3. Report `transport_completeness`, `attribution_coverage`, `dictionary_integrity`, and `lifecycle_validity` separately.

Verification command: `./gradlew :profiler:integrationTestFreshJvm --tests io.mazewall.profiler.TierEEndToEndIntegrationTest` in the rootful fixture environment.

---
<!-- id: issue-20260913-024413-633-add-live-dictionary-publication-while-the-jvm-rema  file: issue-20260913-024413-633-add-live-dictionary-publication-while-the-jvm-rema.md -->
