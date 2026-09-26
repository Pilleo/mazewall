---
title: "Investigate rootful Tier E raw-key phase regression before qualification"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "profiler-native/tier-e-benchmark-baseline.env"
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

**Needed:**
1. Compare pinned native/compiler/source identities and repeated isolated rootful phase samples against retained baseline; identify a reproducible cause or explicitly classify host-specific drift before any gated decision.

---
<!-- id: issue-20260926-133258-892-investigate-rootful-tier-e-raw-key-phase-regressio  file: issue-20260926-133258-892-investigate-rootful-tier-e-raw-key-phase-regressio.md -->
