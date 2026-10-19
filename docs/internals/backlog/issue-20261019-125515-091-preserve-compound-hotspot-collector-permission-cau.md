---
title: "Preserve compound HotSpot collector permission causes across schema3 wire"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: ["profiler", "hotspot-bridge"]
target_files:
  - "profiler/hotspot-agentless/internal/capture/observed.go"
  - "experiments/hotspot-agentless/internal/capture/observed.go"
  - "hotspot-bridge/wire/decoder.go"
target_symbols:
  - "runObserved"
open_questions: false
---

# 🟡 [Severity: HIGH]: Preserve compound HotSpot collector permission causes across schema3 wire

**Context:**
PR540 actual producer EIO plus EACCES probe retains the native error tree but emits only errno5; the SDK loses errors.Is(EACCES). Evidence: build/kubescape-dev/pr540-review-20261019/actual-producer-joined-errno-probe.log. Approved bounded additive schema3 errno_causes fix; no release publication.

**Needed:**
1. Add RED SDK and real producer-to-decoder regressions for joined EIO and permission causes, then implement bounded validated schema3 causes without changing strict encoding.

---
<!-- id: issue-20261019-125515-091-preserve-compound-hotspot-collector-permission-cau  file: issue-20261019-125515-091-preserve-compound-hotspot-collector-permission-cau.md -->
