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
sources. The operator approved the [bounded allocator contract](../../plans/2026-10-18-hotspot-correlation-allocator-contract.md).
Task1 implements the correction; Task2 supplies byte-reproducible full bundles,
public-native and legacy export receipts in the current bounded profile.
See [allocator evidence](../../plans/2026-10-18-hotspot-correlation-allocator-evidence.md#task2-full-assets-and-public-native-qualification).

**Needed:**

Acceptance criteria retain their original wording; current dispositions follow.
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

**Task1 disposition (2026-10-18):** one shared `correlation_ids` ARRAY/u32/u64/1,
32 bounded CAS attempts, positive IDs through INT64_MAX; unavailable zero without
new cache/definition publication on missing/invalid/saturated/contended state.
Actual-C GREEN/clock-mutant RED, edge/helper-fault checks, real eight-CPU allocator
execution, acquired loader-map ownership and non-time resolver/guard controls have
receipts. Both embedded architectures/layouts were generated from ordered patch
`8212ee25dc2fb58f070c235e30eb01666a492286763df318d90576b742372a4c`.
R1 corrects indistinguishable reordered-definition fixtures with exact binding and
rejected-redelivery preservation assertions plus a failing definition-swap control.
This changes only one hashed test input from the initial `962b…` graph; native
source/layout/embedded bytes remain identical and isolated native receipts are
reused. Fresh complete snapshots prove all fourteen current pin advancements
followed current-graph qualification. The initial graph's retained pre-advancement
snapshots cover only four consumers, so its all-fourteen chronology is unproved;
the new receipts do not retroactively establish it.

**Task2 disposition (2026-10-18):** two independent locked Go1.26.0 builds produce
identical helper `d0cc2a1d…9f3f93c` and every full-OCI bundle byte. Independent
descriptor traversal and old-pin refusal pass. Eight public-native cases execute
with zero skips/failures; selected802/802/802 conservation, exact task/non-time
binding, physical drain, zero-retention502discarded=502received/pending0, four
unknown lanes/globalfalse and policy refusal pass. Actual rendered and serialized
pdata checks preserve1,2^53+1,INT64_MAX. One receipted six-case experimental control
includes four carrier TIDs/256marked observations without observed cross-task
reference alias. Both dense600 diagnostics still reject; all partial evidence stays.

**Completion boundary:** this issue stays open for criterion3's complete admitted
context/publication/eviction-interleaving characterization. Task2 supplies real
helper/JVM/transport coupling, not exhaustive same-key producer interleavings or
active perf/NMI safety. The supported synchronous kprobe profile, buffers,
deadlines and fatal errors remain. Kubescape's agent-assisted completed Tier E
content-hash importer still needs a separately scoped agentless bridge; no
end-to-end import/deployment was run. Original failure and the unproved historical
trigger remain visible; no JIT/GC/Loom/reliability promotion. The existing backlog
index row correctly remains open; no archive is justified by these scoped results.

---
<!-- id: issue-20261018-035018-153-replace-clock-only-native-correlation-id-allocatio  file: issue-20261018-035018-153-replace-clock-only-native-correlation-id-allocatio.md -->
