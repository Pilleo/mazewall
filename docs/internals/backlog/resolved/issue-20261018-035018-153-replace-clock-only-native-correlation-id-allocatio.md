---
title: "Replace clock-only native correlation ID allocation with reviewed uniqueness contract"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files:
  - "profiler/hotspot-agentless/patches/profiler-epoch-capture.patch"
target_symbols:
  - "unwind_stop"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Replace clock-only native correlation ID allocation with reviewed uniqueness contract

**Context:**
The original failed capture has nine events sharing correlation ID
**516936189570219** for PID **621929**, TIDs **622909 / 622910**. Its fatal
`stack definition task identity mismatch` and
`terminal_accounting_complete:false` remain correct refusals.

At investigation time the production `unwind_stop` miss path used `bpf_ktime_get_ns()` alone
for an ID consumed by ID-only resolver dictionaries. Actual included C under
controlled equal clocks has **three expected uniqueness RED exits 1**: distinct
task, same-task distinct frame, same-task distinct epoch. Same-key replay passes
in every case; a distinct-clock control passes with exit 0. The initial diagnostic
compiler failure is not that RED. This is a sufficient source defect, not proof
of the historical helper values, real map concurrency or live transport behavior.

See the [source/receipt-backed identity evidence](../../../plans/2026-10-18-hotspot-correlation-identity-evidence.md)
for exact source lines/hashes, raw artifact paths, commands and boundaries.
The authored target is the dispatcher patch above, not generated `build/deps`
sources. The operator approved the [bounded allocator contract](../../../plans/2026-10-18-hotspot-correlation-allocator-contract.md).
Task1 implements the correction; Task2 supplies byte-reproducible full bundles,
public-native and legacy export receipts in the current bounded profile.
See [allocator evidence](../../../plans/2026-10-18-hotspot-correlation-allocator-evidence.md#task2-full-assets-and-public-native-qualification).

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

**Resolution (Task3, 2026-10-18):** the remaining finite criterion3 gate passes
through the included complete production dispatcher/cache/send path on a real
TGID-filtered kprobe. Synthetic valid task/frame entry seeds are not JVM metadata
proof. Actual allocator/LRU256/per-CPU scratch+return map IDs/layouts, helper CPUs0/1,
repeat hits, concurrent distinct keys and two equal-key misses are checked. Real
LRU pressure selects an absent key; old48/new356 retain exact independent packet
bindings, counter355→356 without reset. Native saturation preserves8hits while a
new miss returns0/no definition. **376admitted=376observed,366exact native definition
packets,0reported loss**;9unread perf packets drain after quiesce/detach, before
reader/callback joins, final samples and checked program/all21map retirement.
See [finite native receipts and context limits](../../../plans/2026-10-18-hotspot-correlation-allocator-evidence.md#task3-finite-native-cache-publication-and-drain).

| Acceptance | Disposition |
|---|---|
|1 reviewed bounded non-reusing allocation/lifetime|Approved contract and Task1/R1 implementation/edge receipts; no reset/API/namespace change.|
|2 key/ABI/budgets/fatal immutable binding|Unchanged production bytes; Task1 guards and Task2 exact non-time/public/legacy results retained.|
|3 actual-C/shared ownership/concurrency/publication/eviction/context|Reviewed Task1 native/source/host-fault proofs plus Task3 complete-path native finite cases/drain; no modeled algorithm.|
|4 resolver/reordered/waiter/recovery/pruning/negative controls|Reviewed discriminating R1 and original native error/guard controls retained; old definition remains exact across actual native eviction/republish.|
|5 source-before14pins/full assets/native public/compatibility|Current8212 full chronology and Task2 byte-equal locked helper/OCI/public8/legacy receipts reused only after exact equality audit.|

The scoped allocator correction is complete; independent controller/final review
and integration remain separate. Active perf/NMI nesting and exhaustive schedules
are unsupported scopes, not a promise of scratch leases. Existing synchronous
interpreter/Serial profile, budgets/deadlines/fatal guards remain. Original capture
failure/historical trigger and older962b all14chronology remain unproved. There is
no JIT/GC/Loom/TID-lifetime, dense/global/loss/reliability promotion. Kubescape's
completed agent-assisted TierE content-hash importer still requires a separate
agentless bridge; no deployment/import or node-agent mutation occurred. Deferred
MinorR2/M1 and USER_NOTIF shutdown issue20261003-131111-752 retain their owners.

---
<!-- id: issue-20261018-035018-153-replace-clock-only-native-correlation-id-allocatio  file: issue-20261018-035018-153-replace-clock-only-native-correlation-id-allocatio.md -->
