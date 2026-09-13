---
title: "Integrate bounded dictionary streaming watermarking and explicit incomplete-tail handling"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - "profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/attribution/StackDictionaryStreamEncoder.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/attribution/AttributionCollector.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/attribution/StackDictionaryStreamEncoderTest.kt"
target_symbols:
  - "StackDictionaryStreamEncoder"
  - "AttributionCollector"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Integrate bounded dictionary streaming watermarking and explicit incomplete-tail handling

**Context:**
During live workloads, definitions emitted from the native agent must stream with bounded memory under high dictionary volume. Unresolved tails (where definitions were dropped due to queue saturation or crash) must be explicitly marked as incomplete runs with failure status `DICTIONARY_UNRESOLVED`, and unique-edge deduplication in eBPF must guarantee that suppression occurs only after successful first edge emission.

**Needed:**
1. Connect `StackDictionaryStreamEncoder` into `AttributionCollector` or session runner to enforce bounded memory for pending dictionary entries.
2. Verify that any unresolved stack trace IDs at stream termination surface as explicit `ResolutionStatus.DICTIONARY_UNRESOLVED` and mark the profile run incomplete.
3. Add unit test:
   - First unique-edge emit fails then repeats: later duplicate edge emission is allowed only after successful first emit.
   - Dictionary pressure with long-running workload: bounded memory for pending dictionary entries and explicit incomplete-tail handling.

---
<!-- id: issue-20260912-232409-587-integrate-bounded-dictionary-streaming-watermarkin  file: issue-20260912-232409-587-integrate-bounded-dictionary-streaming-watermarkin.md -->
