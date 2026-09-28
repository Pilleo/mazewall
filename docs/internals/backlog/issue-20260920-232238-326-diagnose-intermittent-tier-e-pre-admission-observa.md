---
title: "Diagnose intermittent Tier E pre-admission observation gaps under sustained load"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "docs/internals/evidence/tier-e-invocation-witness-10k-stop-20260928/README.md"
  - "docs/internals/evidence/tier-e-invocation-witness-10k-stop-20260928/manifest.json"
  - "docs/internals/evidence/tier-e-invocation-witness-10k-stop-20260928/SHA256SUMS"
  - "docs/internals/evidence/tier-e-invocation-witness-10k-stop-20260928/pilot-pass.tar.gz"
  - "docs/internals/evidence/tier-e-invocation-witness-10k-stop-20260928/raw-control.tar.gz"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/TierEInvocationWitnessConservation.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/stress/TierEInvocationWitnessConservationTest.kt"
  - "docs/internals/evidence/tier-e-invocation-marker-join-pilot-stop-20260928/README.md"
  - "docs/internals/evidence/tier-e-invocation-marker-join-pilot-stop-20260928/manifest.json"
  - "docs/internals/evidence/tier-e-invocation-marker-join-pilot-stop-20260928/SHA256SUMS"
  - "docs/internals/evidence/tier-e-invocation-marker-join-pilot-stop-20260928/raw-pilot.tar.gz"
  - "profiler-native/CMakeLists.txt"
  - "profiler-native/src/agent.cpp"
  - "profiler-native/include/invocation_fd_recorder.hpp"
  - "profiler-native/tests/invocation_fd_recorder_test.cpp"
  - "profiler-native/tests/invocation_fd_file_test.cpp"
  - "profiler-native/tests/InvocationFdWorkload.java"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierEOptions.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/TierEInvocationFdArtifact.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/TierEInvocationWitnessComparison.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/TierEInvocationWitnessCpuEvidence.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/TierEInvocationWitnessEvidenceArtifact.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/TierEInvocationWitnessOptionsTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/TierEInvocationWitnessBytecodeTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/TierEInvocationWitnessMapReaderTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/stress/TierEInvocationFdArtifactTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/stress/TierEInvocationWitnessComparisonTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/stress/TierEInvocationWitnessCpuEvidenceTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/stress/TierEInvocationWitnessEvidenceArtifactTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthyInvocationWitnessVerifierTest.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthyInvocationWitnessVerifier.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/TierEKernelSmoke.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/TierEWorkloadLaunch.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/stress/TierEWorkloadLaunchTest.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEbpfEngine.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEBpfProgramBuilder.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEInstalledProgramIdentity.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEBpfProgramInfoReader.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEBenchmarkAuditMapReader.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEBpfMapAllocator.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEPreGateAuditCount.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEPreGateAuditMap.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/TierEAdmissionAuditSummary.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/TierEPreGateEvidenceArtifact.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/TierEDeclaredIdManifestArtifact.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/TierEProgramMissComparison.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/TierEBpfProgramBuilderTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/TierEPreGateBytecodeExecutionTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/TierEInstalledProgramIdentityTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/TierEBpfProgramInfoReaderTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/BpfProgLoadLayoutTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/stress/TierEAdmissionAuditSummaryTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/stress/TierEPreGateEvidenceArtifactTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/stress/TierEDeclaredIdManifestArtifactTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/stress/TierEProgramMissComparisonTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthyResultVerifierTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthyV5EvidenceTest.kt"
  - "config/detekt/warnings/profiler.txt"
  - "docs/internals/designs/profiler/tier-e-testing-migration.md"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/TierEMatchedBenchmark.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierENodeNoiseProbe.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierENodeNoiseQualification.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierENodeNoiseQualificationTest.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthySettings.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthyGuestCommand.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthyContainerOwner.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthyRunner.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthyResultVerifier.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthyPreGateExport.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthyV5EvidenceVerifier.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthyV5EvidenceVerifier.kt"
  - "docs/internals/evidence/tier-e-clean-node-noise-20260927/README.md"
  - "docs/internals/evidence/tier-e-clean-node-noise-20260927/SHA256SUMS"
  - "docs/internals/evidence/tier-e-clean-node-noise-20260927/manifest.json"
  - "docs/internals/evidence/tier-e-clean-node-noise-20260927/raw-diagnostics.tar.gz"
  - "docs/internals/evidence/tier-e-clean-abba-pre-admission-20260927/README.md"
  - "docs/internals/evidence/tier-e-clean-abba-pre-admission-20260927/SHA256SUMS"
  - "docs/internals/evidence/tier-e-clean-abba-pre-admission-20260927/manifest.json"
  - "docs/internals/evidence/tier-e-clean-abba-pre-admission-20260927/raw-diagnostic.tar.gz"
  - "docs/internals/evidence/tier-e-clean-admission-audit-20260927/README.md"
  - "docs/internals/evidence/tier-e-clean-admission-audit-20260927/SHA256SUMS"
  - "docs/internals/evidence/tier-e-clean-admission-audit-20260927/manifest.json"
  - "docs/internals/evidence/tier-e-clean-admission-audit-20260927/raw-audit.tar.gz"
  - "docs/internals/evidence/tier-e-clean-low32-ingress-20260927/README.md"
  - "docs/internals/evidence/tier-e-clean-low32-ingress-20260927/manifest.json"
  - "docs/internals/evidence/tier-e-clean-low32-ingress-20260927/SHA256SUMS"
  - "docs/internals/evidence/tier-e-clean-low32-ingress-20260927/raw-audit.tar.gz"
  - "docs/internals/evidence/tier-e-v3-predecode-pilots-20260927/README.md"
  - "docs/internals/evidence/tier-e-v3-predecode-pilots-20260927/manifest.json"
  - "docs/internals/evidence/tier-e-v3-predecode-pilots-20260927/SHA256SUMS"
  - "docs/internals/evidence/tier-e-v3-predecode-pilots-20260927/raw-pilots.tar.gz"
  - "docs/internals/evidence/tier-e-clean-armed-predecode-20260927/README.md"
  - "docs/internals/evidence/tier-e-clean-armed-predecode-20260927/manifest.json"
  - "docs/internals/evidence/tier-e-clean-armed-predecode-20260927/SHA256SUMS"
  - "docs/internals/evidence/tier-e-clean-armed-predecode-20260927/raw-pair.tar.gz"
  - "docs/internals/evidence/tier-e-v4-verifier-eligibility-20260927/README.md"
  - "docs/internals/evidence/tier-e-v4-verifier-eligibility-20260927/manifest.json"
  - "docs/internals/evidence/tier-e-v4-verifier-eligibility-20260927/SHA256SUMS"
  - "docs/internals/evidence/tier-e-v4-verifier-eligibility-20260927/raw-verifier.tar.gz"
  - "docs/internals/evidence/tier-e-v4-post-session-guard-20260927/README.md"
  - "docs/internals/evidence/tier-e-v4-post-session-guard-20260927/manifest.json"
  - "docs/internals/evidence/tier-e-v4-post-session-guard-20260927/SHA256SUMS"
  - "docs/internals/evidence/tier-e-v4-post-session-guard-20260927/raw-verifier.tar.gz"
  - "docs/internals/evidence/tier-e-v4-architecture-stop-20260927/README.md"
  - "docs/internals/evidence/tier-e-v4-architecture-stop-20260927/manifest.json"
  - "docs/internals/evidence/tier-e-v4-architecture-stop-20260927/SHA256SUMS"
  - "docs/internals/evidence/tier-e-v4-architecture-stop-20260927/raw-verifier.tar.gz"
  - "docs/internals/evidence/tier-e-v4-reviewed-artifact-verifier-20260927/README.md"
  - "docs/internals/evidence/tier-e-v4-reviewed-artifact-verifier-20260927/manifest.json"
  - "docs/internals/evidence/tier-e-v4-reviewed-artifact-verifier-20260927/SHA256SUMS"
  - "docs/internals/evidence/tier-e-v4-reviewed-artifact-verifier-20260927/raw-verifier.tar.gz"
  - "docs/internals/evidence/tier-e-clean-v4-reviewed-pre-gate-20260927/README.md"
  - "docs/internals/evidence/tier-e-clean-v4-reviewed-pre-gate-20260927/manifest.json"
  - "docs/internals/evidence/tier-e-clean-v4-reviewed-pre-gate-20260927/SHA256SUMS"
  - "docs/internals/evidence/tier-e-clean-v4-reviewed-pre-gate-20260927/raw-pair.tar.gz"
  - "docs/internals/evidence/tier-e-v5-raw-pre-admission-stop-20260928/README.md"
  - "docs/internals/evidence/tier-e-v5-raw-pre-admission-stop-20260928/manifest.json"
  - "docs/internals/evidence/tier-e-v5-raw-pre-admission-stop-20260928/SHA256SUMS"
  - "docs/internals/evidence/tier-e-v5-raw-pre-admission-stop-20260928/raw-verifier.tar.gz"
  - "docs/internals/evidence/tier-e-kernel-7-2-8-recursion-control-20260928/README.md"
  - "docs/internals/evidence/tier-e-kernel-7-2-8-recursion-control-20260928/manifest.json"
  - "docs/internals/evidence/tier-e-kernel-7-2-8-recursion-control-20260928/SHA256SUMS"
  - "docs/internals/evidence/tier-e-kernel-7-2-8-recursion-control-20260928/raw-control.tar.gz"
