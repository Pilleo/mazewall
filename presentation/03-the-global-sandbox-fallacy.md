# The Global Sandbox Fallacy: Thread-Scoped Seccomp in the JVM

> **Series overview:** This is Part 2 of a 4-part series on behavioral security for cloud-native applications.


In Part 1, we established that the Linux kernel gives us three unprivileged enforcement primitives — Seccomp, Landlock, and (for platform agents) BPF-LSM — and that a Software Bill of Behavior describes what software is *expected* to do so these primitives have something authoritative to enforce.

Now we get practical. The JVM is one of the most capability-rich processes in the modern data center. Let's examine why the standard approach to securing it leaves significant attack surface, and how thread-scoped enforcement closes the gap.

---

## Why Not BPF-LSM?

Before anything else: if you've been following kernel security, your first question is probably *"why Seccomp and not BPF-LSM?"*

BPF-LSM is unambiguously more powerful. While Seccomp sees raw memory addresses and is TOCTOU-vulnerable for path-based decisions (Part 1), BPF-LSM hooks *after* kernel objects are fully resolved — it inspects the canonical path `/etc/passwd`, the resolved destination IP, the resolved inode. It enables complex, context-aware enforcement that Seccomp cannot match.

**The architectural blocker is privilege.** Loading a BPF-LSM program requires `CAP_BPF` or `CAP_MAC_ADMIN`. A production JVM running as a non-root user in a container should never hold these capabilities. Using BPF-LSM for application-level self-restriction means deploying a highly privileged node agent (a Kubernetes DaemonSet) to manage policies on the JVM's behalf — a significant operational dependency.

Seccomp and Landlock are **self-restriction primitives**. With `NoNewPrivileges` set, any thread can unilaterally strip its own capabilities — no agents, no cluster-level permissions. `jseccomp` requires zero external infrastructure. That architectural purity has a cost (TOCTOU on path inspection), but it's the right trade-off for developer-driven "shift left" security.

---

## The Global Sandbox Fallacy

The standard approach to JVM security is a global seccomp profile applied to the entire Linux process — the Docker default profile, an AppArmor policy on the pod, or a custom seccomp JSON in the `securityContext`.

This is not worthless. Docker's default profile already blocks ~40 dangerous syscalls: `keyctl`, `add_key`, `request_key`, `ptrace` in certain modes, and others. That baseline matters.

**But the remaining allowed syscalls are the problem.** A typical Spring Boot application — even after Docker's default restrictions — still requires:
- `socket` + `connect` + `sendmsg` for its API and database connections
- `openat` + `read` for reading config files and loading classes
- `mmap` with `PROT_EXEC` for the JIT compiler to generate native code

Because the *process* needs these capabilities, *every thread* in the process has them — including threads processing untrusted data.

When an attacker triggers an RCE vulnerability (Log4Shell, a deserialization gadget chain, an XXE payload that reaches JNDI), they inherit the full capability set of the worker thread. They don't need to escape the container. They can use the network socket the JVM already has open to exfiltrate data, the filesystem access it already has to read `/etc/passwd`, the process execution it already has to... wait, that's where it gets interesting.

---