---
title: "Tier E WP-06: Noise budget & UNKNOWN counters"
severity: "ENHANCEMENT"
status: "open"
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