target_symbols:
  - "TierEKernelSmoke"
open_questions: false
---

# 🟡 [Severity: HIGH]: Diagnose intermittent Tier E pre-admission observation gaps under sustained load

**Context:**
Rootful 1,000 operations/s sustained samples intermittently missed contiguous declared operations
before exact admission with zero ring loss and no marker-loss branch. A same-run, counter-only raw
`sys_enter` denominator reproduced the missing descriptor groups and found no `openat2`
substitution. The benchmark container exposed CPUs `0,2`, while every Tier E perf-event program was
attached only to CPU `0`; pinning the target leader did not pin all JVM carrier threads. The engine
now attaches marker, syscall, exec, and benchmark-audit programs on every allowed online CPU.

**Needed:**
1. Retain a clean, multi-fork rootful sustained confirmation of the all-allowed-CPU attachment fix,
   including the benchmark-only raw denominator as diagnostic provenance. Do not promote a capacity
   ceiling from source-dirty smoke evidence.

**Evidence:**
- Split-privilege coverage qualification on 2026-09-22 retained five 75,000-operation forks
  per flavour in [the split coverage report](../evidence/tier-e-split-coverage-20260922/report.md).
  Both dictionary modes recovered all 72 declared paths in every fork with complete terminal
  evidence; inline exceeded its bounded native snapshot size and remained incomplete. This
  run had attachment audits disabled, so it qualifies declared-path recovery but **does not**
  close the pre-admission diagnostic question in this issue.
- Coverage-discovery follow-up: `TierECoverageOracleTest` verifies declared frequencies,
  singleton placement, retry/acknowledgement semantics, wrong attribution, and missing-path
  verdicts. `scripts/run_tier_e_coverage_benchmark.sh` saves a separate coverage comparison;
  its coverage verdict never upgrades incomplete operation accounting. The completed five-seed
  matrix is retained in [the coverage report](../evidence/tier-e-coverage-discovery-20260921/report.md):
  both dictionary modes recovered 72/72 paths in all forks with zero missing expected operations
  and zero ring loss. Both inline modes failed terminal publication at the 64 MiB encoded-size
  bound and remained INCOMPLETE. This issue remains open: the new corpus, disabled audits and
  corrected affinity do not establish the cause of every previous discrepancy.
- The coverage harness now launches the workload JVM pinned from startup and records effective
  affinities. The older leader-only `taskset -pc` operation was insufficient to establish the
  Java workload thread's CPU. Prior performance measurements are not automatically comparable.
- Clean rootful development evidence at `33ee8ffe59e36ac90dd2fe67186969f96151f639` is retained at
  `/tmp/tier-e-admission-audit-development-20260921`. Five 60-second 500/s forks per dictionary
  mode completed with `30600/30600` operation-oracle, raw-entry, and post-marker-admission counts
  in every arm and zero ring loss. This satisfies the requested clean multi-fork 500/s confirmation
  and did not reproduce the prior gap. The issue remains open until the higher-rate capacity and
  host-noise evidence required by the related CPU-global sampling issue is retained.

---
<!-- id: issue-20260920-232238-326-diagnose-intermittent-tier-e-pre-admission-observa  file: issue-20260920-232238-326-diagnose-intermittent-tier-e-pre-admission-observa.md -->

### 2026-09-22 split-privilege matched and lifecycle evidence

Retained artifact: `docs/internals/evidence/tier-e-split-matched-20260922/README.md`, with raw fork/negative-fixture archives and checksums. Exact commands: `bash scripts/run_tier_e_split_matched_benchmark.sh`, `node scripts/test_verify_tier_e_split_operations.mjs`, `./gradlew :profiler:test`, and `ctest --test-dir /tmp/jseccomp-profiler-native-build --output-on-failure`. Five rotated forks per mode completed for baseline, four Tier E flavours, and USER_NOTIF with/without stacks. Full-stream dictionary and inline each recovered 2,600/2,600 declared operations in all five short forks; USER_NOTIF matched 2,600/2,600 in all ten forks. The benchmark-only collector manifest is session-bound and independent of the target's denominator. Stale attach identity, simulated active-session incarnation change, and delayed closed artifact have retained rootful fixtures. The earlier long 72-path dictionary coverage passed; inline long-session coverage remains `INCOMPLETE` at the native 64 MiB budget.

