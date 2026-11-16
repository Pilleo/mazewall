---
title: "Investigate per-CPU Tier E uprobe BPF execution fanout"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEbpfEngine.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/"
target_symbols:
  - "attachMarkerAtOffset"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Investigate per-CPU Tier E uprobe BPF execution fanout

**Context:**
Retainedcycle005 has184native stackcaptures but1472SET and4416CLEAR markerBPF receipts, exactly8executions for each oneSET/threeCLEAR native scope publication; all40coldopens show8sequential sameIDSETs. attachMarkerAtOffset creates perfuprobes and attaches identical BPF once perCPU, with a comment claiming no siblingreplay. Linuxv6.18 trace_uprobe.c runs BPF before perCPU perf-events filter; actualsource kernel7.2.11-specific mechanism needs verification. Repetition does not itself prove historicalmissingopens.

Evidence: immutable cycle005 MZBD has eight committed SETs per cold invocation,
strictly increasing sequences and the same generation; the final state matches
each sole main open with no intervening marker. For invocation 85048942395393,
SET ordinals 12254–12261 carry sequences 9–16, and main ordinal 12272 uses 16/1.
`publish_stack_marker` makes one native marker call per publication
(`profiler-native/src/agent.cpp:566–583`); the 184 captured scopes each publish
admission-clear/exact-set/exit-clear/generic-clear. The attachment loop is at
`TierEbpfEngine.kt:977–1012`.

The [Linux v6.18 reference source](https://github.com/torvalds/linux/blob/v6.18/kernel/trace/trace_uprobe.c#L1404-L1441)
executes the BPF array before checking the current-CPU perf-event list. This is
a source-qualified mechanism hypothesis, not proof for the captured kernel.
Kernel/version-qualified reproduction is still required; no historical
missing-open or performance cause is established. Follow-up K only documents
this investigation and repairs the private classifier; **no attachment, native,
BPF or lifecycle implementation is changed here**. Future implementation remains
scoped to the engine and its tests listed above, under separate authorization.

**Needed:**
1. Independently reproduce attachment multiplicity with owned test-only controls, check exactkernel behavior and compare allowedCPU/eventcounts; if proven fix attachment lifecycle once without losing multithread/migration coverage or changing defaultbackend. Preserve existing captured workload and historical evidence.

---
<!-- id: issue-20261104-101647-045-investigate-per-cpu-tier-e-uprobe-bpf-execution-fa  file: issue-20261104-101647-045-investigate-per-cpu-tier-e-uprobe-bpf-execution-fa.md -->
