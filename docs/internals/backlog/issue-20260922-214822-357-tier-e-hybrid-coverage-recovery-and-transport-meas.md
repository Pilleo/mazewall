---
title: "Tier E hybrid coverage recovery and transport measurement"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["profiler", "profiler-native", "scripts"]
target_files: ["profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierEOptions.kt", "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEBpfProgramBuilder.kt", "scripts/run_tier_e_split_matched_benchmark.sh", "scripts/run_tier_e_split_privilege_fixture.sh", "scripts/run_tier_e_split_coverage_matrix.sh", "scripts/tier_e_target_cpu_sample.mjs", "scripts/tier_e_collector_cpu_sample.mjs", "scripts/tier_e_long_matched_row.mjs", "scripts/summarize_tier_e_long_matched_controls.mjs", "scripts/tests/tier_e_target_cpu_sample.test.mjs", "scripts/tests/tier_e_collector_cpu_sample.test.mjs", "scripts/tests/tier_e_long_matched_row.test.mjs", "scripts/tests/tier_e_long_matched_controls.test.mjs", "profiler-native/tests/AgentCoverageWorkload.java", "docs/internals/evidence/tier-e-long-matched-controls/README.md", "docs/internals/evidence/tier-e-long-matched-controls/SHA256SUMS", "docs/internals/evidence/tier-e-long-matched-controls/archive-manifest.json", "docs/internals/evidence/tier-e-long-matched-controls/commands/drift-decision.json", "docs/internals/evidence/tier-e-long-matched-controls/commands/drift-replay.jsonl", "docs/internals/evidence/tier-e-long-matched-controls/commands/drift-stderr.txt", "docs/internals/evidence/tier-e-long-matched-controls/commands/drift-stdout.txt", "docs/internals/evidence/tier-e-long-matched-controls/commands/estimator-command.txt", "docs/internals/evidence/tier-e-long-matched-controls/commands/recompute-output.txt", "docs/internals/evidence/tier-e-long-matched-controls/commands/verifier-guest-pass.json", "docs/internals/evidence/tier-e-long-matched-controls/commands/verifier-guest-truncation.json", "docs/internals/evidence/tier-e-long-matched-controls/commands/zero-v1-decision.json", "docs/internals/evidence/tier-e-long-matched-controls/commands/zero-v1-replay.jsonl", "docs/internals/evidence/tier-e-long-matched-controls/commands/zero-v1-stderr.txt", "docs/internals/evidence/tier-e-long-matched-controls/commands/zero-v1-stdout.txt", "docs/internals/evidence/tier-e-long-matched-controls/decision.json", "docs/internals/evidence/tier-e-long-matched-controls/fork-results.jsonl", "docs/internals/evidence/tier-e-long-matched-controls/host-environment-after.json", "docs/internals/evidence/tier-e-long-matched-controls/host-environment-before.json", "docs/internals/evidence/tier-e-long-matched-controls/member-sha256-index.txt", "docs/internals/evidence/tier-e-long-matched-controls/run-manifest.json", "docs/internals/evidence/tier-e-long-matched-controls/source-state-after.json", "docs/internals/evidence/tier-e-long-matched-controls/source-state-before.json", "docs/internals/evidence/tier-e-long-matched-controls/terminal.json", "docs/internals/evidence/tier-e-long-matched-controls/tier-e-long-matched-raw.tar.zst", "scripts/verify_tier_e_long_matched_controls.py", "scripts/tests/test_verify_tier_e_long_matched_controls.py", "docs/internals/designs/profiler/tier-e-design.md", "docs/internals/evidence/tier-e-long-matched-controls-corrected-20260925/README.md", "docs/internals/evidence/tier-e-long-matched-controls-corrected-20260925/SHA256SUMS", "docs/internals/evidence/tier-e-long-matched-controls-corrected-20260925/acceptance-verdict.json", "docs/internals/evidence/tier-e-long-matched-controls-corrected-20260925/archive-manifest.json", "docs/internals/evidence/tier-e-long-matched-controls-corrected-20260925/commands/estimator-stdout.txt", "docs/internals/evidence/tier-e-long-matched-controls-corrected-20260925/commands/recompute-output.txt", "docs/internals/evidence/tier-e-long-matched-controls-corrected-20260925/commands/verifier-stdout.json", "docs/internals/evidence/tier-e-long-matched-controls-corrected-20260925/decision.json", "docs/internals/evidence/tier-e-long-matched-controls-corrected-20260925/fork-results.jsonl", "docs/internals/evidence/tier-e-long-matched-controls-corrected-20260925/guest-matrix.log", "docs/internals/evidence/tier-e-long-matched-controls-corrected-20260925/kernel.txt", "docs/internals/evidence/tier-e-long-matched-controls-corrected-20260925/member-sha256-index.txt", "docs/internals/evidence/tier-e-long-matched-controls-corrected-20260925/pilot-run-manifest.json", "docs/internals/evidence/tier-e-long-matched-controls-corrected-20260925/pilot-terminal.json", "docs/internals/evidence/tier-e-long-matched-controls-corrected-20260925/run-manifest.json", "docs/internals/evidence/tier-e-long-matched-controls-corrected-20260925/source-state-after.json", "docs/internals/evidence/tier-e-long-matched-controls-corrected-20260925/source-state-before.json", "docs/internals/evidence/tier-e-long-matched-controls-corrected-20260925/terminal.json", "docs/internals/evidence/tier-e-long-matched-controls-corrected-20260925/tier-e-long-matched-corrected.tar.zst", "docs/internals/evidence/tier-e-long-matched-controls-corrected-20260925/validation.json", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/README.md", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/SHA256SUMS", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/commands/corrected-estimator-stdout.txt", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/commands/corrected-full-directory.json", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/commands/corrected-jsonl-only.json", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/commands/corrected-member-index.log", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/commands/corrected-sha256sums.log", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/commands/mutation-probes.json", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/commands/original-estimator-stdout.txt", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/commands/original-full-directory.json", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/commands/original-jsonl-only.json", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/commands/original-member-index.log", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/commands/original-sha256sums.log", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/commands/probe-estimator-artifact-errors.decision.json", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/commands/probe-estimator-artifact-errors.jsonl", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/commands/probe-estimator-artifact-errors.stdout.txt", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/commands/probe-estimator-cross-fork-drift.decision.json", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/commands/probe-estimator-cross-fork-drift.jsonl", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/commands/probe-estimator-cross-fork-drift.stdout.txt", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/commands/probe-estimator-extra-arm.decision.json", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/commands/probe-estimator-extra-arm.jsonl", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/commands/probe-estimator-extra-arm.stdout.txt", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/commands/probe-estimator-missing-identity.decision.json", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/commands/probe-estimator-missing-identity.jsonl", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/commands/probe-estimator-missing-identity.stdout.txt", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/commands/probe-verifier-missing-arm.stdout.txt", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/commands/probe-verifier-nan-cpu.stdout.txt", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/commands/probe-verifier-split-source.stdout.txt", "docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/verifier-sha256.txt", "docs/internals/evidence/tier-e-long-matched-verifier-contract-validation-20260925/README.md", "docs/internals/evidence/tier-e-long-matched-verifier-contract-validation-20260925/SHA256SUMS", "docs/internals/evidence/tier-e-long-matched-verifier-contract-validation-20260925/commands/corrected-estimator.stdout.txt", "docs/internals/evidence/tier-e-long-matched-verifier-contract-validation-20260925/commands/corrected-full-directory.json", "docs/internals/evidence/tier-e-long-matched-verifier-contract-validation-20260925/commands/corrected-jsonl-only.json", "docs/internals/evidence/tier-e-long-matched-verifier-contract-validation-20260925/commands/corrected-ledger.log", "docs/internals/evidence/tier-e-long-matched-verifier-contract-validation-20260925/commands/corrected-members.log", "docs/internals/evidence/tier-e-long-matched-verifier-contract-validation-20260925/commands/original-estimator.stdout.txt", "docs/internals/evidence/tier-e-long-matched-verifier-contract-validation-20260925/commands/original-full-directory.json", "docs/internals/evidence/tier-e-long-matched-verifier-contract-validation-20260925/commands/original-jsonl-only.json", "docs/internals/evidence/tier-e-long-matched-verifier-contract-validation-20260925/commands/original-ledger.log", "docs/internals/evidence/tier-e-long-matched-verifier-contract-validation-20260925/commands/original-members.log", "docs/internals/evidence/tier-e-long-matched-verifier-contract-validation-20260925/commands/pytest-all.log", "docs/internals/evidence/tier-e-long-matched-verifier-contract-validation-20260925/commands/review-eight-probes.log", "docs/internals/evidence/tier-e-long-matched-verifier-contract-validation-20260925/validation.json", "docs/internals/evidence/tier-e-long-matched-verifier-contract-validation-20260925/verifier-sha256.txt", "profiler/build.gradle.kts", "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierELongMatchedAcceptance.kt", "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierELongMatchedDirectoryVerifier.kt", "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierELongMatchedEstimator.kt", "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierELongMatchedRowBuilder.kt", "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierESourceFingerprint.kt", "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEToolsMain.kt", "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierELongMatchedAcceptanceTest.kt", "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierELongMatchedEstimatorTest.kt", "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierELongMatchedRowBuilderTest.kt", "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierESourceFingerprintTest.kt", "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierECpuSamplesTest.kt", "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierELongMatchedArmStatusTest.kt", "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierESplitCoverageVerifierTest.kt", "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierECpuSamples.kt", "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierELongMatchedArmStatus.kt", "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierESplitCoverageVerifier.kt", "docs/internals/evidence/tier-e-kotlin-tooling-smoke-20260925/README.md", "docs/internals/evidence/tier-e-kotlin-tooling-smoke-20260925/SHA256SUMS", "docs/internals/evidence/tier-e-kotlin-tooling-smoke-20260925/archive-manifest.json", "docs/internals/evidence/tier-e-kotlin-tooling-smoke-20260925/commands/tier-e-kotlin-cpu-debug.log", "docs/internals/evidence/tier-e-kotlin-tooling-smoke-20260925/commands/tier-e-kotlin-cpu-smoke.log", "docs/internals/evidence/tier-e-kotlin-tooling-smoke-20260925/kotlin-tooling-smoke.tar.zst", "docs/internals/evidence/tier-e-kotlin-tooling-smoke-20260925/member-sha256-index.txt", "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierELongMatchedArtifactContractTest.kt", "pytest.ini", "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierELongMatchedDirectoryVerifierMutationTest.kt"]
target_symbols: ["TierEOptions", "CoverageRecoveryMode", "CoverageCandidate"]
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Tier E hybrid coverage recovery and transport measurement

