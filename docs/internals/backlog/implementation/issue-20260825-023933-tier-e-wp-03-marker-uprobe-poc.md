---
title: "Tier E WP-03: Marker .so + uprobe + task-storage PoC (Gates G0/G1)"
severity: "ENHANCEMENT"
status: "open"
priority: high
component: "ebpf-prototype"
target_modules:
  - "ebpf-prototype"
target_files:
  - "ebpf-prototype/bpf/mazewall_context.bpf.c"
  - "ebpf-prototype/daemon/"
effort: "large"
autonomy: "supervised"
open_questions: false
dependencies:
  - "issue-20260825-023932-tier-e-wp-02-collector-prototype.md"
---

# 🟢 [Severity: ENHANCEMENT]: WP-03 — Marker + Uprobe + Task-Storage PoC (G0/G1)

**Context:** The heart of Tier E. Prove the locked handoff primitive end-to-end before any
lifecycle/trust machinery exists:

```text
marker(42)  → uprobe → task_storage[current] = 42
syscall     → sys_enter → task_storage[current] == 42 → event.context = 42
```

Both programs run **on the same task**, so write/read are serialized by construction — no
memory-ordering proof required. Do not add synchronization "for safety"; it would be dead weight.

Design reference: [tier-e-design.md §4.1–§4.3, §7, §8](../../designs/profiler/tier-e-design.md).

**Needed:**

1. `libmazewall_context.so` exporting exactly one symbol:
   ```c
   void mazewall_context_marker(uint32_t context_id) { /* intentionally empty */ }
   ```
   Non-static, default visibility, stable ABI.
2. BPF task-storage map (`BPF_MAP_TYPE_TASK_STORAGE`, value `__u32`, `BPF_F_NO_PREALLOC`).
   Requires BTF for key/value types at load time — libbpf handles this in the prototype.
3. Uprobe program at the marker symbol offset; reads `ctx_id`
   (x86_64: `ctx->di`, arm64: `ctx->regs[0]` — compile-time per-arch) and stores via
   `bpf_task_storage_get(..., BPF_LOCAL_STORAGE_GET_F_CREATE)` on
   `bpf_get_current_task_btf()`.
4. Extend WP-02's `sys_enter` program: lookup storage; missing or zero ⇒ count UNKNOWN and emit
   nothing. Attributed ⇒ ring-buffer record `{ktime_ns, tgid, tid, syscall_nr, context_id}`.
5. Bring-up attach via libbpf `bpf_program__attach_uprobe` (symbol name resolution); the raw
   perf_event path is documented in design doc §4.5 for the later FFM migration.
6. **USDT production ABI (design doc §4.1.1 / Appendix A):** wrap the marker body in
   `DTRACE_PROBE1(mazewall, context_switch, context_id)` (`sys/sdt.h`) and attach with
   `bpf_program__attach_usdt("usdt/<path>:mazewall:context_switch")`, reading the argument via
   `bpf_usdt_readarg`. This is the intended long-term interface; the plain uprobe remains the
   bring-up fallback.
7. Test driver: single platform Java thread (plain FFM downcall), deterministic sequence.

### Gate G0a — plain uprobe (must be 100% repeatable)

```text
marker(42); syscall  → event.context == 42
marker(7);  syscall  → event.context == 7
```

Single platform thread. No agent. No containers beyond the rootful test harness itself. No
concurrency.

### Gate G0b — USDT attach (must reproduce G0a byte-for-byte)

Same sequence, attachment switched to `usdt/<path>:mazewall:context_switch`. Additionally
verify the negative path: a library copy **without** the `.note.stapsdt` probe entry fails
attach loudly (`ATTACH_FAILED`), never silently all-UNKNOWN.

### Gate G1 (measurement, not pass/fail)

Benchmark ≥10 million marker invocations for **both** variants; record ns/transition,
transitions/sec, CPU cost, attached and detached (detached should be ~ns). Evaluate
skip-if-unchanged guard impact. Record numbers as an addendum section in the design doc.

**PR is done when:** G0a and G0b pass repeatedly in CI-style runs and G1 numbers for both
variants are recorded.

## Guardrails

* If userspace → uprobe → storage → sys_enter proves unreliable, **stop and escalate**. This is
  the go/no-go milestone of the whole initiative. Do not build workarounds silently.
