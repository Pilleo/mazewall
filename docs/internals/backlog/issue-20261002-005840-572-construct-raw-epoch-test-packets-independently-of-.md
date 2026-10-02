---
title: "Construct raw epoch test packets independently of race decoder copies"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "experiment"
target_modules: []
target_files:
  - "experiments/hotspot-agentless/patches/profiler-additions/tracer/hotspot_epoch_test.go.txt"
  - "experiments/hotspot-agentless/Makefile"
  - "experiments/hotspot-agentless/results/**"
target_symbols:
  - "epochTracePacket"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Construct raw epoch test packets independently of race decoder copies

**Context:**
The expanded race gate found epochTracePacket writing to traceFromRaw, which copies raw bytes under race builds. The origin remained zero; the oversized-packet test could pass for an unrelated decoder rejection.

**Needed:**
1. Encode raw header fields directly and require the exact oversized frame-count rejection; verify both race and non-race selected graphs.

**Implemented evidence (2026-10-02):**
The raw fixture now encodes wire fields at generated layout offsets without
calling the decoder. The oversized-array case requires its actual bounds error.
The original race test failed repeatedly with origin zero; corrected race and
non-race tracer tests pass, and the expanded `make verify` gate includes them.
Keep open for review of the scoped epoch verification evidence.

---
<!-- id: issue-20261002-005840-572-construct-raw-epoch-test-packets-independently-of-  file: issue-20261002-005840-572-construct-raw-epoch-test-packets-independently-of-.md -->