Verdict for this *short* matched workload: `capture_domain_validity=PASS` for selected `sys_enter`, `transport_completeness=PASS`, `attribution_coverage=PASS`, `dictionary_integrity=PASS`, `lifecycle_validity=PASS`, `runtime_overhead=UNVERIFIED`, `policy_eligibility=false`. `calibration_target=unmet`: baseline batches were below 1 ms and the common count cannot be raised enough without exhausting bounded inline retention. The historical 25% performance gate is not passed by these timings. Real host PID reuse and a decision-quality all-seven-mode duration remain unqualified. This entry does not close the intermittent pre-admission issue.

### 2026-09-22 calibrated follow-up

A second seven-mode, five-rotated-fork rootful matrix used the saved `calibration_cpu_heavy` profile with the **same** 200-operation batches and post-syscall application CPU work. Artifact: `docs/internals/evidence/tier-e-split-calibrated-20260922/README.md`. `calibration_target=met`: baseline median 109.7 ms; all modes stayed below 15 seconds per batch. Both full-stream Tier E transports recovered 2,600/2,600 declared operations with complete terminal evidence in every fork; both USER_NOTIF modes independently matched 2,600/2,600 per fork. The measured paired median workload ratios were dictionary/full 1.09, dictionary/unique 1.10, inline/full 1.09, inline/unique 1.14, USER_NOTIF/no-stack 1.53, and USER_NOTIF/stack 1.55. Intervals and raw logs are retained. `runtime_overhead=UNVERIFIED` for the historical 25% release regression gate because no prior release has this exact calibrated workload and collector CPU/RSS, receipt p99, and publication phase time were not captured. Long inline 72-path coverage and real host PID-reuse qualification remain incomplete. This does not resolve the intermittent pre-admission issue.

### 2026-09-22 collector-resource follow-up

A second calibrated five-fork seven-mode matrix retained privileged-collector process CPU and peak RSS after terminal publication: `docs/internals/evidence/tier-e-split-calibrated-metrics-20260922/README.md`. All 35 forks completed, all 20 Tier E terminal reports were complete, and both full-stream transports again recovered exactly 2,600/2,600 declared operations per fork. Dictionary/full-stream collector peak RSS median was 362,952 KiB versus inline/full-stream 546,872 KiB; CPU medians (including setup and terminal work) were 5.41 s and 7.37 s. Wide timing intervals across the two calibrated runs preclude a speed ranking. Receipt p99, publication phase time, a matching prior-release regression baseline, real host PID reuse, and sustained inline 72-path completion remain unqualified. This issue remains open and `policy_eligibility=false`.

### 2026-09-26 node-noise qualification control

A bounded Kotlin one-pair rootful Docker run using the unmodified
10,000-operation, 1,000/s near-capacity schedule and a one-second Java procfs
noise loop was `INCOMPLETE`: full-stream declared 10,000 operations, matched
9,989, missing 11 with no reported ring loss. A fresh no-noise control with
the same target schedule, CPUs and attachment-ready option also failed the
exact oracle (9,994/10,000, missing 6). Both runs retained source volume and
raw logs at `/tmp/tier-e-node-noise-kotlin-v3-1790425247/` and
`/tmp/tier-e-node-control-1790451632/`, respectively. The control shows the
missing-ID outcome is not solely caused by the noise loop. No denominator or
retained-state budget was changed, neither run qualifies a capacity ceiling,
and the diagnostic remains separate from historical shell-loop rates.

### 2026-09-26 opt-in raw-entry/exact-admission audit and Kotlin noise smoke

Sealed receipts: [the Kotlin node-noise diagnostic](../evidence/tier-e-kotlin-node-noise-diagnostic-20260926/README.md).
For one source-pinned audit-enabled control and noisy target, both individual
10,000-operation targets completed. The then-active controller rejected the
noisy target's suffixed `dictionary_full_stream_collector_continuous` variant;
the controller now binds to that completed sustained variant and saves the
observed attachment marker outside the mutable target artifact. A later
one-pair audit-enabled rootful run completed end to end: the observed marker
matched the first profiled full-stream fork, all 10,000 raw `sys_enter` events
and all 10,000 exact admissions were counted, and the operation oracle matched
10,000/10,000 with `policy_eligibility=false`. These runs have **different**
source fingerprints and are not one five-pair result.

In another audited failing noisy target, raw `sys_enter` observed 10,000/10,000
operations, but only 9,994 operations reached exact-admission submission; two
wrong-target rejections were explicitly recorded. Neither the remaining four
missing IDs nor the intermittent default-path shortfall is assigned to a
specific kernel branch on that evidence. A separate unaudited default-path
noisy attempt missed two operations. This issue remains **open** until a
source-pinned, predetermined multi-pair noisy/control result reconciles every
declared operation without audit-dependent qualification. Historical baseline,
resource budgets, and operation denominators remain unchanged.

### 2026-09-27 clean-source default profile and matched controls

[Sealed Docker receipts](../evidence/tier-e-clean-node-noise-20260927/README.md)
pin `b8901ed7`, the same source fingerprint, image/native binary and target
CPUs `0,1`. A predeclared five-pair **unaudited** noisy profile failed closed
on its first target: full-stream matched 10,000/10,000 but the later unique
fork missed IDs `9632`–`9636` (5/10,000), so zero complete pairs were published.
The exact runner/volume were retained for recovery. A separate one-pair
**audit-enabled** noisy diagnostic recovered all 10,000 raw entries and exact
submissions in both forks; a separate unaudited no-noise control matched both
forks. Neither positive result explains the intermittent default-path gap or
permits attributing the five missing IDs solely to noise. This issue stays
**open**; do not reduce the operation denominator, reclassify incomplete
targets as samples, or treat these unequal instruments as paired performance
rates.

**Overlap limitation discovered during closure planning (2026-09-27):**
`TierENodeNoiseProbe.runPairs` samples the unrelated process only after the
first full-stream attachment marker, then `completed` verifies that **that
same first-fork PID is still alive** before waiting for the remaining target
forks. The `b8901ed7` unique-mode IDs `9632`–`9636` were therefore missing in
a later fork, after the three-second noise sample had ended. Its failure is
still a mandatory whole-target correctness failure, but it is **not proof of
noise overlap in unique mode**. A future comparison may claim noisy
full-stream overlap only unless it explicitly observes a separate unique-mode
attachment and overlaps that mode. Preserve full+unique exact-operation
oracles either way; do not infer the missing-ID cause from the first-fork
marker.

