---
title: "Tier E WP-14: FFM loader/control-plane migration (libbpf-free runtime)"
severity: "ENHANCEMENT"
status: "open"
priority: medium
component: "platform"
target_modules:
  - ":platform"
target_files:
  - "platform/src/main/kotlin/io/mazewall/ffi/internal/SyscallInvoker.kt"
  - "ebpf-prototype/"
effort: "xlarge"
autonomy: "supervised"
open_questions: false
dependencies:
  - "issue-20260825-023940-tier-e-wp-10-oracle-comparison.md"
---

# 🟢 [Severity: ENHANCEMENT]: WP-14 — FFM Loader / Control-Plane Migration

**Context:** Locked operator decision: C + libbpf proved buildability (WP-02..05); the shipped
implementation substrate is FFM/Kotlin ("fast for experiments, futureproof for implementation").
No long-lived C daemon in production. Invariants 5–7 continue to hold: no bpffs pinning, one
map set per epoch, daemon death ⇒ DEAD.

Design reference: [tier-e-design.md §4.1.1, §4.5, §10](../../designs/profiler/tier-e-design.md).

### Scope update (2026-08-25 pivot)

The Kotlin control plane landed EARLY as `:tier-e-proto` (opt-in Gradle module) behind a
stateless libbpf binding shim (`ebpf-prototype/daemon/tier_e_bpf_shim.c` — no policy
logic). What REMAINS in this work package is exactly the shim's replacement:
pure-syscall object loading (BTF from `.bpf.o`, PROG_LOAD), raw-tracepoint open,
perf_event uprobe attach, ringbuf mmap consumption in `SyscallInvoker` terms, plus the
Kotlin `.note.stapsdt` parser. The daemon FSM/protocol/trust code above the seam is
already Kotlin and must not be re-implemented here.

**Needed:**

1. New `SyscallInvoker` downcalls per ffm_safety skill checklist:
   `bpf(2)` (map create, prog load, link create), `perf_event_open` (uprobe dynamic PMU:
   type from `/sys/bus/event_source/devices/uprobe/type`, `config1`=path, `config2`=offset),
   plus ring-buffer mmap management (mmap exists).
2. Program loading without libbpf:
   * verifier interaction incl. log buffer handling;
   * BTF blob for the task-storage map definition (generate at build time; ship as resource);
   * no CO-RE required for v1 programs (verified §7).
3. USDT support path (per §4.1.1): implement `.note.stapsdt` parsing to locate
   `mazewall:context_switch` and its argument descriptor; verify probe presence in the exact
   target-mapped inode before session RUNNING. If custom USDT parsing is judged
   disproportionate, a plain-uprobe fallback is permitted **only** with recorded parity
   evidence from G0a/G0b/G1 numbers and an explicit operator sign-off revising §4.1.1.
4. Port the daemon control plane to Kotlin following the `UnixListenDaemonMachine`
   state-machine pattern; widen visibility in `:platform` if needed (small refactor PR first).
5. Parity suite: identical G0a/G0b/G2 scenarios against the C prototype's recorded results;
   byte-comparable event streams modulo timestamps.

### Acceptance

```text
production runtime has zero C processes and zero libbpf linkage
parity suite green vs prototype baselines
ffm_safety checklist applied to every new downcall/layout
./gradlew :enforcer:check :profiler:check coverage thresholds intact
```
