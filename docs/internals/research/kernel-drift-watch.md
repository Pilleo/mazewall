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
| 2026-11-01 | Run [41307728608](https://github.com/Pilleo/mazewall/actions/runs/41307728608) / [#564](https://github.com/Pilleo/mazewall/issues/564) compared Ubuntu 24.04's `linux-libc-dev` 6.8.0-147.147 to the newer snapshot | Qualified the watcher on Ubuntu 26.04. The snapshot is the Ubuntu Resolute `linux-libc-dev` 7.0.0-42.42 package (`linux`, SHA-256 `f775d4438862c9eba5453eacf5134baeceee26b2a94827082758052a649ae968`); its only review delta was the Landlock OverlayFS-whiteout comment, with no ABI change. The prior exact 7.0.0-38.38 baseline SHA-256 was `0498bc9510149ef06abd6c09b8932e43c5bc71b68634b4b8f84488b2e0a20e82`. |

## Snapshot refresh procedure

1. Use Ubuntu 26.04 and `sudo apt-get update && sudo apt-get install -y linux-libc-dev`.
   The watcher rejects packages older than the reviewed 7.0.0-42.42 baseline.
2. Copy `usr/include/linux/{seccomp.h,landlock.h,io_uring.h,audit.h,bpf_common.h}` into
   `docs/internals/research/uapi-snapshot/linux/`.
3. Commit with a `Decision log` row describing what changed and whether action is required.