**Context:**
Deliver an opt-in hybrid coverage mode that preserves an exact stack/syscall edge
in a bounded kernel map when its ring event cannot be delivered. Keep the existing
ring stream and collector-owned acknowledgement. Dictionary/full-stream remains
the default. `policy_eligibility=false` throughout.

**Needed:**
1. Stage 1: extend split-topology benchmark to export receipt p50/p95/p99, native
   serialization/publication counters, CPU/RSS, retained-byte peaks; freeze workload;
   bounded deadlines, cleanup, terminal result for missing batches/children/metrics.
2. Stage 2: marker v2 `mazewall_stack_marker_v2(invocationId, stackTraceId, flags)`
   with versioned USDT, per-session version selection, 24→32 byte task storage,
   exact publish after admission, clear lifecycle, nested scope restore.
3. Stage 3: bounded retained-edge map `DISABLED|SHADOW|RECOVER` default DISABLED,
   4096 edges, sessionEpoch+stack+syscall+flags key, immutable representative,
   insert-if-absent before ring reservation, no eviction, continued ring attempts.
4. Stage 4: 100ms sweep start, ≤256 entries/batch with bounded yielding, serialized
   collector owner, historical validation without replaying task state, shared
   acknowledgement (coverage-only for full-stream), ordered shutdown, early
   singleton ring-drop fixture with incomplete event history.
