---
title: "🟢 [RESOLVED]: `installOnProcess` process-wide seccomp synchronization (TSYNC) fails deterministically on standard JVMs"
severity: "RESOLVED"
status: "resolved"
---

# 🟢 [RESOLVED]: `installOnProcess` process-wide seccomp synchronization (TSYNC) fails deterministically on standard JVMs

**Target:** `io.mazewall.seccomp.PureJavaBpfEngine`
**Failure Hypothesis:** Process-wide seccomp installation via `TSYNC` requires `no_new_privs` to be enabled on all threads in the thread group. In standard JVMs, background threads are spawned before `no_new_privs` is set, causing TSYNC to fail with `EACCES` under non-root configurations. The current exception error message is also highly misleading.
**Context & Proof:** The Linux kernel requires `no_new_privs` to be set on all sibling threads in the thread group for `SECCOMP_FILTER_FLAG_TSYNC` to succeed. When the JVM starts, GC threads, JIT threads, and VM helper threads are spawned at startup. In `PureJavaBpfEngine.installInternal`, the main thread calls `setNoNewPrivs()`, which only sets the flag on the *calling* thread. Pre-existing background threads do not get it. When `TSYNC` is attempted, the kernel returns `EACCES` (-13). The method catches this failure and throws an exception claiming "Your kernel may be too old to support SECCOMP_FILTER_FLAG_TSYNC", which is factually incorrect and misleads operators.
**Resolved:** Clarified the exception message to clearly state that `TSYNC` failed due to missing `no_new_privs` on sibling threads, advising operators to run with OCI/Kubernetes `allowPrivilegeEscalation: false` or pre-set `no_new_privs` using an external launcher. Additionally, added a platform diagnostics API (`Platform.diagnose()`) to verify the `no_new_privs` state in-app.
