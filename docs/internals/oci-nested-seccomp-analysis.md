# OCI Nested Seccomp Analysis

### Security Analysis of Nested Seccomp in OCI Runtimes

OCI runtimes (such as `runc`, `containerd`, and Docker) restrict the `seccomp(2)` system call and specific `prctl(2)` options within their default profiles. This design decision is part of a defense-in-depth strategy aimed at reducing the host kernel's attack surface, preventing untrusted processes within containers from interacting with the kernel's BPF verifier or constructing arbitrary syscall filters.

However, the necessity of this OCI-level block can be evaluated against kernel-level invariants:
1.  **Enforced State Monotonicity:** The Linux kernel strictly requires the `PR_SET_NO_NEW_PRIVS` flag to be set before an unprivileged process can load a seccomp filter. Once active, the process and all descendants are permanently barred from privilege transitions (such as setuid, setgid, or file capability elevations).
2.  **Filter Monotonicity:** Seccomp filters can only restrict the current syscall capabilities; they cannot be removed, bypassed, or relaxed by subsequent nested filters.
3.  **Kernel Limits:** Modern kernels cap seccomp filter depth and BPF program complexity, preventing simple kernel memory exhaustion vectors.

Given these kernel-level invariants, blocking unprivileged seccomp filter installation inside containers does not prevent privilege escalation, since the kernel already enforces an immutable boundary. The primary risk re-introduced by whitelisting `seccomp` and `prctl(PR_SET_SECCOMP)` is a minor increase in BPF verifier exposure. 

A potential architectural alternative for OCI specifications would be to permit nested filter installation by default whenever the container is configured with `allowPrivilegeEscalation: false` (which pre-emptively enforces `PR_SET_NO_NEW_PRIVS`). This would allow secure, application-level sandboxing (such as thread-scoped containment) to be deployed natively within standardized container environments without requiring custom profiles.