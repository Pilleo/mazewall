---
title: "Retain attributable BPF program load failure evidence"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEbpfEngine.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/BpfProgLoadRequestEncoder.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEBpfProgramLoadFailureEvidence.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEBoundaryDiagnostics.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/BpfProgLoadLayoutTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/BpfProgLoadRequestEncoderTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/TierEBpfProgramLoadFailureEvidenceTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/TierEBoundaryDiagnosticsTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEA4DiagnosticSupport.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEA4DiagnosticSupportTest.kt"
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierEAllTargetDaemonIntegrationTest.kt"
  - "docs/internals/designs/profiler/tier-e-design.md"
  - "docs/internals/backlog/issue-20261104-061859-942-retain-attributable-bpf-program-load-failure-evide.md"
  - "docs/superpowers/plans/2026-11-03-tier-e-missing-open-discriminator.md"
target_symbols:
  - "TierEbpfEngine"
  - "BpfProgLoadRequestEncoder"
  - "BpfProgLoadLayout"
  - "TierEBpfProgramLoadFailureEvidence"
  - "BoundaryLayout"
  - "TierEA4DiagnosticSupport"
  - "TierEAllTargetDaemonIntegrationTest"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Retain attributable BPF program load failure evidence

**Context:**
The consumed A4 attempt failed pre-ARM on ATTACH BPF_PROG_LOAD errno 28 ENOSPC. Only the verifier summary processed 4001 insns (limit 1000000) survived; the fixed 64 KiB log is retained as a trimmed C-string without raw bytes or size receipt. The failed program and completeness are unknown; do not infer disk, map, memlock, or instruction exhaustion. See [result](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/a4-discriminator-result.md), [closure review](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/a4-discriminator-attempt-closure-review.md), [attempt log](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/a4-discriminator-one-attempt.log), and [incident](issue-20261103-190440-355-investigate-retained-all-target-19-of-20-loadtempl.md). This file declaration is a future proposal, not implementation or acquisition authorization; the one attempt is consumed.

**Needed:**
1. First perform offline attribution and design retention of the raw submitted program and bounded verifier-log completeness provenance, with reproducible host regressions, before any separately authorized kernel acquisition.

**2026-11-04 agreed implementation scope (V):** The operator now authorizes repeated evidence-driven tests and qualification; the historical consumed attempt above is unchanged. Sole writer implements these exact fourteen paths in the current checkout; controller coordinates independent review, full build and fresh privileged runs afterward. No native/image rebuild or old evidence mutation. Keep open pending qualification.

Reserve exactly 44,072 incremental diagnostic bytes within 6 MiB: program 8,192, raw log prefix 32,768, metadata 2,048, owned scratch 1,024, extended attr 40. Base 6,270,528; map excess 20,928; admitted map allocation at most 3,691,072. Ordinary request native payload (including unchanged 64 KiB working log) is separately bounded at 73,876, not charged again or described as whole-JVM RSS. Failure artifact at most 43,008 bytes; no mirrors/retries. Default request remains 104 bytes; enabled same-call request is zero-filled 144 bytes with unsigned log_true_size at 140. Prefix truncation/unknown size never establishes complete causal evidence.

Acceptance: compiling behavioral named-load and layout RED, focused failure/ownership/publication/classification/budget/export tests, final profiler host suite, integration compilation, diagnostic wiring, unfiltered ktlint, detekt, SpotBugs and warning ratchet, backlog/reference and whitespace checks. Full/kernel gates remain controller handoff, not a claim from host checks.

**Host implementation evidence (2026-11-04):** Named-load attribution and 104-versus-144 same-call layout failed behaviorally before production edits; stale-budget rejection also failed before the consumer repair. Focused tests passed, then the final `:profiler:test` suite passed 1,665 tests with zero failures/errors and four unrelated opt-in HotSpot skips. `:profiler:compileIntegrationTestKotlin`, `:profiler:verifyTierEA4DiagnosticWiring`, unfiltered `:profiler:ktlintCheck`, `:profiler:detektMain`, `:profiler:spotbugsMain` and root `detektWarningRatchet` passed (profiler: zero added normalized warnings; inventory unchanged). See [the handoff report](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/a4-follow-up-verifier-report.md) for commands, XML, schema and hashes. Issue remains open: independent review, full build and fresh kernel qualification are controller-owned and not yet established by these host results.

---
<!-- id: issue-20261104-061859-942-retain-attributable-bpf-program-load-failure-evide  file: issue-20261104-061859-942-retain-attributable-bpf-program-load-failure-evide.md -->
