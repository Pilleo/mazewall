---
title: "Reject oversized native trace custom-label counts before allocation and indexing"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/hotspot-agentless/patches/profiler-epoch-handler.patch"
  - "experiments/hotspot-agentless/patches/profiler-epoch-handler.patch"
  - "profiler/hotspot-agentless/patches/profiler-additions/tracer/hotspot_epoch_test.go.txt"
  - "experiments/hotspot-agentless/patches/profiler-additions/tracer/hotspot_epoch_test.go.txt"
target_symbols:
  - "loadBpfTrace"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Reject oversized native trace custom-label counts before allocation and indexing

**Context:**
Issue042 reader-accounting review confirmed that loadBpfTrace indexes the fixed ten-label native array using unbounded Custom_labels.Len after metadata/pool acquisition; a valid-size record with count11 can panic, and very large counts can request excessive map capacity. The bound must be validated before allocation or indexing. Preserve existing frame/epoch validation and both native overlay mirrors.

**Needed:**
1. Add a real parser regression for count11, then reject counts above the actual native label-array capacity before metadata access or pool acquisition; retain zero/full-capacity acceptance and check a large uint32 count only after the guard is present.

**Investigation:**
- Prerequisite found during issue042's native reader-attribution review. The parent
  verified the current prepared `tracer.go:1110–1183`: frame/epoch validation is
  present, but `Custom_labels.Len` is used for allocation and fixed-array indexing
  without a capacity check.
- Run only the bounded count11 regression against the old parser. Do not execute
  its `MaxUint32` case or the full new test before the guard; allocation can be
  excessive. Test setup failures are not behavioral RED evidence.

**Side effects:**
- Preserve both source mirrors, existing frame/epoch checks and valid labels.
  No new payload/wire ABI, dependency, CLI option or accounting-completeness flag.
- The hashed native overlay changes. Derive its real new patch ID after the
  scoped inputs are final; update live acceptance consumers before publication.
  Never relabel or overwrite the accepted b9 baseline or its consumed receipts.

---
<!-- id: issue-20261103-222740-448-reject-oversized-native-trace-custom-label-counts-  file: issue-20261103-222740-448-reject-oversized-native-trace-custom-label-counts-.md -->
