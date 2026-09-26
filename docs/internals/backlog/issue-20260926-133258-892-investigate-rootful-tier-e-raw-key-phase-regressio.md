---
title: "Investigate rootful Tier E raw-key phase regression before qualification"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["profiler", "profiler-native"]
target_files:
  - "profiler-native/tier-e-benchmark-baseline.env"
  - "profiler-native/src/agent.cpp"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEBenchmarkPhaseGate.kt"
  - "docs/internals/designs/profiler/tier-e-testing-migration.md"
  - "docs/internals/evidence/tier-e-raw-key-comparability-20260926/README.md"
  - "docs/internals/evidence/tier-e-raw-key-comparability-20260926/comparison.json"
  - "docs/internals/evidence/tier-e-raw-key-comparability-20260926/raw-samples.tar.gz"
  - "docs/internals/evidence/tier-e-raw-key-comparability-20260926/SHA256SUMS"
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
1. Obtain operator approval for a new *comparable* phase contract before changing measurement or acceptance code: retain the raw-key baseline, count canonical-cache lookup independently, bound aggregate key+lookup+logical work against unchanged historical totals, and validate unique-edge separately. Do not remove weak-class guard or generation validation or claim a green gate under its old meaning.

---
<!-- id: issue-20260926-133258-892-investigate-rootful-tier-e-raw-key-phase-regressio  file: issue-20260926-133258-892-investigate-rootful-tier-e-raw-key-phase-regressio.md -->
