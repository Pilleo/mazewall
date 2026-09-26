---
title: "Investigate rootful Tier E raw-key phase regression before qualification"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: ["profiler", "profiler-native"]
target_files:
  - "profiler-native/tier-e-benchmark-baseline.env"
  - "profiler-native/src/agent.cpp"
  - "profiler-native/CMakeLists.txt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEBenchmarkPhaseGate.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierENativeScenarioRunner.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierERootfulGateRunner.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierERootfulGateResultVerifier.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEToolsMain.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierEBenchmarkPhaseGateTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierERootfulGateResultVerifierTest.kt"
  - "docs/internals/designs/profiler/tier-e-testing-migration.md"
  - "docs/internals/designs/profiler/tier-e-split-privilege-deployment.md"
  - "docs/internals/evidence/tier-e-raw-key-comparability-20260926/README.md"
  - "docs/internals/evidence/tier-e-raw-key-comparability-20260926/comparison.json"
  - "docs/internals/evidence/tier-e-raw-key-comparability-20260926/raw-samples.tar.gz"
  - "docs/internals/evidence/tier-e-raw-key-comparability-20260926/SHA256SUMS"
  - "docs/internals/evidence/tier-e-comparable-phase-v2-20260926/README.md"
  - "docs/internals/evidence/tier-e-comparable-phase-v2-20260926/SHA256SUMS"
  - "docs/internals/evidence/tier-e-comparable-phase-v2-20260926/paired-decision.json"
  - "docs/internals/evidence/tier-e-comparable-phase-v2-20260926/paired-raw.tar.gz"
  - "docs/internals/evidence/tier-e-comparable-phase-v2-20260926/rootful-gate-raw.tar.gz"
  - "docs/internals/evidence/tier-e-comparable-phase-v2-20260926/legacy-v2-denial.txt"
target_symbols:
  - "RAW_KEY_NS"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Investigate rootful Tier E raw-key phase regression before qualification

**Context:**
Clean Kotlin rootful gate reached the three-sample native phase check: full-mode raw_key_ns samples 1718210, 759277, 770918 ns; median 770918 exceeds the retained FULL_RAW_KEY_NS 440912 × 125% = 551140 ns. Fixture and fresh-JVM tests passed. No baseline rewrite or promotion is justified.

**Additional controls:** The current host Release build recorded 554860, 530319,
588132 ns (median 554860); a separate host Debug build recorded 1415879,
1411971, 1379663 ns (median 1411971). Historical gate commit `ce31883b`
configured `CMAKE_BUILD_TYPE=Debug`, so merely switching the gate to Release
would change its measurement contract rather than qualify the retained Debug
baseline. The rootful guest Debug median 770918 ns still fails the same fixed
limit. These are diagnostic host/guest controls, not a new baseline.
Current host and rootful gate image both report `c++ (Ubuntu
15.2.0-16ubuntu1) 15.2.0`; compiler-version mismatch alone does not explain
the within-host Release/Debug difference or the fixed Debug gate failure.

**2026-09-26 controlled isolation:** In a single pinned rootful Debug image,
historical/current full-stream native agents (five alternating CTest pairs,
all zero exit and 514 captures) had `raw_key_ns` medians 360993/640989 ns.
Historical full-stream did **not** query the canonical cache, while current
full-stream had 504 cache hits and 10 misses; `c0b9615a` expanded lookup to
full-stream. Current cache enabled/disabled raw medians were 1331802/35556 ns,
but disabling cache increased logical metadata median to 20924007 ns. A
diagnostic-only nested-clock removal still had raw median 1174091 ns, so
instrumentation is not the whole cause. The aggregate key+logical phase fell
from historical 7565972 to current 1108453 ns. This is a changed **metric
boundary**, not evidence that reducing the cache's correctness checks would
fix a production slowdown. See
[`tier-e-raw-key-comparability-20260926`](../../evidence/tier-e-raw-key-comparability-20260926/README.md)
and its checksum-sealed raw samples. The original per-phase gate still fails;
no baseline or policy decision was revised.

**Needed:**
1. Under the operator-approved version-two contract, count canonical-cache lookup independently of key construction, bound aggregate key+lookup+logical work against the unchanged historical totals, validate unique-edge separately, and reject missing or unequal work. Preserve weak-class guards, generation checks, the old baseline, and legacy replay.

**Resolution:** `agent.cpp` reports disjoint raw-key construction and cache
lookup durations, a version-two emission label, and its existing correctness
counts without changing capture or cache behavior. The v2 Kotlin gate requires
three mode-bound samples with 514 captures, zero failures, active cache
population summing to 514, positive work, and the unchanged historical
stack/key and aggregate-total limits. The legacy gate explicitly rejects v2
records. Thirty-nine native CTests and the profiler host suite passed. A clean
rootful Docker gate completed with version-two full/unique totals of
1,355,282/1,344,775 ns below fixed limits 13,412,790/2,119,025 ns. A
predetermined 15-pair same-image Debug control retained all 60 CTest arms,
with beneficial paired-bootstrap 95% intervals for both comparable native
totals. All immutable inputs and raw/decision rows are checksum sealed in
[`tier-e-comparable-phase-v2-20260926`](../../evidence/tier-e-comparable-phase-v2-20260926/README.md).
`policy_eligibility=false` and `REJECT_DEFAULT_PROMOTION` stand.

---
<!-- id: issue-20260926-133258-892-investigate-rootful-tier-e-raw-key-phase-regressio  file: issue-20260926-133258-892-investigate-rootful-tier-e-raw-key-phase-regressio.md -->
