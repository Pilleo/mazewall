---
title: "Reject oversized provider raw frame arrays before decoding"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "experiment"
target_modules: []
target_files:
  - "experiments/hotspot-agentless/patches/profiler-epoch-handler.patch"
  - "experiments/hotspot-agentless/patches/profiler-additions/tracer/hotspot_epoch_test.go.txt"
target_symbols:
  - "loadBpfTrace"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Reject oversized provider raw frame arrays before decoding

**Context:**
Epoch ABI validation reproduced a pinned-provider panic when Frame_data_len is 3073: loadBpfTrace slices a 3072-word backing array without checking its capacity. The isolated regression fails with slice bounds out of range.

**Needed:**
1. Validate raw frame count and declared frame count before slicing or borrowing a trace from the pool; retain a binary-packet regression.

**Implemented; review pending:** the packet parser rejects data count >3072 and
frame count >data count before pool access or slicing. The real raw-packet
regression reproduced the original bounds panic and now passes with `-race`;
the separate binary epoch/error/pool-reset test also passes. Evidence is included
in the final host/upstream verification for the epoch implementation.

---
<!-- id: issue-20261001-152252-722-reject-oversized-provider-raw-frame-arrays-before-  file: issue-20261001-152252-722-reject-oversized-provider-raw-frame-arrays-before-.md -->