5. Stage 5: compare terminal vs 100ms live snapshots; reopen incremental only on
   ≥10% profiler CPU, retention exhaustion, or dictionary blocking discovery.
6. Stage 6: v1-ring vs v2-ring vs v2-hybrid matrix, 72-path oracle, 5+15 forks,
   72/72 gates, p99 dependency-relative recovery ≤500ms, paired 95% ≤1.25 workload
   and CPU, receipt p99 ≤ max(100us,10%).

**Investigation:**
- Split matched benchmark exports workload_ns/CPU/RSS only; receipt/publication
  phases exist in TierEMatchedBenchmark but not in split topology.
- Split target agent runs without `benchmark-phases`; native counters unavailable.
- Terminal report lacks publication/receipt fields; collector.log is terminal-only.
- TierEOptions has DIAGNOSTIC_UPDATE coverage map but no SHADOW/RECOVER mode.

**Side effects:**
- Disabled mode must retain existing generated program and map schema.
- No ring suppression, no promise of lower producer CPU.
- No default promotion without separate decision.

**Rootful experimental result (2026-09-23):**
- Marker v2 register mapping, full-width 32-byte task initializer, stack/generation
  overlap, in-flight completion key, real retained output, and split-topology
  configuration have been corrected. The map uses a 24-byte session/stack/syscall/flags
  key and a 96-byte first-observation value with attachment generation.
