---
title: "Investigate retained all-target 19-of-20 loadTemplate attribution"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: [profiler, profiler-native]
target_files:
  - "profiler-native/src/agent.cpp"
  - "profiler-native/CMakeLists.txt"
  - "profiler-native/include/open_boundary_recorder.hpp"
  - "profiler-native/tests/open_boundary_recorder_test.cpp"
  - "profiler-native/tests/OpenBoundaryWorkload.java"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEBpfProgramBuilder.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEbpfEngine.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEBoundaryDiagnostics.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierEProfilerSession.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierEKotlinDaemon.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/TierEBpfProgramBuilderTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/TierEPreGateBytecodeExecutionTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/TierEBoundaryDiagnosticsTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/attribution/TierEProfilerSessionLifecycleTest.kt"
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierEAllTargetDaemonIntegrationTest.kt"
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierEMissingOpenDiscriminatorIntegrationTest.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/controller/TierEFixtureLifecycle.kt"
  - "profiler/build.gradle.kts"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEFixtureLifecycleTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEA4DiagnosticSupport.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEA4DiagnosticSupportTest.kt"
  - "profiler/src/test/resources/tier-e-a4-tracer.py"
target_symbols:
  - "TierEAllTargetDaemonIntegrationTest"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Investigate retained all-target 19-of-20 loadTemplate attribution

**Context:**
The fresh A4 [full-build log](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/a4-full-build-format.log) records exit 1 after 16m42s at `:profiler:rootfulContainerTest`: 26 executed, one failed, none skipped. The failed `[1] group` expected 20 `openat` observations with `loadTemplate` frames but got 19; `readConfiguration` remained 20. The current terminal reported 183 resolved (usual 184), kernel active 183 and ring loss 0; all-target attribution was `INCOMPLETE` and policy eligibility false.

The [retained sample](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/a4-full-build-format-runs/a4c-group-38c6cd94-d6d4-43f1-8fbf-e265e475b7b5/) preserves the closed MZSE dictionary, event JSONL, terminal, metrics, commands and recovery evidence. Preserve failure owners and data; no cleanup is authorized. This is new retained evidence, not an established cause or a diagnosis of the historical A3 missing path 43. [A3 remains open](issue-20261019-084828-251-investigate-tier-e-delayed-artifact-coverage-dropp.md).

**Needed:**
1. Causally join retained bytes with the native, BPF and consumer paths; declare exact additional paths only if the investigation requires them.
2. Add a deterministic regression if supported by the evidence; fix the root cause once only with proof. Do not guess a fix or weaken thresholds, timeouts or expected counts, or retry to claim green.
3. Preserve the 20+20 requirement and run final focused, kernel and full gates. Do not claim qualification without actual qualifying counts.

**Confirmed bounded diagnostic scope:**
The operator approved the [single-arm diagnostic design](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/a4-diagnostic-design.md) and confirmed its [four-task implementation plan](../../superpowers/plans/2026-11-03-tier-e-missing-open-discriminator.md), including the exact additional native/test/build files listed above. This authorizes default-off native/BPF boundary receipts, their host tests and independent reviews, and at most one newly owned ptrace/strace-observed group arm after all normal gates and readiness checks. Diagnostic storage is bounded to 6 MiB and oracle output to 16 MiB; preserve defaults, protocols, counts, host settings and the original retained owners. The dedicated task cannot be reached or armed by an ordinary build. Any gate/readiness failure stops before ARM; an attempted invocation is not retried. A clean arm is **INCONCLUSIVE**, not a fix or A4 acceptance; A3 remains unresolved. The issue stays open for causal evidence and orchestrator-managed integration.

Controller readiness clarification: unchanged closed-gate polling already produces diagnostic records. Verify empty initialization before attachment, retain all polling records, and use a bounded private live-control receipt through existing STATUS handling (wire reply unchanged) before ARM; do not reset counters or claim current emptiness. The [clarification and scoped host checks](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/a4-diagnostic-readiness-clarification.md) reopen four paths already listed above, not additional files or privileged actions. Final complete/quiescent evidence remains necessary for absence claims; the single attempt and failure-retention boundaries are unchanged.

**Consumed attempt:** The [result](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/a4-discriminator-result.md) and [closure review](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/a4-discriminator-attempt-closure-review.md) record an incomplete pre-ARM failure: ATTACH reported BPF_PROG_LOAD ENOSPC, with insufficient evidence to identify the cause. No admissible join or 20+20 execution occurred. The single invocation is consumed; historical A4 remains FAILED and A3 unresolved. Future work requires separate scope and authorization. Follow-ups: [program-load evidence](issue-20261104-061859-942-retain-attributable-bpf-program-load-failure-evide.md), [retention collision](issue-20261104-061908-412-fix-diagnostic-target-registration-retention-colli.md), and [configuration-cache serialization](issue-20261104-061917-747-fix-tier-e-diagnostic-configuration-cache-serializ.md).

---
<!-- id: issue-20261103-190440-355-investigate-retained-all-target-19-of-20-loadtempl  file: issue-20261103-190440-355-investigate-retained-all-target-19-of-20-loadtempl.md -->

**Current availability (2026-11-16):** The operator approved retiring only the consumed one-shot A4 LIVE task and its two dedicated Python resources. This supersedes the earlier “no cleanup authorized” limit solely for that narrow retirement; the live acquisition path is unavailable. Keep this issue open and preserve its historical failure evidence and original 20+20 requirement. `TierEA4DiagnosticReplay` and shared replay/provenance support remain. This retirement does not establish a cause, qualify G4, complete A4, or resolve A3; no follow-up live attempt is authorized. See [retirement issue 079](issue-20261116-072155-079-retire-consumed-tier-e-a4-one-shot-live-diagnostic.md).
