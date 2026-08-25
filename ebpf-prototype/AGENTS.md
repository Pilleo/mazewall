# Guidelines for AI Coding Agents in mazewall-ebpf-prototype

This directory hosts the **Tier E** prototype: the eBPF semantic-enrichment bridge
(uprobe/USDT marker → BPF task storage → `sys_enter` attribution). It is a research area that
stays **outside Gradle wiring until Gate G2 passes** (zero wrong pairings under stress).

Authoritative design: [../docs/internals/designs/profiler/tier-e-design.md](../docs/internals/designs/profiler/tier-e-design.md)
Work packages: [../docs/internals/backlog/implementation/](../docs/internals/backlog/implementation/) (`tier-e-wp-*` issues)

---

## 🚧 Hard Rules

1. **No Gradle wiring.** Nothing here may be added to `settings.gradle.kts` or any module's
   build before G2. Build via local Makefile/scripts only.
2. **Dependencies are ask-first, per PR.** Currently approved for this area:
   `clang/llvm`, `libbpf` (prototype only), `bpftool`, `systemtap-sdt-devel` (`sys/sdt.h`
   for the USDT marker ABI). Anything else needs explicit operator approval.
3. **Kernel floor: Linux 5.15.** Guard every script/test accordingly.
4. **Rootful containers only.** eBPF loading requires capabilities in the initial user
   namespace; run all kernel tests through rootful Podman/Docker scripts.

## 🔒 Tier E Invariants (full list in design doc §3)

* Profiling/enrichment/detection hints **only** — never an enforcement input.
* Context metadata is tracee-controlled and forgeable (`UNTRUSTED ATTRIBUTION METADATA`).
* **Fail UNKNOWN, never guess** — missing storage, create failure, drops, attach windows.
* Platform threads only; virtual threads rejected fail-closed.
* One BPF map set per session epoch; never recycled.
* No bpffs pinning; daemon owns all FDs; death detaches cleanly.
* Session death is terminal (`RUNNING → DEAD`); reconnect = new epoch; never splice traces.
* No policy generation from Tier E; `toPolicy()` must not exist.
* Ring-buffer loss marks observations incomplete (`droppedEvents`, `drainComplete=false`).

## 🧪 Verification Protocol

* Gates in order: **G0a** (plain-uprobe pairing) → **G0b** (USDT attach parity) →
  **G1** (marker latency, both variants) → **G2** (concurrency stress) — see design doc §8.
* Any **wrong pairing** halts work: capture kernel version + arch + repro seed and file a
  backlog issue immediately. Do not patch around it.
* Every kernel-behavior discovery gets its own backlog issue (house rule).

## Layout

```text
bpf/       BPF C sources (collector, context programs)
daemon/    privileged sidecar (load/attach/session control)
client/    throwaway test client (pre-FFM)
scripts/   rootful container runners
tests/     stress suites (WP-05)
```

## Marker ABI

Production interface is the USDT probe `mazewall:context_switch(uint32 context_id)`; plain
uprobe on the exported symbol exists only as bring-up fallback (design doc §4.1.1). Do not
introduce enter/exit probe pairs or semaphore guards without operator sign-off.