**Next predeclared diagnostic (2026-09-27, before acquisition):** Use one
clean committed source snapshot, the checked-in `node-noise.json` schedule,
the exact rootful Docker image/native package, CPUs `0,1` for the profiled
target and CPU `2` for the unrelated process. Select the **unaudited**
10,000-operation/1,000-per-second full+unique target without changing the
denominator. Execute five control/noisy pairs in order
`control,noisy | noisy,control | control,noisy | noisy,control | control,noisy`.
The Kotlin scheduler validates both modes and all 10,000 IDs per target,
requires identical source/image/agent/CPU identities, and writes each pair
immediately after both arms complete. Stop on the first failed arm with an
`INCOMPLETE` terminal, preserve earlier pairs and the exact retained runner,
and report the missing admission boundary rather than rerunning until green.
Only the first full-stream fork has proven noise overlap; no noisy unique
comparison is claimed. An audit-enabled one-pair diagnostic, if subsequently
needed, stays a separate instrumented series and cannot upgrade this default
decision. Store the chosen committed HEAD and exact image digest with the
series plan **before** starting a container.

**Observed AB/BA stop:** The [sealed clean-source series](../evidence/tier-e-clean-abba-pre-admission-20260927/README.md)
reached two fully complete control/noisy pairs and a passing pair-3
no-noise control. The pair-3 noisy full-stream fork was exact, but later
unique mode missed IDs `1777` and `1778`: 9,998/10,000, kernel exact
admissions 29,996/30,000, ring-ready 29,996, ring loss zero. The unaudited
raw-entry denominator cannot tell whether the deficit preceded the syscall
handler or which exact-admission check rejected it. No third pair was
published; the exact source volume/runner and earlier pairs were preserved.
This **does not close** the underlying gap or justify a BPF design change.

**Separate source-matched instrumented boundary:** The
[three-pair opt-in audit series](../evidence/tier-e-clean-admission-audit-20260927/README.md)
also stopped on pair 3, with two prior complete pairs retained. In its later
unique fork raw audit counted **10,000/10,000** target operations on CPU 1,
but exact admission submitted only **9,993**; seven descriptor IDs
`2904`–`2910` each had zero recorded exact admissions. Kernel exact and
ring-ready counts both stopped at 29,986/30,000 with ring loss zero. The
audited main handler-entered count covers unrelated syscalls, and raw/main
program ingress is not currently counted on the same per-descriptor domain.
These observations establish a gap **between raw target observation and
exact descriptor admission**, not whether the main program failed to run or
an early syscall/argument gate diverted those seven events. The broad
lookup-failure/wrong-target counters include unrelated syscalls and cannot
be assigned to the missing IDs. Do not infer a cause or change the core
BPF scan from the aggregate counts. The next diagnostic needs a narrowly
bounded benchmark-only main-program ingress checkpoint keyed by the declared
descriptor, plus tests and a versioned protocol/layout review; any invasive
core BPF redesign needs explicit operator approval. The unaudited five-pair
   qualification remains `INCOMPLETE`.

**Opt-in audit v2 checkpoint (2026-09-27):** The benchmark-only BPF path now
counts the low 32-bit `openat` event near the start of the main program, keyed
by its syscall descriptor, *after* argument decoding and an audit-map lookup
but *before* the existing full-width syscall check and subsequent admission
gates. The diagnostic map value grows from 144 to 152
bytes; `exact_admission_audit_contract_version=2` emits both the per-descriptor
low32 count and a bounded missing-descriptor sample. The old full-width
handler, rejection and submission counters remain independently available.
The per-descriptor comparison can distinguish events counted at the low32
checkpoint from later full-width/admission diversions. A zero low32 count does
**not** prove the main program never ran: argument decoding, eligibility and
the descriptor-map lookup precede the increment. Aggregate
counts include unrelated `openat` operations and cannot be subtracted from the
10,000 target denominator without matching the descriptor. This adds no
production-path instrumentation when the audit is disabled; it does not
change BPF's linear scan, the exact oracle, or the qualification contract.

A **source-dirty development smoke** with the opt-in v2 raw and exact audits,
unchanged 10,000-operation/1,000-per-second denominator and rootful Docker
successfully loaded the new layout and completed both modes: 10,000/10,000
target raw entries, session-matched admissions and exact submissions in each,
30,000/30,000 kernel exact and ring-ready records per mode, no missing IDs or
ring loss. Its BPF low32/main counts were 10,222/10,222 in full mode and
10,062/10,062 in unique mode, including ambient wrong-target opens. Raw logs,
profile, plan and results remain under `/tmp/tier-e-low32-audit-1790535499*`.
This non-noisy, source-dirty smoke proves verifier/layout viability, **not**
the cause of the intermittent gap or a five-pair default-path PASS. Focused
builder/report tests and 22 rootful Docker JUnit tests ran without skips.
The BPF audit extraction reduced the existing `buildSysEnterProgram` Detekt
complexity from 67 to 61 and method length from 422 to 395; the two existing
numeric warning inventory entries were reviewed and updated, with no new
warnings or suppression.

**Clean source-matched audit-v2 stop:** [The sealed diagnostic](../evidence/tier-e-clean-low32-ingress-20260927/README.md)
predeclared three audited AB/BA control/noisy pairs at clean `bffaccdf`, with
unchanged 10,000-operation/1,000/s full+unique modes, image, agent and CPUs.
The first control completed both modes with 10,000 raw target entries,
10,000 session-matched/exact submissions and 30,000 kernel exact/ring-ready
records per mode. The first noisy arm failed on its **first full-stream fork**:
the independent raw audit counted 10,000/10,000 target entries on CPU 1, but
descriptor IDs `4742`–`4749` each had zero low32 checkpoint and zero exact
admission counts. Exact submitted/session-matched were 9,992 and kernel
exact/ring-ready stopped at 29,984/30,000, with zero reported ring loss. The
aggregate low32 and full-width handler counts were both 10,048, including
unrelated opens; argument-read failures were zero. A first-fork live-target
noise sample was retained, but the individual missing IDs have no measured
overlap with its three-second window. No complete audited pairs were published.
The exact failed runner and volume remain stopped/retained. The 9.6.1 TestKit
preflight failure and decision-controller rejection of a three-pair audited
profile both happened **before any scheduled target**; the tested 9.8.0
launcher then executed the explicitly recorded serial schedule without
changing source or profile denominators. Those setup failures and arm logs
are preserved in the sealed receipt. The result narrows the eight IDs to
**at or before the low32 count**, not specifically to a missing main-program
dispatch: audit-map lookup failures or an earlier gate are still possible.
Do not attribute an aggregate lookup or wrong-target counter to those IDs.
The underlying gap and the unaudited five-pair decision remain `INCOMPLETE`;
further attribution requires descriptor-specific evidence before changing the
core BPF branch or promoting a default result.