- Dedicated scanner queues bounded immutable pages independently of ring polling;
  collector-owned ring/map coverage membership and acknowledgement preserve
  full-stream event history and deduplicate coverage. Final scan requires both
  native closure and kernel quiescence; map pressure remains explicit.
- Retained evidence: `docs/internals/evidence/tier-e-hybrid-20260923/README.md`.
  Six configurations each recovered 72/72 paths in five development and fifteen
  decision forks (120/120 fault-free runs). Hybrid-only long 75,000-operation
  coverage passed 72/72 paths in 40/40 additional development/decision forks.
  Thirty controlled ring-drop forks
  recovered every singleton from the map while retaining incomplete ring
  transport and event history. The maximum per-fork dependency-relative p99
  discovery upper bound was 2.3 ms versus a 500 ms target. A one-entry map
  pressure run reported 223 insertion failures and still recovered 72/72 via
  the ring; its recovery verdict remained incomplete.
- Final calibrated paired upper 95% workload ratios were 1.080 (full) and
  1.021 (unique); combined CPU ratios were 1.285 (full, **FAIL**) and 1.239
  (unique); receipt-p99 fractions of the allowed increase were 2.47 (full,
  **FAIL**) and 1.34 (unique, **FAIL**). Default promotion is rejected; mode
  remains opt-in experimental. Live native publication stayed below 0.5% of
  measured collector CPU and did not exhaust native retention; incremental
  dictionary transport remains deferred.
- Same-guest long matched controls (2026-09-24/25, diagnostic):
  `docs/internals/evidence/tier-e-long-matched-controls/README.md`.
  One clean rootful disposable guest, fifteen paired forks per emission mode
  across seven arms (baseline plus v1/v2/hybrid profiled per emission) at
  75,000 operations, with source/image/native/JAR identities frozen
  (`input_dirty_count=0`) and 105/105 arms complete against the exact 72-path
  oracle. Profiler-only incremental CPU medians: full-stream v1 75.74 s,
  hybrid 75.40 s (hybrid/v1 1.0137, upper 95% 1.0327); unique-edge v1
  74.18 s, hybrid 74.34 s (hybrid/v1 0.9894, upper 95% 1.0212);
  `metrics_status=COMPLETE` with 15 pairs per variant and emission mode.
  This evidence class is distinct from the old host 40/40 hybrid-only
  correctness forks above, from the short host calibrated gate failures
  above (which remain failed and are not replaced), and from the guest-host
  PID-reuse controlled-watcher evidence below. Combined CPU ratios of
  14.4–14.9x baseline are a collector-dominated denominator artifact and are
  not comparable to the host 1.25 gate. Diagnostic only: the operator's
  `REJECT_DEFAULT_PROMOTION` stands and `policy_eligibility=false`.
