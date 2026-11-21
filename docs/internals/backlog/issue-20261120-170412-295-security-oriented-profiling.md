---
title: "Security-oriented profiling"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: []
target_files: []
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Security-oriented profiling

**Context:**
Use MazeWall profiling to generate candidate worker policies, then verify them against malicious behavior and realistic application workloads; profiling alone must not be treated as a complete allowlist. (Java4Shell-roadmap P2 #18)

**Needed:**
1. Design the profiling-to-policy-generation toolchain and output schema.
2. Implement the verification harness: run malicious behavior probes and realistic workloads against candidate policies.
3. Define rules for treating profiling output as incomplete and requiring allowlist qualification evidence.
4. Add negative tests: policies that only reflect observed behavior must fail the malicious-behavior probe.
5. Run owning focused checks and the project completion gate.

---
<!-- id: issue-20261120-170412-295-security-oriented-profiling  file: issue-20261120-170412-295-security-oriented-profiling.md -->