**Next discriminator, read-only review (2026-09-27):** Both independently
attached direct-raw `sys_enter` programs read the low syscall number at
context offset 8 and argument 0 from x86_64 `pt_regs` offset 112. Their
descriptor maps are created and filled before workload release; map update
and userspace lookup errors throw. The current raw audit counts *declared*
target descriptors after argument decode, its own map lookup and session
gate. The main low32 count follows argument decode and a separate hash-map
lookup, but precedes the full-width syscall check. Program run statistics
were unavailable. In the failed noisy fork, both argument-read failure
counters were zero; the main audit's **1,844,588 aggregate lookup failures**
include ambient syscalls and cannot be assigned to IDs `4742`–`4749`.

| Hypothesis | What the retained evidence says | Discriminator |
| --- | --- | --- |
| The main program did not run for those entries | Raw descriptor counts were one, main low32 counts were zero; independent raw/main direct-raw attachments do not prove identical delivery. | Compare independently counted *all target `openat`* entries in each program before argument decoding or descriptor lookup, using the same target/session gate and quiescent interval. |
| Main program ran, but decoded argument or audit hash lookup did not reach its increment | The helper failure total was zero, but lookup failures are aggregate and the current hash counter cannot identify those IDs. | If independent early target totals agree, compare per-descriptor low32 counts with explicit read/lookup failure stages; do not assign the aggregate failures to missing IDs. |
| The version-two bounded report hid a declared missing descriptor | This run printed all eight zeros, but eight unrelated repeated descriptors used half its 16 slots; future runs can conceal higher-numbered zero IDs. | Prioritize zero-count descriptors ahead of repeated non-singletons and test truncation explicitly. |

The next probe may add only benchmark-enabled pre-decode target counters
on both direct-raw attachments and a versioned diagnostic receipt. A target
total mismatch narrows the boundary to program delivery or the pre-counter
session/target gate, before argument decoding and descriptor lookup;
matching totals alone cannot prove which *individual* descriptor took a
particular branch when unrelated target `openat` calls are also present.
Preserve the exact full+unique oracle, stop at first failure and avoid
changing production/default BPF bytecode or the linear scan.

**Diagnostic implementation pending clean acquisition:** When *both* raw and
exact audits are opted in, a shared, preallocated three-entry array records
target-TGID `openat` entries separately for raw and main attachments. Both
increments precede `pt_regs` argument decoding and descriptor-hash lookup;
both check the same configured session target. The version-three report
prints the independent raw/main totals next to the existing descriptor
counts, after workload exit. It must not subtract an aggregate total from
the 10,000 declared-descriptor denominator: unrelated target opens may occur.
Bounded low32 samples now prioritize zero-count descriptors over unrelated
repeat counts. The map, added instructions and report fields are opt-in only;
the production audit-disabled BPF and 152-byte exact-admission value are
unchanged. Focused tests and verifier-load checks precede any fresh clean
series. Numeric Detekt inventory entries are reviewed in place; no new
warning category or suppressed security check is accepted.

**Development probe exposed an independent raw-audit shortfall:** The first
source-dirty version-three, non-noisy 10,000-operation smoke completed the
main full+unique oracle. Full-stream raw audit saw **9,993/10,000** target
descriptors, missing `4021`–`4027`, while main exact submission was 10,000.
The early target totals were raw 9,995 and main 10,002; unique mode saw
10,000 raw entries and matching early totals of 10,002. Both programs used
direct raw tracepoints, raw target CPU count was `1:9993`, argument-read
failures were zero and kernel ring loss was zero. The normal operation
terminal `COMPLETE` did **not** qualify the independent raw audit. Raw logs
remain at `/tmp/tier-e-predecode-v3-smoke-1790540584*` and in the
[sealed pilot archive](../evidence/tier-e-v3-predecode-pilots-20260927/README.md).

The first early counters began when each program attached, **before** both
maps were configured. Main attaches first; those aggregate counts did not
have a proven shared start, even though seven declared descriptors were
independently missing at raw observation. A third map slot now arms both
early counters after both programs attach and both descriptor maps are
populated, before the workload is released. A paired diagnostic fails
closed after printing its evidence if raw/exact descriptor counts or
argument reads are incomplete or the armed aggregate target totals differ.
This does not prove which attachment failed to execute for a specific ID.

**Armed, fail-closed development probe:** A separate source-dirty 10,000-op
no-noise run under the shared arm gate stopped on its first full-stream fork.
Raw audit observed 10,000/10,000 target descriptors on CPU 1 and its early
target count was 10,002. The main early target count was **9,994**, the main
low32 checkpoint had zero entries for IDs `4410`–`4417`, exact submitted
9,992, and kernel exact/ring-ready both stopped at 29,984 with zero ring
loss. The sample contained two additional target opens in the raw early
total, so the eight-count *armed aggregate difference* matches the eight
per-descriptor omissions, but does not prove whether the main BPF program
failed to run or a pre-counter session/target gate diverged. The paired
verdict was `INCOMPLETE`, the target stopped immediately before unique mode,
and exact runner/volume are retained under
`/tmp/tier-e-armed-v3-smoke-1790541641*` and in the same sealed archive. A clean committed-source
predeclared diagnostic is required before attributing this result; do not
rerun the failed arm merely to obtain a positive sample.

**One predeclared clean-source v3 diagnostic pair:** The [sealed arm
receipts](../evidence/tier-e-clean-armed-predecode-20260927/README.md)
pin clean `0cb0b3ee`, one source fingerprint, Docker image/native agent,
target CPUs `0,1`, unrelated CPU `2`, and the unchanged 10,000-op/1,000/s
full+unique schedule for control then noisy. All four forks independently
matched **10,000 raw descriptor entries, 10,000 exact submissions,
30,000 kernel exact/ring-ready records**, with zero ring loss. Armed early
target `openat` totals were **raw=10,002/main=10,002** each time (including
two unrelated target opens), and all four paired diagnostic verdicts passed.
The noisy observation was bound to the first full-stream PID; it does not
prove overlap with the later unique fork. This is **one audited pair** with
`policy_eligibility=false`, not a regression fix, not proof of the cause of
the earlier eight-ID failures and not the required *five unaudited* pairs.
No additional run may be selected merely because this one passed. The
next work is to isolate the pre-arm target/session gate versus program
dispatch when the deficit recurs, with per-ID evidence before any core BPF
change. Timing stability and default node-noise closure remain blocked.

**Version-four feasibility gate before BPF edits:** The real branch order in
both direct-raw programs is low32 `openat` → current TGID helper → **session
map lookup and target comparison** → common arm lookup → early aggregate
counter → `pt_regs` argument read → declared-descriptor hash. The two
opposite failed runs cannot distinguish a missing callback from a
pre-counter session/target diversion. Engine installation receives the
positive target TGID, but `TierEKernelSmoke` reads declared FDs only after
constructing its `TierEOptions`/session and before installation; therefore
derive the independent target comparison from that install argument and
create a **fixed** benchmark-only descriptor array, not a late-size map or
new public API. Use at most 131,072 keys (the existing benchmark audit cap)
with five 64-bit slots per key: declared marker, raw/main pre-session and
raw/main post-session (5,242,880 map value bytes plus kernel overhead).
Reject an oracle FD outside `0..131071` before attaching, mark only the
declared entries after attach, and arm both programs only after this and
the older audit maps are configured. A per-ID count must follow an
immutable installed-TGID check and a successful argument read, but **precede**
the mutable session-map lookup; an explicit read/lookup failure makes the
diagnostic `INCOMPLETE`, never zero by assumption. The second stage follows
successful session/TGID admission. This is opt-in on the selected direct-
raw backend; the default linear-scan program and historical v3 reports
remain unchanged. If this bounded shape cannot load, stop with the verifier
log rather than changing core program architecture to obtain a PASS.

