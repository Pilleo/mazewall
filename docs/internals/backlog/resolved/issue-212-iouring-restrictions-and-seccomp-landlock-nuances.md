---
title: "Kernel Invariants and Limitations of io_uring Restrictions (seccomp, Landlock, and IORING_REGISTER_RESTRICTIONS)"
severity: "MEDIUM"
status: "closed"
priority: medium
---

# 🟡 [Severity: MEDIUM]: Kernel Invariants and Limitations of io_uring Restrictions

**Context:**
`io_uring` operations submit I/O requests asynchronously via shared-memory Submission Queue Entries (SQEs) executed by kernel helper threads (`io-wq`). Traditional `seccomp-bpf` filters operate at the `sys_enter` boundary of the calling thread and are structurally blind to inner SQE opcodes. To compensate, Linux provides multiple distinct controls with different capabilities and failure modes:

1. **`seccomp-bpf`**: Can block ring creation (`io_uring_setup`), preventing `io_uring` initialization entirely. Cannot inspect or filter inner SQE opcodes.
2. **`IORING_REGISTER_RESTRICTIONS` (`io_uring_register`)**: An in-subsystem mechanism to apply an opcode allowlist (`IORING_RESTRICTION_SQE_OP`), register command allowlist (`IORING_RESTRICTION_REGISTER_OP`), and SQE flag constraints (`IORING_RESTRICTION_SQE_FLAGS_ALLOWED`/`REQUIRED`). It must be applied while the ring is in a disabled state (`IORING_SETUP_R_DISABLED`) before enabling (`IORING_REGISTER_ENABLE_RINGS`). Once enabled, restrictions are one-way and immutable (`-EBUSY`).
   * **Limitation:** It is purely a bitwise mask on opcodes and flags; it **cannot** perform path, filename, IP address, or argument inspection. Furthermore, it is a voluntary per-ring setting—untrusted code can attempt to spawn new unrestricted rings unless `seccomp` blocks `io_uring_setup`.
3. **Landlock LSM**: Intercepts file system operations at VFS LSM hooks (`security_file_open`). Kernel credential propagation (`io_wq_submit_work()`) ensures that Landlock rulesets apply to `io-wq` worker threads. Thus, Landlock **does** enforce path-level security on `io_uring` filesystem access.

**Needed:**
- Document these precise kernel capabilities, limitations, and failure modes in `../../../../profiler/IO_URING_PROFILING.md` and kernel primitives design documents.
- Ensure policy recommendations explicitly combine Seccomp (blocking `io_uring_setup` for untrusted threads), Landlock (path enforcement on allowed rings), and optional `IORING_REGISTER_RESTRICTIONS` (opcode filtering for cooperative ring setups).
