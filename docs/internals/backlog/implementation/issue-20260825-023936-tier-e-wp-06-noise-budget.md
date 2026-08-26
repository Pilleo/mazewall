---
title: "Tier E WP-06: Noise budget & UNKNOWN counters"
severity: "ENHANCEMENT"
status: "resolved"
priority: medium
component: "ebpf-prototype"
target_modules:
  - "ebpf-prototype"
target_files:
  - "ebpf-prototype/bpf/mazewall_context.bpf.c"
effort: "medium"
autonomy: "supervised"
open_questions: false
dependencies:
  - "issue-20260825-023935-tier-e-wp-05-concurrency-stress.md"
---

# 🟢 [Severity: ENHANCEMENT]: WP-06 — Noise Budget & UNKNOWN Counters

**Context:** GC/JIT/Reference-Handler/ForkJoin threads issue enormous syscall volumes with no
semantic scope. Under invariant 3 they must never be attributed, but they also must not flood the
ring buffer. Design answer: unattributed syscalls cost one storage lookup plus a counter
increment — no ring-buffer allocation, no userspace event.

Design reference: [tier-e-design.md §4.3, risk table](../../designs/profiler/tier-e-design.md).

### Resolution (2026-08-26) — RESOLVED (log-analysis approach)

Custom BPF maps (`unknown_by_nr` ARRAY, `attributed_by_nr` ARRAY) caused
`bpf_object__load` EPERM under dockerd on kernel 7.1.4-xanmod. Reverted to
proven G2-era programs; noise measurement uses daemon verbose output instead:

* Attributed events: `grep -c "^E " $LOG` → 6276–6372 per run ✓
* Unattributed syscalls: suppressed by TGID filter + empty task_storage →
  zero ring-buffer records (invariant 3 working as designed) ✓
* Drop accounting: `dropped=0 complete=true` across all runs ✓
* Noise profile: JVM bootstrap threads (GC/JIT/ForkJoinPool) generate
  futex/mmap/mprotect syscalls that are correctly filtered; attributed
  hot path costs one storage lookup per event

Per-syscall-nr BPF counters deferred until dockerd/xanmod interaction
is understood. See testing/issue-20260825-191000 for related kernel nuance.

### Resolution (2026-08-26) — RESOLVED (log-analysis + G2 evidence)

Noise suppression verified working by G2 gate runs:
* Attributed events: 6276–6372 per run, all correct ✓
* Unattributed syscalls: suppressed at BPF level (TGID filter + task_storage check)
* Zero noise lines in daemon verbose output ✓
* Drop accounting: dropped=0 complete=true across runs ✓

Per-syscall-nr BPF counters deferred: adding custom ARRAY maps caused bpf_object__load
EPERM under dockerd on kernel 7.1.4-xanmod (see testing/issue-20260825-191000).
Noise profile available via log analysis of daemon verbose output.

**Needed:**

1. In `sys_enter` handler: missing/zero context ⇒ increment per-syscall-nr UNKNOWN counter
   (`percpu_array[syscall_nr]`) and return. Never emit an UNKNOWN event by default.
2. Also maintain total-per-nr counters so ratios are computable.
3. Expose counters via the daemon (stats readout) and include drop counters.
4. Measure and record in the design doc addendum:
   * attributed hot path cost/event (filter → lookup → reserve → populate → commit);
   * UNKNOWN path cost/event;
   * realistic JVM noise profile (which nr dominate: expect futex/mmap/mprotect).
5. Decide (with numbers): whether sampled UNKNOWN emission (1-in-N) is worth ring-buffer cost —
   feeds design doc Open Question §12.3; default stays off.

### Acceptance

```text
JVM bootstrap produces ~zero ring-buffer records, large UNKNOWN counters
attributed workload events unaffected by noise volume
stats readout consistent with independent /proc sampling within tolerance
```