**Version-four implementation pending kernel verifier:** The shared arm
array retains v3 raw/main target counters and adds six failure slots for
raw/main argument-read and before/after indexed-map lookup errors. A fixed,
benchmark-only five-u64-per-FD array holds the declared marker and raw/main
pre-/post-session counts; the 131,072-key cap reserves 5,242,880 value
bytes plus kernel overhead. The installed positive TGID is an immediate in
both *direct-raw* diagnostic programs, independent of the mutable session
map. The before-session checkpoint reads the descriptor only after the
common arm, classifies unrelated negative/out-of-range FDs as ambient,
and increments declared keys before the existing session gate. The after-
session checkpoint runs after that gate but before later attribution.
Failed reads/lookups remain explicit counters; map setup, verifier and
kernel failures stop the instrumented run. Legacy v3 reports, default
BPF bytecode, the 152-byte exact-audit map and operation denominators
remain untouched. Test-first builder/classifier checks pass; a real
verifier-load and clean acquisition have **not** yet occurred, so no new
delivery or causal claim is made here.

**First v4 verifier stopped on a probe bug:** The [sealed source-dirty
verifier](../evidence/tier-e-v4-verifier-eligibility-20260927/README.md)
loaded the bounded array but stopped on its first full fork. Raw/main
pre-session ID counts were 10,000/10,000 and their armed target aggregates
were 10,002/10,002, but main after-session and exact submissions counted
32,847 and 30,000. Those impossible per-ID multiplicities resulted from
the **new diagnostic helper** leaving `r5` nonzero before the older
exact-audit eligibility initialization, not a classified kernel delivery
loss. The exact stopped runner/volume were retained. A bytecode test failed
on this register initialization before the opt-in v4-only reset made it
pass. This fork cannot classify main versus raw dispatch; re-verify the
changed source once, rather than repeating an unchanged failing verifier.

**Second changed-source v4 verifier also stopped on an instrument-only
branch:** [Sealed receipts](../evidence/tier-e-v4-post-session-guard-20260927/README.md)
showed raw/main pre-session 10,000/10,000, raw post-session 10,000 and
exact submissions 10,000, but main post-session counted **33,045**.
The opt-in main post-session diagnostic read the previously saved FD even
on later non-`openat` syscalls; those counts were not real declared opens.
A new builder test failed before and passed after guarding that lookup on
the low32 `openat` number. The failed full fork stopped before unique mode,
and its exact runner/volume remain retained. A separately predeclared
changed-source verifier may check this *one demonstrated probe fix*; if
another instrumented fork is still incoherent, stop and review the
diagnostic architecture rather than continuing a success-seeking series.

**Third predeclared verifier reached the architecture stop:** The [sealed
source-dirty receipt](../evidence/tier-e-v4-architecture-stop-20260927/README.md)
stopped on its first full fork. Main exact submission was 10,000/10,000;
raw observed 9,997/10,000 on CPU 1 and missed IDs `7746`–`7748`. Armed
raw/main target totals were 10,000/10,003; raw/main pre- and post-session
*aggregate* descriptor totals were each 9,997/10,000. Read/map failure
counters and ring loss were zero. Those totals do **not** establish a
particular ID's pre-gate branch or prove raw program non-execution.
The classifier incorrectly labeled unrelated repeated low32 descriptors
as missing main hash entries; these lower-numbered ambient FDs filled its
16 slots before the three missing raw IDs. Its `pre_gate_audit_boundary`
field is **not decision-quality** for this fork. Unique mode and the clean
v4 series were not started; the stopped runner and volume are retained.
Stop further probe fixes and kernel acquisition until the diagnostic
architecture and per-ID evidence representation receive explicit review.
The five-pair unaudited qualification remains `INCOMPLETE`.

**Reviewed v4 diagnostic correction (in progress, not kernel-qualified):**
The earlier sixteen-row classifier compared ambient/mixed low32 counts
against target-qualified pre-gate counts and assigned a causal `MAIN_HASH_GATE`
without evidence. The decision classifier now uses only declared, target-
qualified raw/main pre-/post-session rows, treats missing later observations
as an unclassified checkpoint absence rather than proof of a particular
kernel gate, and excludes the mixed low32 map from its verdict. A separate
bounded sidecar writes **every declared FD** with pre-/post-stage counts,
raw target count and exact submission into a fresh atomic artifact before
the child can report success. Tooling checks its per-fork SHA-256, count,
ordering and schema before retiring the Docker volume; missing or torn
evidence fails closed. The printed sixteen rows are only a preview.

The v4 BPF path now initializes a per-invocation eligibility flag and
validated FD before the target/syscall/arm/argument/declared checks; later
post-session evidence requires that flag and uses that preserved FD. An
instruction-prefix interpreter test (not a mock of the decision function)
failed for stale scratch, failed first argument reads and unarmed events
before the code change and now exercises wrong-target, session rejection,
missing map-entry and consecutive-syscall paths. These are **host-only
checks**; do not reinterpret or discard the sealed architecture-stop run.
No subsequent kernel verifier or clean v4 acquisition has run under the
corrected contract; default qualification remains blocked.

**Reviewed-probe development verifier and host export correction:** The
[source-dirty, predeclared real-kernel run](../evidence/tier-e-v4-reviewed-artifact-verifier-20260927/README.md)
recorded all 10,000 target raw IDs and 10,000 exact submissions in each
full+unique fork. Raw/main pre-/post-session counts were each 10,000 per
fork, all explicit pre-gate failure counters were zero and both complete
10,000-row per-ID sidecars were written with recorded hashes. Kernel exact
and ring-ready were 30,000/30,000 with zero loss. The host export stopped
`INCOMPLETE` because `fork-output.target_pid` names the collector process,
not the target JVM in the sidecar header. A red/green test corrected the
verifier to bind to the **child's `workload_pid`**; offline verification
against the *same retained exported files* then passed without another
kernel run. The original terminal is **not retroactively upgraded**. The
runner and volume remain retained. This is still source-dirty development
evidence: no clean-source v4 pair or default qualification is claimed.