- Corrected same-guest control evidence (2026-09-25):
  `docs/internals/evidence/tier-e-long-matched-controls-corrected-20260925/README.md`.
  The historical collector sampler mixed CPU ticks from two `/proc` snapshots;
  the corrected sampler reads both counters from one snapshot. A fresh clean
  five-fork pilot passed 35/35 arms and a fifteen-fork decision run passed
  105/105 arms, including 90/90 exact 72-path profiled results. The tracked
  `scripts/verify_tier_e_long_matched_controls.py` independently accepted the
  new run. Corrected hybrid/v1 incremental CPU medians were 1.0023 (upper 95%
  1.0419) in full-stream and 0.9989 (upper 95% 1.0342) in unique-edge (15
  pairs each). Old and new runs are not directly paired; these diagnostic
  results do not replace the failed short-host combined-CPU or receipt-p99
  gates. The operator's `REJECT_DEFAULT_PROMOTION` and
  `policy_eligibility=false` remain unchanged.

**Native live-admission follow-up (2026-09-23):**
- Existing MZSE v2 snapshots now enter the opt-in hybrid collector as
  session-bound, dependency-complete, monotonically published prefixes every
  100 ms. Duplicate IDs retry idempotently; missing, stale, conflicting, or
  vanished snapshots fail closed. Transient snapshot buffers and retained
  definitions are charged separately to the finite collector budget.
- Rootful evidence:
  `docs/internals/evidence/tier-e-native-live-admission-20260923/README.md`.
  Five terminal/live pairs per emission mode passed across repeated-stack,
  stack-diverse, and class-churn profiles (60/60 short arms complete).
  Five sustained 75,000-operation ring-drop seeds per mode passed in both
  terminal and native-live arms (20/20 arms recovered all 72 paths). Median
  maximum dictionary waiting dropped from 62.34/62.41 s to 0.25/0.24 s
  (full/unique), with dependency-relative p99 discovery below 500 ms.
- The initial 10-run live pressure matrix ended explicitly incomplete due to
  a flat 256-byte-per-definition collector charge. Frame-aware stack and
  128-byte invocation accounting corrected that bound; later probes recorded
  7.7 MiB class-churn and 55.3 MiB sustained collector retained-byte peaks
  below the 64 MiB cap. The initial failure and corrected results are both
  retained. Incremental transport remains deferred.

**Remaining scope before closing this backlog item:**
- Long matched v1/v2 ring-only controls were rerun alongside hybrid on one
  clean disposable guest as diagnostic paired measurements (fifteen forks per
  emission mode, 75,000 operations, exact 72-path oracle, profiler-only CPU
  denominator; original and corrected results at
  `docs/internals/evidence/tier-e-long-matched-controls/README.md` and
  `docs/internals/evidence/tier-e-long-matched-controls-corrected-20260925/README.md`):
  hybrid and v1 incremental CPU are at parity within noise in both emission
  modes. This still does not establish long-workload comparative overhead
  against the historical host gates, which remain failed, and it does not
  close this item.
- A later review found the acceptance tooling too permissive (estimator
  accepted cross-fork/missing identities, unexpected arms, and artifact
  errors; verifier counted directories without parsing artifacts). Both tools
  were hardened with regression tests, and both retained archives were
  revalidated in full-directory and JSONL-only modes with mutation probes,
  without modifying any measurement data:
  `docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925/README.md`.
  Earlier acceptance receipts describe JSONL consistency only; the
  revalidation additionally covers the raw arm artifacts. This does not
  close this item either.
- A subsequent verifier review found malformed `fork`/`arm` and oversized CPU
  values escaping as exceptions, missing `artifact_errors` treated as clean,
  malformed/duplicate oracle paths passing, and terminal completion strings
  accepted despite failed evidence dimensions. The verifier now validates
  rows before grouping/arithmetic, requires the exact 72-path oracle, and
  requires five true evidence dimensions and an empty `failed_dimensions`
  list. Both immutable decision archives pass the strengthened full-directory
  verifier, and all eight review probes fail closed; see
  `docs/internals/evidence/tier-e-long-matched-verifier-contract-validation-20260925/README.md`.
  Neither the historical gate failure nor the operator's
  `REJECT_DEFAULT_PROMOTION` decision changes; `policy_eligibility=false`.
