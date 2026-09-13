---
title: "Decouple dictionary frame interning from streaming transport serialization"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "profiler/src/main/kotlin/io/mazewall/profiler/attribution/StackTraceDictionary.kt"
target_symbols: 
  - "StackTraceDictionary"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Decouple dictionary frame interning from streaming transport serialization

**Context:**
StackTraceDictionary mixes frame interning with wire transport serialization, making it difficult to enforce bounded pending dictionary queues, watermarking, and isolated JVMTI agent benchmark harnesses.

**Needed:**
1. Separate StackTraceDictionary into an in-memory frame intern table and a bounded dictionary stream encoder

**Resolution:**
- Kept `StackTraceDictionary` purely responsible for in-memory canonical frame interning and resolution.
- Introduced `StackDictionaryStreamEncoder` with bounded queueing (`maxPendingEntries`), watermarking, and binary encoding compatible with `AgentDefinitionFile`.
- Added isolated unit tests in `StackDictionaryStreamEncoderTest` verifying roundtrip decode via `AgentDefinitionFile.decode`, watermark progression, and bounded-queue drop behavior under memory pressure.
- Passed full `:profiler:check` and `detektWarningRatchet`.

---
<!-- id: issue-20260912-224648-231-decouple-dictionary-frame-interning-from-streaming  file: issue-20260912-224648-231-decouple-dictionary-frame-interning-from-streaming.md -->
