---
title: "Replace clock-only native correlation ID allocation with reviewed uniqueness contract"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files:
  - "profiler/hotspot-agentless/patches/profiler-epoch-capture.patch"
target_symbols:
  - "unwind_stop"
open_questions: true
---

# 🟡 [Severity: MEDIUM]: Replace clock-only native correlation ID allocation with reviewed uniqueness contract

**Context:**
The original failed capture has nine events sharing correlation ID
**516936189570219** for PID **621929**, TIDs **622909 / 622910**. Its fatal
`stack definition task identity mismatch` and
`terminal_accounting_complete:false` remain correct refusals.

The unchanged production `unwind_stop` miss path uses `bpf_ktime_get_ns()` alone
for an ID consumed by ID-only resolver dictionaries. Actual included C under
controlled equal clocks has **three expected uniqueness RED exits 1**: distinct
task, same-task distinct frame, same-task distinct epoch. Same-key replay passes
in every case; a distinct-clock control passes with exit 0. The initial diagnostic
compiler failure is not that RED. This is a sufficient source defect, not proof
of the historical helper values, real map concurrency or live transport behavior.

See the [source/receipt-backed identity evidence](../../plans/2026-10-18-hotspot-correlation-identity-evidence.md)
for exact source lines/hashes, raw artifact paths, commands and boundaries.
The authored target is the dispatcher patch above, not generated `build/deps`
sources. **Runtime correction and its exact contract await operator design review.**

**Needed:**
1. Review and approve shared same-map-lifetime **positive non-reusing atomic ID
   allocation**, reserved zero and signed legacy export compatibility (at most
   `INT64_MAX`). Specify saturation and explicit bounded-contention failure, with
   no wrap/reuse, unbounded spin or time/random fallback. All variants sharing the
   LRU must share lifetime; do not reset while resolver/definitions/waiters/pending
   events can retain IDs. New map/layout and exact failure/publication semantics
   remain design questions, not choices made by filing this issue.
2. Preserve the full key, uint64 value, wire/ABI, buffers and existing fatal guards.
   Keep occurrence BOOTTIME/deadlines and membership-based two-cycle pruning
   independent of opaque ID magnitude. Preserve EPERM/EACCES propagation and
   immutable occurrence binding; no resolver namespace change is authorized here.
3. After approval, demonstrate actual-C RED/GREEN/mutant, same-key replay and
   task/frame/epoch distinction, reserved zero, saturation and bounded contention.
   Prove real shared-map/context ownership and multi-CPU concurrency for supported
   variants; characterize publication/output failure, eviction and admitted
   interleavings. Atomic uniqueness alone cannot fix misbound scratch/return data.
4. Check immutable resolver/reordered-definition/waiter, recovery and pruning
   behavior using non-time IDs; preserve task/epoch/frame fatal and guard-removal
   negative checks. Legacy overwrite hardening is a separate review concern, not
   a substitute for allocator uniqueness.
5. Renew current-source qualification **before fourteen pin-consumer advancement**;
   derive the actual ordered-input hash. Require reproducible assets, both embedded
   architectures/generated layouts, full byte/OCI receipts, legacy rendered/pdata
   and ABI checks, and actual public-native evidence. Keep current locked pins and
   historical artifacts until that separately approved transition is qualified.

**Completion boundary:** this issue is open. The documentation records an approval
request, not an implemented allocator or JIT/GC/Loom/reliability qualification.
Original failure and the unproved historical trigger must remain visible.

---
<!-- id: issue-20261018-035018-153-replace-clock-only-native-correlation-id-allocatio  file: issue-20261018-035018-153-replace-clock-only-native-correlation-id-allocatio.md -->