- Kotlin-first tooling follow-up: a developer-only `:profiler` tooling source
  set independently parses the fifteen-fork JSONL and full-directory control
  artifacts, computes seeded paired CPU statistics, normalizes per-arm rows,
  and fingerprints the tracked/untracked runtime source. Kotlin JUnit tests
  compare both archived decisions and source/row compatibility. Existing
  Python/Node entry points remain during CLI/caller parity verification;
  they are retired only when every active caller has a Kotlin replacement.
  The seven-arm split-coverage matrix and normal split-privilege CPU/coverage
  path now invoke the Kotlin tooling distribution. A rootful six-arm short
  hybrid control and explicit one-fork seven-arm 75,000-operation diagnostic
  completed with all expected paths. These source-dirty developer probes
  (`input_dirty_count=9`) are retained at
  `docs/internals/evidence/tier-e-kotlin-tooling-smoke-20260925/README.md`;
  one fork cannot produce a fifteen-pair confidence decision. Source
  fingerprints matched the historical Python
  aggregate, and both archived decisions' per-mode medians and upper bounds
  matched the prior estimator. Gradle caching and build reuse avoid recompiling
  per arm, but the specified 800 µs pacing fixes a 60-second wall-time floor
  for every 75,000-operation arm. Retire remaining compatibility scripts only
  after their exceptional/fault callers and regression coverage move to Kotlin.
  Kotlin JUnit now owns the pure long-control tooling cases; redundant Node
  test suites were retired. Three operational/fingerprint Python tests remain
  in default discovery; the historical 75-case verifier suite is still
  explicitly runnable using its archived command. Other 28 Node tests are
  retained for still-active Tier E protocol/transport script paths.
- Real **guest-host** PID reuse is now qualified for a controlled watcher-pause
  window: the registered non-root target exited, another real process received
  its host PID with a different `/proc` start time, and the rootful hybrid
  collector rejected it with `PROCESS_IDENTITY_REUSED`, zero resolved syscalls,
  and incomplete evidence. Raw proof is retained at
  `docs/internals/evidence/tier-e-real-host-pid-reuse-20260924/README.md`.
  The 256-lifetime normal-host probe remains independently unverified; no
  normal-host PID controls changed. Natural-race frequency is unmeasured.
  Existing 100 ms native MZSE live
  snapshot admission is now measured; it is not an incremental receiver
  protocol, and only a closed terminal artifact after producer closure proves
  terminal dictionary completeness.
- Pre-admission marker proof implemented and retained at
  `docs/internals/evidence/tier-e-marker-proof-20260923/README.md`: registered
  v2 sessions require the agent-authored, incarnation-bound selected version
  before constructing BPF or acknowledging ATTACH. The v1-emitting/missing-
  proof negatives fail before workload; v1 compatibility and v2 USDT/uprobe
  positives pass. The file is diagnostic self-attestation, not a cryptographic
  claim against a malicious target UID.
- The split coverage, matched, fault, and live-comparison runners now hash
  tracked and untracked runtime source before and after forks, separately
  from native/JAR and image IDs. Earlier archives cannot retroactively gain
  this evidence. The separate profiler-only CPU denominator is now measured
  per profiled arm (target `ProcessHandle.totalCpuDuration`, collector
  `process_ticks_since_start`) in the same-guest long matrix; its combined
  form remains collector-dominated and needs a production-shaped denominator
  before any gate use. Preserve the
  operator's rejection decision and `policy_eligibility=false` until addressed.
- The earlier Detekt gate is resolved using the same-toolchain pre-hybrid
  baseline (`docs/internals/evidence/tier-e-detekt-pre-hybrid-20260923.json`).
  The reviewed inventory contains no new warning rules and its profiler
  cardinality remains 445; `./gradlew build` passed before marker-proof work.

---
<!-- id: issue-20260922-214822-357-tier-e-hybrid-coverage-recovery-and-transport-meas  file: issue-20260922-214822-357-tier-e-hybrid-coverage-recovery-and-transport-meas.md -->
