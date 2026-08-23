# Kernel-Drift Watch (issue-20260823-172002)

Quarterly review checklist for Linux interfaces mazewall must reason about. The scheduled CI job
(`.github/workflows/kernel-drift-watch.yml`) diffs the packaged uapi headers against the snapshot in
`docs/internals/research/uapi-snapshot/` and opens an orchestrator issue on drift; this document is
where each finding gets a decision row.

## Review cadence

Run at every quarterly release train AND whenever the drift-watch CI job files an issue.

## Checklist

| Area | Sources | Questions to answer |
|---|---|---|
| seccomp | LWN kernel cuticles, `linux/seccomp.h` diff, `Documentation/userspace-api/seccomp_filter.rst` | New return actions? NOTIF API changes (`SECCOMP_IOCTL_NOTIF_ADDFD`, flags)? TSYNC semantics changes? |
| Landlock | `linux/landlock.h` diff, ABI version announcements | New access-rights bits (handled dynamically by `KernelFeatureMatrix` — confirm)? New syscalls? TSYNC scope changes? |
| io_uring | `linux/io_uring.h` diff | New `IORING_OP_*` that bypass open/openat semantics? SQPOLL/registered-files changes affecting our `io_uring_setup` special-casing (`PolicyBuilder` note: blocked unless Landlock enforced)? |
| LSM stacking | kernel LSM list summaries | Errno precedence changes affecting violation detection? Landlock+SELinux/AppArmor composition surprises? |
| cBPF verifier/JIT | netdev list, `kernel/bpf/core.c` diffs | Classic-BPF opcode semantic changes (see issue-20260823-140500: JA jumps by K) — would break emitted filters? |

## Decision log

| Date | Finding | Decision |
|---|---|---|
| 2026-08-23 | Initial baseline snapshot committed (`uapi-snapshot/`) | Baseline only; no action |
| 2026-08-23 | Classic BPF JA uses K not jt (empirically proven, C harness) | Encoded in `BpfSimulator` + ArchUnit-pinned tests; see resolved issue-20260823-140500 |

## Snapshot refresh procedure

1. `sudo apt-get install linux-libc-dev` (or use the CI container image pinned in
   `.github/workflows/kernel-drift-watch.yml`).
2. Copy `usr/include/linux/{seccomp.h,landlock.h,io_uring.h,audit.h,bpf_common.h}` into
   `docs/internals/research/uapi-snapshot/linux/`.
3. Commit with a `Decision log` row describing what changed and whether action is required.
