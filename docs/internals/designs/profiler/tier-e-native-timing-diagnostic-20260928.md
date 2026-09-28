# Tier E Native Timing Diagnostic Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Identify whether the retained Tier E native-timing slowdown correlates with off-CPU delay, capture CPU work, or measured host constraints using exactly one non-qualifying, predeclared 36-fork experiment.

**Architecture:** Add an opt-in, bounded native capture-clock diagnostic, keeping existing `benchmark-phases` metrics and default capture behavior intact. Execute original Debug agent A, diagnostic build B (disabled), and the same diagnostic build C (enabled) in isolated fresh JVM forks with independent host telemetry and immutable provenance. Analyze raw results separately from the qualification phase gate.

**Tech Stack:** C++20/JVMTI, CMake/CTest, Kotlin tooling, Docker rootful OCI, Linux procfs/cgroups, JDK 25, Gradle 9.6.1.

**Spec:** [`docs/internals/evidence/tier-e-generic-native-phase-offline-comparison-20260928/README.md`](../../evidence/tier-e-generic-native-phase-offline-comparison-20260928/README.md) and the operator-approved 36-fork protocol in the conversation of 2026-09-28.

## Global Constraints

- Historical `8a0d6d32` first-phase terminal and its runner/volume stay immutable and `INCOMPLETE`.
- Historical unique `GetStackTrace` limit remains **2,307,381 ns**; this campaign never satisfies/replaces `TierERootfulPhaseEvidence`, aggregate, or default promotion.
- Kernel `7.0.0-34-generic` and config `e75c0adab6020e72844d7431cf801a02b94ed32efc251c3b0fb80c021d065180`; exact image `sha256:fc5bf5e14796e5187be873b84c1ce4049ce240415d93e4bdc8307fbbe0870c56`; container CPU set `0,2`; 100 iterations/fork.
- A uses retained Debug agent `ff1e640c079de05ba407eab963cfb8939553e7f49390e60fdb20645faa3f790a` and workload class `12ac99613bffb53253c02545063b37f01eb1860657233acf7879f90365d7c0e6`; B and C use the same, separately hash-bound diagnostic binary.
- 36 fresh forks maximum, six fixed blocks: A-B-C / B-C-A / C-A-B / C-B-A / B-A-C / A-C-B. Odd blocks full then unique; even blocks unique then full. Three-minute timeout per fork, 45-minute total acquisition cap. No skipped, replaced, or repeated samples.
- Verify all identity, telemetry and workload contracts before interpretation; a slow sample is retained, an identity/missing-telemetry failure stops acquisition as `INCOMPLETE`.
- Never modify `.gitignore`, `docs/internals/backlog/README.md`, `.omo/`, or operator-owned untracked issues. Scoped code and evidence only; no new dependencies, ABI change or capture policy change.

## Pre-acquisition readiness (separate from the 36-fork budget)

Before starting another diagnostic acquisition after the `005086b9` parser
stop, run a separate **six-fork, non-qualifying** readiness lane on the same
clean committed source and pinned image. Its order is A/full, A/unique,
B/full, B/unique, C/full, C/unique; each arm/mode runs once and has its own
three-minute timeout. The six-fork lane has a 20-minute acquisition cap and
stops on the first incomplete workload, missing clock, changed image/binary,
cgroup quota/affinity mismatch, telemetry failure, or failed export. It
does not estimate A/B or B/C performance, replace the 36-fork comparison,
or change the historical `GetStackTrace` acceptance bound. Every sample is
retained, including slow ones.

The acquisition CLI requires the separately owned readiness receipt. Before
allowing a 36-fork run, verify its `READINESS_COMPLETE`,
`policy_eligibility=false` terminal, source revision and digest, exact
kernel/config/image/agent/workload/toolchain IDs, six ordered raw sample
hashes, complete host telemetry and byte-matched owner export. A failed
readiness run blocks the campaign; it cannot be extended or silently
restarted. Preserve it as a separate `INCOMPLETE` terminal.

From a clean source clone, pass fresh distinct output directories:

```bash
DOCKER_HOST=unix:///var/run/docker.sock ./gradlew :profiler:tierENativeTimingReadiness -PtierEOutput=/absolute/readiness --max-workers=1
DOCKER_HOST=unix:///var/run/docker.sock ./gradlew :profiler:tierENativeTimingDiagnostic -PtierEOutput=/absolute/diagnostic -PtierEReadiness=/absolute/readiness --max-workers=1
```

The second command is admitted only after independently verifying the first
command's source-bound receipt and completing all host build checks. Both
lanes retain only diagnostic evidence; neither promotes policy eligibility.

## File and interface map