**One clean reviewed-v4 diagnostic pair:** The [sealed clean control/noisy
receipt](../evidence/tier-e-clean-v4-reviewed-pre-gate-20260927/README.md)
pins committed `5959b108`, one source fingerprint, image/native agent,
target CPUs `0,1` and unrelated CPU `2`. Both full+unique forks in each
arm recovered all **10,000/10,000** declared raw target IDs, main exact
submissions and target-qualified raw/main pre-/post-session counts. All
four 10,000-row sidecars were atomically published and checked against
their fork, **workload PID**, SHA-256, schema and sorted IDs before their
source volume was retired. Every fork retained 30,000/30,000 kernel
exact/ring-ready records and zero ring loss. The separate noise sample
overlapped the observed first full-stream PID only; later unique-mode
overlap was not measured. This **single instrumented pair** is not a
reproduction or cause of the earlier opposite misses, nor the five-pair
unaudited decision. No current-source native timing PASS or policy
promotion is claimed; stop rather than rerunning until green.

**Version-five provenance hardening (offline, pending kernel verification):**
The older static helper metrics rebuilt a program under configured `AUTO`,
which could describe the wrong backend and omitted the v4 install-time
TGID. The engine now snapshots raw/main **exact map-FD-substituted BPF
instruction bytes** at each `BPF_PROG_LOAD` and records SHA-256, effective
backend and helper/byte counts. `BPF_OBJ_GET_INFO_BY_FD` retains the kernel
program ID and translated byte length even when optional runtime counts
are zero/unavailable; native errors fail closed rather than being swallowed.
The target-authored original `fd=` oracle manifest is exported separately
before the shared arm, with an exact ID set and SHA-256. A new version-five
sidecar binds that oracle hash and both installed-program digests to its
full declared-ID rows. Before volume retirement, the host recomputes all
raw/main stage and admission totals and compares the sorted sidecar IDs
against the original oracle, not just the child-reported row count.
Emitted-bytecode prefix tests now also exercise the **raw** program and
caller-clobbered helper registers, along with the existing main-program
failure/eligibility matrix. Archived version-four receipts retain their
historical interpretation. These are host/offline checks so far; no new
source-clean version-five qualification or default-path PASS exists.

**First version-five kernel verifier failed with complete individual
evidence (2026-09-28 UTC):** The [sealed source-dirty
receipt](../evidence/tier-e-v5-raw-pre-admission-stop-20260928/README.md)
matches the target-authored 10,000-ID oracle to all 10,000 retained
sidecar rows and records the exact installed raw/main program IDs and
post-map-FD-substitution instruction digests. It stopped on its **first
full fork**: raw observed 9,997/10,000 on CPU 1 while main exact submitted
10,000/10,000. The original target oracle and complete table identify
`5050`–`5052`: each raw before/after session and raw target count was
**zero**, while each main before/after session and exact count was **one**.
No other declared row was abnormal. Armed raw/main target aggregates were
10,000/10,003, all six explicit read/array-lookup failure counters were
zero and kernel ring loss was zero. Distinct raw/main kernel program IDs
`61288`/`61287` survived despite unavailable optional runtime statistics.
These facts bound the raw deficit to **at or before its per-ID pre-session
stage**; they do not prove program non-execution or a particular kernel
callback failure. The exact stopped runner/volume remain retained; unique
mode and the predeclared clean three-pair campaign were **not** started.
Do not rerun the same schedule without a new testable hypothesis separating
program delivery from the earliest target/arm/argument checks. Default
unaudited node-noise and the native timing gate remain incomplete.

