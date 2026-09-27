---
title: "Diagnose intermittent Tier E pre-admission observation gaps under sustained load"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/TierEKernelSmoke.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEbpfEngine.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEBpfProgramBuilder.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/TierEAdmissionAuditSummary.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/TierEBpfProgramBuilderTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/stress/TierEAdmissionAuditSummaryTest.kt"
  - "config/detekt/warnings/profiler.txt"
  - "docs/internals/designs/profiler/tier-e-testing-migration.md"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/TierEMatchedBenchmark.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierENodeNoiseProbe.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierENodeNoiseQualification.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierENodeNoiseQualificationTest.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthySettings.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthyGuestCommand.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierETrustworthyContainerOwner.kt"
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
counts the low 32-bit `openat` event at the start of the main program, keyed
by its syscall descriptor, *before* the existing full-width syscall check and
subsequent admission gates. The diagnostic map value grows from 144 to 152
bytes; `exact_admission_audit_contract_version=2` emits both the per-descriptor
low32 count and a bounded missing-descriptor sample. The old full-width
handler, rejection and submission counters remain independently available.
The per-descriptor comparison can distinguish absence at main-program ingress
from a full-width/argument diversion or a later admission rejection. Aggregate
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
