---
title: "Add AOT Reachability Assessment for Thread-Containment Eligibility"
severity: "ENHANCEMENT"
status: "open"
priority: medium
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "docs/internals/designs/core/security-considerations.md"
effort: "large"
autonomy: "autonomous"
open_questions: false
---

# 🔵 [Severity: ENHANCEMENT]: Add AOT Reachability Assessment for Thread-Containment Eligibility

**Context:** Native Image closed-world analysis and dead-code elimination can reduce the capabilities and code-reuse gadgets present in an AOT image, but they operate on the complete image rather than establishing a per-thread code boundary. Mazewall currently has no automated way to combine reachability inputs, dynamic-feature metadata, native dependencies, syscall observations, and concurrency behavior into a bounded assessment of whether a trusted data-processing entry point is suitable for Tier 2 thread-scoped mitigation. Describing DCE as proof of "RCE-safe" code would be unsound because retained application capabilities, native ACE, shared process memory, incomplete runtime coverage, and incorrectly modeled dynamic features remain outside that claim.

**Needed:** Define a fail-closed assessment format that records the exact entry points, dependency versions, Native Image configuration and reachability metadata, native libraries, observed syscall corpus, and test coverage. Statically reject reachable thread creation, executor submission, process, network, filesystem, class-loading, expression, reflection, method-handle, deserialization, JNI, FFM, `Unsafe`, and native-loading capabilities unless explicitly modeled. Add dynamic tests that detect thread creation and executor handoff while exercising representative and fuzzed inputs. Emit only bounded classifications such as `DATA_ONLY_THREAD_ELIGIBLE`; never emit or imply `RCE_SAFE`. Document that a passing assessment is evidence for trusted-code/hostile-data Tier 2 deployment and does not replace a process boundary for Java RCE or native ACE.

**Architectural Decision:**
1. **Tooling & Task Integration:** Implement as a dedicated Gradle task `:enforcer:assessAotEligibility` that inspects GraalVM reachability configuration (`reachability-metadata.json`, `reflect-config.json`, `jni-config.json`) and scans application bytecode call-graphs.
2. **Schema & Report Format:** Output a structured `ThreadContainmentAssessment.json` reporting `DATA_ONLY_THREAD_ELIGIBLE` or `REJECTED`, logging all reachability violations and observed native boundaries.


