---
title: "Extract TierEBpfProgramBuilder from TierEbpfEngine for isolated bytecode verification"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEbpfEngine.kt"
target_symbols: 
  - "TierEbpfEngine"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Extract TierEBpfProgramBuilder from TierEbpfEngine for isolated bytecode verification

**Context:**
TierEbpfEngine embeds over 400 lines of raw eBPF instruction generation and jump offset arithmetic inline with bpf(2) syscalls, preventing non-root unit testing of capture domain logic and syscall filtering.

**Needed:**
1. Extract buildSysEnterProgram and buildMarkerProgram into a dedicated TierEBpfProgramBuilder object

**Resolution:**
- Extracted `buildSysEnterProgram` and `buildMarkerProgram` into `io.mazewall.profiler.tierE.engine.TierEBpfProgramBuilder`.
- Delegated from `TierEbpfEngine.buildProgram` and `TierEbpfEngine.buildMarkerProgram`.
- Added isolated non-root unit tests in `profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/TierEBpfProgramBuilderTest.kt` covering instruction counts, BPF termination opcodes, and emission mode filtering.
- Ratchet verified: `./gradlew check` passed with zero new warnings and valid backlog index.

---
<!-- id: issue-20260912-224645-805-extract-tierebpfprogrambuilder-from-tierebpfengine  file: issue-20260912-224645-805-extract-tierebpfprogrambuilder-from-tierebpfengine.md -->
