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

**Needed:**
1. Compare pinned native/compiler/source identities and repeated isolated rootful phase samples against retained baseline; identify a reproducible cause or explicitly classify host-specific drift before any gated decision.

---
<!-- id: issue-20260926-133258-892-investigate-rootful-tier-e-raw-key-phase-regressio  file: issue-20260926-133258-892-investigate-rootful-tier-e-raw-key-phase-regressio.md -->
