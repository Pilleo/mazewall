---
title: "Tier E WP-02: Standalone C eBPF syscall collector prototype"
severity: "ENHANCEMENT"
status: "open"
priority: high
component: "ebpf-prototype"
target_modules:
  - "ebpf-prototype"
target_files:
  - "ebpf-prototype/bpf/syscall_collector.bpf.c"
  - "ebpf-prototype/collector/"
effort: "medium"
autonomy: "supervised"
open_questions: false
dependencies:
  - "issue-20260825-023931-tier-e-wp-01-mazewall-context-api.md"
---

# 🟢 [Severity: ENHANCEMENT]: WP-02 — Standalone C eBPF Syscall Collector

**Context:** Smallest possible kernel component, built before any Java integration. Proves the
toolchain (clang/llvm + libbpf) and the event path (tracepoint → ring buffer → reader) inside the
unwired `ebpf-prototype/` directory. **Do not touch Gradle wiring or any existing module.**

Design reference: [tier-e-design.md §4](../../designs/profiler/tier-e-design.md).

**Needed:**

1. BPF program attached to `raw_tp/sys_enter`. Key facts (verified, design doc §7):
   * Second raw argument **is** the syscall number — no CO-RE deref needed.
   * Use `bpf_get_current_pid_tgid()`: TGID = high 32 bits, TID = low 32 bits.
2. Emit through a BPF ring buffer:
   ```c
   struct syscall_event {
       __u64 ktime_ns;
       __u32 tgid;
       __u32 tid;
       __s32 syscall_nr;
   };
   ```
3. **Filter to one configured TGID immediately** (`--pid <tgid>` CLI flag). A machine-wide
   firehose is a defect, not a feature.
4. Userspace reader prints `tgid/tid syscall_nr` lines.
5. Increment a drop counter when `bpf_ringbuf_reserve()` fails; expose it in reader output.
6. Build via clang/llvm + libbpf in `ebpf-prototype/`, rootful Podman test script under
   `ebpf-prototype/scripts/`.

### Tests

Run a tiny C or Java workload performing known syscalls; verify:

```text
correct TGID / host TID / syscall number
concurrent threads remain distinguishable
non-target TGIDs produce zero events
ring-buffer drop counter increments under forced pressure (tiny ringbuf)
```

**PR is done when:** a platform Java thread calling `gettid()` corresponds to the kernel stream
whose host TID matches, tested inside a rootful container.

## Guardrails

* Kernel floor 5.15. Guard scripts accordingly.
* Log every kernel-behavior surprise as its own backlog issue (house rule).