- `profiler-native/include/capture_timing_diagnostic.hpp`: bounded, opt-in clock accounting (`CaptureTimingDiagnostic::begin()/finish()`, `snapshot()`), fail-closed clock and migration observations; no per-capture allocation.
- `profiler-native/tests/capture_timing_diagnostic_test.cpp`: test disabled-path no calls, valid deltas, failed clocks and counter overflow.
- `profiler-native/src/agent.cpp`: parse explicit `capture-timing-diagnostic`, measure synchronous `GetStackTrace` only when enabled and print separate diagnostic summary; leave the existing `get_stack_trace_ns` metric intact.
- `profiler-native/CMakeLists.txt`: register test and two CTest fixture variants. Both run `AgentWorkload 0 platform 100` in full and unique modes; C includes the explicit diagnostic option.
- `profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierENativeTimingDiagnostic.kt`: pure fixed schedule and fail-closed sample parser, separate from the rootful phase receipt.
- `profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierENativeTimingDiagnosticRunner.kt`: source-clean, pinned-image three-arm acquisition, owned resource lifecycle, per-fork host telemetry and non-qualifying receipts.
- `profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierENativeTimingReadiness.kt`: fail-closed, read-only cross-check of readiness plan, fixed six-sample schedule, raw sample/telemetry hashes and owner export before acquisition.
- `profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierENativeTimingDiagnosticTest.kt`: schedule/stop/identity/missing-telemetry and non-promotion tests.
- `config/testing/tier-e/rootful-gate-pinned.json`: existing exact image/CPU profile, checked against diagnostic constants; per-run `plan.json` and `schedule.jsonl` seal identities, sample budget and timeout before acquisition.
- `docs/internals/evidence/tier-e-native-timing-diagnostic-20260928/`: predeclared identity/schedule followed by raw artifact checksums, all results and a bounded decision; scoped backlog and testing-design links.

---

### Task 1: Native measurement contract

- [ ] Write a native test that drives the wished-for bounded clock helper with deterministic time and CPU IDs; assert zero invocations while disabled, one valid elapsed/CPU sample, failures invalidate the snapshot, and no integer overflow is accepted.
- [ ] Register and run `ctest --test-dir profiler-native/build -R '^capture_timing_diagnostic_test$' --output-on-failure` and confirm it fails because the helper does not exist.
- [ ] Implement the header with confined per-call data, atomic aggregate counters and explicit snapshot validity; never allocate or write logs inside the synchronous call.
- [ ] Run the test again; compile the native agent; check valid sample count exactly equals attempted count.
- [ ] Add an opt-in agent option and a distinct summary line; add red/green end-to-end CTest assertions verifying A/B omit it and C emits it with 514 captures and no clock error. Preserve old `tier-e-benchmark:` rows verbatim.
- [ ] Commit native source/tests only after tests pass.

### Task 2: Three-arm acquisition and validator

- [ ] Write Kotlin tests for all 36 deterministic schedule tuples, missing/corrupt identity, missing counters, failed/timeout sample, no retry, unchanged qualification verdict and `policy_eligibility=false`.
- [ ] Run `./gradlew :profiler:test --tests '*TierENativeTimingDiagnosticTest'` to verify red.
- [ ] Implement pure parser first, then a separate CLI using existing rootful ownership patterns; A is copied from the retained runner with SHA verification and a fresh owned destination, B/C use one diagnostic binary. Verify the built class hash equals the retained workload hash; require the exact pinned image before creating a volume. Never mount or alter the stopped qualification runner/volume.
- [ ] Collect CPU set, effective affinity/migration, per-thread timing, cgroup `cpu.stat`, host `/proc/pressure/cpu`, and frequency/thermal snapshots where available before/after each JVM; mark optional sensors unavailable explicitly. Preserve both standard streams, nonzero exit codes, raw host observations and per-sample metadata; no modification to legacy phase receipts.
- [ ] Re-run focused tests, `:profiler:test`, `:profiler:ktlintCheck`, Detekt warning ratchet and `./scripts/adkw check-backlog`; then `./gradlew build --max-workers=1`. Commit complete non-qualifying diagnostic tooling.

### Task 3: Predeclare and acquire once

- [ ] In a source-clean worktree/clone of the final committed diagnostic source, verify original A hash, new B/C hashes, image tag-to-ID binding, kernel/config, JDK/compiler, CPU IDs and clean source digest; write a sealed immutable plan and 36-row schedule **before** the first Docker fork.
- [ ] Run the fixed campaign once: three minutes per fork, 45 minutes total, stop on first contract/integrity failure, never stop merely because timing exceeds the historical limit. Retain owned containers/volumes on failure.
- [ ] Verify all 36 sample hashes and host provenance, parse without discarding outliers. Compare A/B (disabled-path overhead) and B/C (instrumentation effect) per mode/block; report each raw sample and whether the previous slowdown reproduced, without retroactive acceptance.
- [ ] Seal `README.md`, manifest, raw archives and `SHA256SUMS`. Update scoped issue/design; run `./scripts/adkw check-backlog` and checksum verification. Commit evidence without staging operator edits.
