---
title: "Decide required CI treatment of hardware-specific native performance gate"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "ci"
target_modules: [":profiler"]
target_files:
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEBenchmarkPhaseGate.kt"
  - ".github/workflows/ci.yml"
target_symbols:
  - "TierEBenchmarkPhaseGate"
open_questions: true
---

# 🟡 [Severity: MEDIUM]: Decide required CI treatment of hardware-specific native performance gate

**Context:**
PR531 corrected CI run38729302452 now reaches the native phase gate. Retained full/unique verdicts report GetStackTrace medians4309871ns and3515754ns, exceeding unchanged 125-percent historical limits2707762ns and2307381ns. Rootful terminal is INCOMPLETE/policy-ineligible. Original archives remain local; no benchmark publication is needed. Default-branch merge is blocked pending an operator decision on whether this hardware-specific benchmark belongs in required CI. Do not raise limits, fabricate acceptance, or bypass protected checks.

**Needed:**
1. Obtain operator decision about moving the performance benchmark to explicit manual-only execution while retaining build, security and kernel correctness gates, or investigate the measured regression under a scoped performance task.

---
<!-- id: issue-20261015-115813-512-decide-required-ci-treatment-of-hardware-specific-  file: issue-20261015-115813-512-decide-required-ci-treatment-of-hardware-specific-.md -->
