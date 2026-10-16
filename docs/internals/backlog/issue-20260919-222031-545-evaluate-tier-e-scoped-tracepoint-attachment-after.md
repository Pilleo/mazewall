---
title: "Evaluate Tier E scoped tracepoint attachment after node-wide cost measurement"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - "profiler"
target_files: 
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEBpfProgramBuilder.kt"
target_symbols: 
  - "TierEBpfProgramBuilder"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Evaluate Tier E scoped tracepoint attachment after node-wide cost measurement

**Context:**
The global raw sys_enter tracepoint executes its target gate for unrelated host syscalls. A per-platform-TID perf-event attachment must not be implemented or promoted until an isolated rootful host-noise experiment measures material node-wide cost and proves lifecycle-safe event equivalence.

**Needed:**
1. Add a rootful benchmark-only host-noise fixture that holds a Tier E target session open while a pinned unrelated process generates a declared syscall rate. Measure that unrelated process's CPU and throughput with and without the global raw tracepoint attached.
2. Retain target operation-oracle results, decoded event counts, ring loss, marker lifecycle status, and terminal evidence for every measured arm. An empty ring or an unrelated-process result never proves target producer closure.
3. Predeclare the decision gate: proceed to a per-platform-TID perf-event prototype only if the global attachment consumes at least 15% additional unrelated-process CPU at a realistic declared rate, the paired confidence interval excludes zero, and the target arm remains complete with zero unexplained loss.
4. If the gate is not met, retain a `not_justified` evidence artifact, leave the global raw tracepoint unchanged, and document that a scoped attachment would add JVM carrier-TID discovery, thread exit, detach, exec, fork, and PID/TID-reuse correctness obligations.
5. If the gate is met, write a separate approved implementation plan before code. The prototype must preserve the current marker state, ring record layout, loss accounting, collector-authoritative acknowledgement, and fail-closed lifecycle semantics; it is benchmark-only until exact event equivalence and lifecycle evidence pass.

**Acceptance Criteria:**
1. Every evidence artifact records the unrelated workload command, pinned CPUs, kernel, image/source hashes, requested and achieved syscall rate, target event count, and all Tier E verdict dimensions.
2. The experiment reports paired CPU and throughput intervals rather than a single-host ratio.
3. The production global raw tracepoint remains unchanged unless the gate is met and a later, separately reviewed prototype passes exact lifecycle and equivalence checks.
4. All outputs remain `policy_eligibility=false`.

---
<!-- id: issue-20260919-222031-545-evaluate-tier-e-scoped-tracepoint-attachment-after  file: issue-20260919-222031-545-evaluate-tier-e-scoped-tracepoint-attachment-after.md -->
