# mazewall-ebpf-prototype (Tier E)

> **Status: research area, unwired.** Not part of the Gradle build until Gate G2 passes.
> See [AGENTS.md](AGENTS.md) for hard rules and
> [the Tier E design document](../docs/internals/designs/profiler/tier-e-design.md) for the
> locked architecture.

Tier E attributes Linux syscalls to Java semantic scopes:

```text
MazewallContext.withContext(PDF_PARSE) { parser.parse(...) }
        │ FFM downcall
        ▼
mazewall:context_switch(u32)          USDT probe in libmazewall_context.so
        │ synchronous uprobe
        ▼
BPF task storage[current] = PDF_PARSE
        │ later, on openat()
        ▼
sys_enter → task_storage[current] → ringbuf { tid, openat, PDF_PARSE }
```

## Planned layout

| Path | Contents | Work package |
|---|---|---|
| `bpf/` | syscall collector + context/task-storage BPF programs | WP-02, WP-03 |
| `daemon/` | privileged sidecar: load, attach, session lifecycle, control socket | WP-04 |
| `client/` | throwaway test client (replaced by the FFM bridge client later) | WP-02..05 |
| `scripts/` | rootful Podman/Docker test runners | all |
| `tests/` | concurrency/stress suites | WP-05 |

## Prerequisites

* Linux ≥ 5.15 with BTF
* clang/llvm, bpftool, libbpf (≥ 0.8 for USDT attach), systemtap-sdt-devel (`sys/sdt.h`)
* Rootful container runtime for tests (eBPF caps require the initial user namespace)

## Execution order

Work packages and gates live in
[docs/internals/backlog/implementation/](../docs/internals/backlog/implementation/)
(issue ids `issue-20260825-*-tier-e-wp-*`). Start at WP-02 after WP-01 lands; do not skip
gates.
