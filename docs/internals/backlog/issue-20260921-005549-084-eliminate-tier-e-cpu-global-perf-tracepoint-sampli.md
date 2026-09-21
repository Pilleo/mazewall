---
title: "Eliminate Tier E CPU-global perf tracepoint sampling loss"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEbpfEngine.kt"
target_symbols:
  - "TierEbpfEngine"
open_questions: false
---

# 🟡 [Severity: HIGH]: Eliminate Tier E CPU-global perf tracepoint sampling loss

**Context:**
Rootful sustained runs proved openat calls present in strace but absent from both the collector and independent raw-sys_enter audit because CPU-global perf tracepoint descriptors are subject to the host perf_event_max_sample_rate before target filtering.

**Needed:**
1. Replace CPU-global perf sampling with target-lifecycle-scoped syscall observation that cannot be consumed by unrelated host traffic and remains fail-closed for unadmitted threads.
2. Preserve the current 88-byte ring record, invocation/dictionary contract, `sys_enter` capture-domain boundary, and collector-authoritative acknowledgement.
3. Admit every already-live target platform thread before marking the session running; account for a thread that appears later as unknown/incomplete until it has a validated observer attachment.
4. Retain an attachment inventory and close every per-thread observer during terminal shutdown, detach, exec, and failed installation.
5. Add rootful regressions for threads present at attach, threads created after attach, migration, thread exit during attachment, and a host-noise stress case above `perf_event_max_sample_rate`.
6. Require an independent raw observation denominator and `strace` shadow proof for any future sustained-capacity promotion.

**Evidence:**
- Clean rootful source revision `267f701709e33f8bd34f3c10ee7ec2c7bb8e21d3`, kernel `7.2.4-x64v3-xanmod1`, and CPU set `0,2` passed the 10-second 1,000/s pilot but failed at 2,000/s.
- A 60-second 500/s raw-audit replay expected 30,600 declared `openat` operations and observed 30,597. The collector missed the exact same directory descriptors `17315`, `17316`, and `17317`; no `openat2` fallback occurred.
- The retained 2,000/s `strace` shadow shows the original missing descriptors did execute `openat`, ruling out workload-oracle error and collector/dictionary loss.
- The host `perf_event_max_sample_rate` was `25,750`. CPU-global perf tracepoint descriptors observe all host syscalls before their BPF TGID filter, so the evidence is consistent with upstream perf sampling throttling.
- See `docs/internals/evidence/tier-e-perf-tracepoint-sampling-loss-20260921.json` and the retained raw artifacts under `/tmp/tier-e-sustained-capacity-fast-master-20260921` and `/tmp/tier-e-sustained-500-audit-20260921`.
- Commit `6bd706cb30863ce6e6730afc01567f8515c77360` adds the x86_64 direct `BPF_RAW_TRACEPOINT_OPEN` backend while preserving the explicit perf compatibility path. Its focused static suite and rootful kernel smoke passed.
- A rootful diagnostic at `/tmp/tier-e-raw-tracepoint-500-audit-20260921` reconciled all 30,600 declared operations and raw-audit counts in both modes. The clean repeat at `/tmp/tier-e-raw-tracepoint-500-clean-20260921` passed full-stream but unique-edge missed four operations despite a complete 30,600-operation raw audit and zero reported ring loss. This is a distinct unclassified admission/marker gap, so this issue remains open.

**Acceptance:**
1. A rootful 60-second 500/s and 1,000/s run has zero oracle/raw-audit/strace disagreement in both full-stream and unique-edge modes.
2. Unrelated host syscall load cannot reduce target observation counts.
3. An unadmitted new or exiting thread is reported as incomplete rather than silently omitted or assigned stale exact attribution.
4. The sustained-capacity manifest distinguishes a short-pilot result from a durable multi-fork capacity decision.
5. Direct raw-tracepoint runs classify every main-program/admission mismatch by a durable per-operation reason before they are used for capacity evidence.

---
<!-- id: issue-20260921-005549-084-eliminate-tier-e-cpu-global-perf-tracepoint-sampli  file: issue-20260921-005549-084-eliminate-tier-e-cpu-global-perf-tracepoint-sampli.md -->
