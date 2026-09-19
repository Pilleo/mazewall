---
title: "Establish allocation-safe evidence for remaining Tier E collector overhead"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - "profiler"
target_files:
  - "docs/internals/evidence/tier-e-integration-readiness-audit-20260919.json"
  - "scripts/run_tier_e_trustworthy_benchmark.sh"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierEProfilerSession.kt"
target_symbols:
  - "TierEProfilerSession"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Establish allocation-safe evidence for remaining Tier E collector overhead

**Context:**
In-process JFR and per-event allocation counters both perturb exact Tier E attribution. The readiness audit cannot classify remaining conversion and collector allocation as unavoidable until an external or separately instrumented methodology preserves the controlled workload.

**Needed:**
1. Keep production Tier E code free of diagnostic counters. The rejected counter split showed that even primitive allocation sampling changes the measured outer allocation boundary.
2. Do not use in-process JFR for exact Tier E workloads. The retained rootful trial caused JFR-owned JVM activity to appear as nine syscalls outside the controlled workload stack.
3. Evaluate an external profiler or a separately built diagnostic runtime whose activity cannot share the profiled process or its selected `sys_enter` domain. `perf` is available for CPU sampling but cannot by itself attribute JVM allocation classes.
4. Define the diagnostic artifact schema: profiler identity/version, target PID namespace, source fingerprint, kernel, JDK, profiling overhead, raw profile, five rootful fork outputs, and all five Tier E verdict dimensions.
5. First validate that the diagnostic setup preserves the existing workload checksum, selected decoded evidence, zero unexpected workload-stack events, no ring loss, and `COMPLETE` Tier E terminal evidence. Only then use it to classify allocation.
6. Attribute conversion, dictionary join staging, unique-edge gating, retained result creation, and publication separately. Do not sum nested allocation boundaries.
7. Update the readiness audit only when the method establishes whether each residual is immutable evidence, acceleration state, or avoidable allocation.

**Acceptance criteria:**
1. Five rootful production forks retain `PASS` workload correctness and `COMPLETE` Tier E session evidence with the diagnostic enabled.
2. Diagnostic output identifies allocation sources without adding events to the controlled workload or modifying decoded evidence.
3. The evidence includes a paired no-diagnostic control and records whether any residual is conclusively unavoidable.
4. Rootless Podman is explicitly `INCOMPLETE`; it is not evidence for this issue.

---
<!-- id: issue-20260919-120622-331-establish-allocation-safe-evidence-for-remaining-t  file: issue-20260919-120622-331-establish-allocation-safe-evidence-for-remaining-t.md -->
