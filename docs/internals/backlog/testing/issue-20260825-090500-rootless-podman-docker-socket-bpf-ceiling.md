---
title: "Dev-host container daemon is rootless podman; Tier E kernel phases need init-userns root"
severity: "LOW"
status: "open"
priority: medium
component: "ebpf-prototype"
target_modules:
  - "ebpf-prototype"
target_files:
  - "ebpf-prototype/scripts/run_collector.sh"
effort: "small"
autonomy: "supervised"
open_questions: true
dependencies: []
---

# 🟡 [Severity: LOW]: Rootless podman service masquerades as docker.sock on the dev host

**Context:** `/var/run/docker.sock -> /run/podman/podman.sock` serves a **rootless** podman
instance running as host uid 1000. A `--privileged` container through it shows full
namespaced caps (`CapEff=0x1ffffffffff`, uid_map `0→1000`) yet **cannot** load tracing BPF:

* bpf(2) capability checks run against the *initial* user namespace → EPERM;
* RLIMIT_MEMLOCK hard limit inside the userns is 8 KiB and unraisable;
* host sysctl `kernel.unprivileged_bpf_disabled=2`.

This is exactly the Tier P ceiling documented in `tier-e-design.md` §7 / profiler-design.md,
and mirrors `EbpfLoad.UserNamespaceRoot` semantics already probed by `EbpfCapability`.
Discovered while executing Tier E WP-02's kernel phase (2026-08-25).

**Needed:**

1. Keep using `ebpf-prototype/scripts/run_collector.sh`, which now auto-detects the situation
   (probes container `uid_map`; rootless ⇒ falls back to one `sudo podman run`).
2. Decide the durable workflow (operator):
   * passwordless sudo rule for the specific runner command, or
   * execute Tier E kernel phases only in CI runners with genuine rootful runtimes.
3. Never "fix" this by relaxing host BPF policy (`unprivileged_bpf_disabled`) — that widens
   the attack surface machine-wide for convenience.

## ❓ Open Questions

1. Which durable workflow does the operator prefer for local G0/G1/G2 gate runs?