**New-kernel source-bound discriminator (2026-09-28, before acquisition):**
The operator rebooted into `7.2.8-x64v3-xanmod1`, GitLab XanMod tag
[`ca927d28dad3d7cdaad121df660728a67439dad0`](https://gitlab.com/xanmod/linux/-/tree/7.2.8-xanmod1).
The v5 raw IDs `5050`–`5052` were acquired on older `7.2.4` and cannot be
transferred to this kernel. In both exact source tags `__bpf_trace_run`
checks each raw program's recursion context, increments **that program's**
misses when it cannot run, and skips its callback. The raw BPF dispatch
file, tracepoint dispatch file, and isolated recursion/miss-accounting
functions are byte-identical between the tags. This establishes an
available **hypothesis**, not the cause of the historical three IDs.
The running kernel's `bpf_prog_info.recursion_misses` lies at byte 208;
the old 208-byte request omitted it. A host C `offsetof` probe found
size 232 and offset 208. Normal BPF programs allocate per-program stats
and the raw recursion-miss increment does not depend on the BPF runtime-
timing static key. `/proc/sys/kernel/bpf_stats_enabled` is `0` and no
global switch was changed. `BPF_ENABLE_STATS` would require `CAP_SYS_ADMIN`
and temporarily enable a global static key, so it is not selected.
The optional diagnostic reader now requests 232 bytes, guards the returned
length, retains `null` if unavailable, and captures separate raw/main
miss-count baselines before arm and post-target counts with program-ID
continuity checks. Source/FFM host tests passed; **no new-kernel benchmark
has yet run**.

| Explanation | Current bound | Falsifier in a future armed, source-pinned fork |
| --- | --- | --- |
| Raw callback skipped by its program recursion guard | Exact tagged source has the branch and per-program counter; historical value was not requested. | **Available zero raw recursion-miss delta** alongside a raw missing ID rules out this skip for that fork. Nonzero program-wide misses support, but cannot by themselves identify, any particular ID. |
| Raw low32 syscall, immutable TGID or shared arm check exited | Armed raw target total and raw pre-ID count both fell three below main; no tracked argument/lookup failure. | An independently admitted event reaches a checkpoint before the selected condition (requires a separately reviewed, narrowly bounded probe). |
| Raw argument or declared-map access failed | Target-written IDs were stable, and all explicit argument/map-failure counters were zero. | A newly captured explicit failure in the exact missing-ID interval, after checking helper/branch behavior. |
| FD reuse or denominator change | Target retains its directory FDs until the workload completes; the target-authored manifest and full per-ID file agree without duplicates. | A same-target FD-lifetime or manifest discrepancy. |

If host validation remains green, the next **separately predeclared**
experiment is at most three clean, serial *control-only* audited targets
on `7.2.8` with the original 10,000-operation full+unique schedule. Noise
overlap did not explain the previous missing IDs, so a control-only
measurement isolates this recursion hypothesis. Stop on the first failure,
retain full rows, source/kernel/program identities and raw/main miss deltas;
if all pass, report not reproduced within this fixed budget. An available
zero raw miss delta would exclude the recursion guard for a failed arm;
nonzero misses remain aggregate system-wide evidence, not per-ID proof.
None of these opt-in outcomes qualifies the unaudited or timing gates.

**First new-kernel control stopped, source-clean (2026-09-28 UTC):** The
[sealed `6e0d5dd6` control](../evidence/tier-e-kernel-7-2-8-recursion-control-20260928/README.md)
ran on XanMod `7.2.8` under its fixed source/image/agent/CPU-set plan.
On the first full-stream fork raw counted 9,957 and main exact submitted
9,958 of 10,000. The original target FD manifest and complete per-ID
rows show **43 raw-only**, **42 main-only**, and **zero jointly missing**
IDs. Explicit read/array-lookup failure counters and ring loss were zero.
After shared-arm baselines of six each, per-program recursion-miss deltas
were **222 raw and 4,957 main**, with runtime timing/count still
unavailable. The tagged kernel's recursion guard can skip one program,
but these system-wide miss totals cannot be assigned to any of the 85
specific failed IDs. This supports investigation, not a causal claim.
Controls two/three and unique mode did not run; exact failed runner and
source volume remain retained.

**Additional demonstrated CPU-provenance defect:** The manifest requested
workload CPU `0` and collector CPU `1`, yet **all 9,957 observed raw target
operations occurred on CPU 1**. In sustained non-coverage mode,
`TierEMatchedBenchmark` starts the collector pinned to CPU 1, which
launches the target JVM with inherited CPU-1 affinity. Only its leader
is later moved via `taskset -pc 0`; established JVM workers can retain
CPU 1. Thus this result does not establish target-thread CPU-0 affinity,
although the direct-raw attachment itself is system-wide. Do not use
the data as a CPU-0 workload qualification. A separate versioned
startup-affinity correction must pin the target before its JVM threads
start and verify effective workload-thread affinity before gate release;
any later run is a newly predeclared source/CPU contract, not a retry of
this `INCOMPLETE` terminal.

**Startup-affinity correction (source validation, not kernel qualification):**
The opt-in paired pre-gate audit now launches the target JVM with `taskset -c`
on the selected workload CPU **before** its Java threads exist. The harness
reads `/proc/<target>/task/*/status` and requires every existing target thread
to have exactly that CPU in `Cpus_allowed_list` before releasing the operation
gate; an unreadable, empty or mismatched snapshot fails closed. The earlier
leader-only operation remains for the other benchmark modes. A focused
red/green test checks the startup command and rejects one mis-affined thread.
This is a new source revision and therefore cannot revise the sealed
`6e0d5dd6` result. No new CPU-pinned kernel control has been acquired.
The Detekt report for this exact correction was reviewed: the pre-existing
oversized `TierEKernelSmoke.main` inventory changed from complexity 362 to
363 and length 1308 to 1313; its existing two numeric warning entries were
updated individually. The report adds no other warning, and one previously
tracked magic-number occurrence was removed. No threshold or module-wide
warning allowance was changed.

**Next opt-in discriminating witness (source implementation, not kernel
qualification):** The native `unix_openat0` proxy records its *published*
marker invocation ID and positive directory FD in a bounded `MZIF` sidecar;
the original target-authored FD manifest remains the denominator. When
explicitly enabled only for paired audited direct raw/main benchmark forks,
both BPF programs read that invocation ID from existing task storage before
their arm/FD checkpoint and count it in an owned bounded map. Unavailable
token, zero marker, and map failures have separate armed counters. A second
per-ID artifact binds the native sidecar, original manifest, both loaded
program hashes, and raw/main earliest counters; host settlement checks all
rows before retiring the source volume. The default BPF bytecode and
unaudited benchmark remain unchanged. An ID without an early token only
bounds a **pre-token checkpoint** gap; positive program-wide recursion
misses still cannot prove that the kernel guard skipped the ID. Native and
host tests cover wire format, helper failure, emitted bytecode, and
mismatch rejection. The reviewed Detekt report updates only the existing
numeric inventory for extended builder/engine/harness declarations; no
global threshold or new warning category was adopted. A source-clean
pilot that proves a complete 1:1 native-to-raw/main join is required
before any full 10,000-operation diagnostic acquisition.

**One source-clean 200-operation feasibility pilot stopped, not retried:**
The [sealed `42211aa6` marker-join pilot](../evidence/tier-e-invocation-marker-join-pilot-stop-20260928/README.md)
verified every target-authored FD against one native marker ID, one raw
token, one main token and both FD-stage checkpoints; 200/200 target events
were on CPU 0, with zero ring loss. It nevertheless stopped `INCOMPLETE`
under its predeclared **zero witness failure** rule: raw and main each
reported three *additional* target `openat` calls with an unavailable
marker ID during the shared arm. Both early target counts were 203, and
the no-token counters were 3/3. These aggregates are compatible with
ambient target traffic but are not per-ID kernel guard evidence. The
source-clean first terminal, exact stopped runner and artifact volume are
retained. No 10,000-operation or unaudited gate follows from this result.
A separate source and experiment contract would need a tested positive
conservation condition: every declared ID has both tokens and both FD
stages exactly once, all other witness failures are zero, and only the
early-target surplus equals the no-token counters. Any missing declared
token leaves that surplus unattributable and remains `INCOMPLETE`.

**New source contract, with the original failure unchanged:** A focused
red/green host regression now admits armed zero-token surplus only when
every target-authored declared FD has exactly one native ID, raw token,
main token, before/after descriptor count, and final raw/exact count;
the token map must contain *only* those IDs. Other marker/map errors
must be zero, and raw/main early target counts must equal the declared
cardinality plus their respective zero-token counters. All other cases
fail closed without calling the surplus ambient. The independent host
verifier validates the same numeric relationship before retiring an
exported source volume and rejects the already-sealed pilot's
`invocation_witness_complete=false` receipt. This is a new source
revision and needs a separately predeclared clean short pilot; it does
not rehabilitate the original `42211aa6` terminal.

**Source-matched revised pilot passed; the fixed 10,000-ID control
stopped `INCOMPLETE`:** The [sealed `8393bf7f` diagnostic](../evidence/tier-e-invocation-witness-10k-stop-20260928/README.md)
preserves two separately predeclared runs. One 200-ID pilot completed a
full native/raw/main join, with three zero-token surplus calls in each
program conserved against 203/203 early target counts; it is feasibility
evidence only. The subsequent *single* 10,000-ID full-then-unique control
stopped at the first full-stream fork. The original target FD manifest,
native marker sidecar, token sidecar and original pre-gate sidecar agree:
**all 10,000 declared IDs reached raw and main tokens, both FD stages,
raw observation and main exact submission on CPU 0**, with zero native
drops, ring loss or explicit FD-read failures. However each program saw
10,004 target openat entries before its FD gate: the armed witness had
**one missing task-storage state and three zero-token states** per
program. Only zero-token surplus was admitted by the predeclared
conservation rule. Missing task storage cannot safely be assigned to
ambient traffic or an individual declared ID from these aggregates, so
the terminal remains `INCOMPLETE` and unique-edge did not run. Raw/main
recursion-miss deltas were 166/346, still program-wide and not causal.
The exact stopped runner and artifact volume are retained. No default
or unaudited qualification follows; the complete instrumented 10,000-ID
count does not prove the earlier uninstrumented gap was fixed.
