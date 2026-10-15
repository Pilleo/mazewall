---
title: "Decide required CI treatment of hardware-specific native performance gate"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "ci"
target_modules: [":profiler"]
target_files:
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEBenchmarkPhaseGate.kt"
  - ".github/workflows/ci.yml"
target_symbols:
  - "TierEBenchmarkPhaseGate"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Decide required CI treatment of hardware-specific native performance gate

**Context:**
PR531 corrected CI run38729302452 now reaches the native phase gate. Retained full/unique verdicts report GetStackTrace medians4309871ns and3515754ns, exceeding unchanged 125-percent historical limits2707762ns and2307381ns. Rootful terminal is INCOMPLETE/policy-ineligible. Original archives remain local; no benchmark publication is needed. Default-branch merge is blocked pending an operator decision on whether this hardware-specific benchmark belongs in required CI. Do not raise limits, fabricate acceptance, or bypass protected checks.

**Needed:**
1. Obtain operator decision about moving the performance benchmark to explicit manual-only execution while retaining build, security and kernel correctness gates, or investigate the measured regression under a scoped performance task.

## Operator decision and implementation

On 2026-10-15 the operator explicitly approved removing the benchmark job from
required PR CI while preserving build/security/kernel correctness gates and
continuing their repairs. Removed `tier-e-rootful` from the PR/push workflow;
the installed CLI and unchanged performance thresholds remain available for
explicit local runs. No benchmark data publication or replacement cloud workflow
was added. The existing host quality, dependency-state, configuration-cache,
kernel/assembly, mutation, security and demonstration checks remain in place.

The CI workflow also retains JUnit XML diagnostics for actual correctness
failures, rather than relying on abbreviated console exceptions. YAML structure
and retained commands are verified at `run-61c91b26391b`.

---
<!-- id: issue-20261015-115813-512-decide-required-ci-treatment-of-hardware-specific-  file: issue-20261015-115813-512-decide-required-ci-treatment-of-hardware-specific-.md -->
