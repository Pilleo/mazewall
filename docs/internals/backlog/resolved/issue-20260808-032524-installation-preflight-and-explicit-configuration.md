---
title: "Add Installation Preflight, Effective Configuration and Typed Results"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies:
  - "issue-20260808-032521"
  - "issue-20260808-032522"
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/Platform.kt"
  - "enforcer/src/main/kotlin/io/mazewall/KernelFeatureMatrix.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/ContainedExecutors.kt"
effort: "large"
autonomy: "supervised"
---

# 🟡 [Severity: MEDIUM]: Add Installation Preflight, Effective Configuration and Typed Results

**Context:** Kernel installation is irreversible, but the current high-level call gives no policy-specific preflight or effective-policy receipt. Failure behavior can be selected globally through properties/environment variables and is not visible at the call site. Errors commonly surface as broad state/operation exceptions rather than a typed failed stage with native details.

**Needed:** Add an immutable Mazewall configuration with explicit fail-closed mode, a policy/scope-specific `assess` operation, typed installation exceptions and an `InstallationReceipt`. Assessments must report runtime compatibility, feature probes, outer Seccomp constraints, Landlock support, thread constraints, fallback mode, warnings and expected effective rules without mutating kernel state. Add unit fault-injection tests and kernel integration tests ensuring assessment never silently authorizes bypass and installation receipts reflect observed state.
