# Unprivileged Linux Kernel & BPF Opportunities for JVM Applications

## Overview

While full extended eBPF features (such as `sockmap`, `cgroup-bpf`, or `kprobes`) offer significant system benefits, they typically require elevated Linux capabilities (`CAP_BPF`, `CAP_NET_ADMIN`, `CAP_PERFMON`) or full `root` privileges.

However, Linux provides several powerful kernel primitives, security modules, and socket BPF capabilities that are **100% unprivileged** (requiring no `root`, no `sudo`, and no elevated capabilities). These mechanisms can be invoked directly from JVM applications using the Java Foreign Function & Memory (FFM) API (`java.lang.foreign`), providing zero-dependency, kernel-enforced features.

---

## 1. Unprivileged Socket BPF Filters (`SO_ATTACH_FILTER`)

### Mechanism
Any unprivileged process can attach a **Classic BPF filter** (`struct sock_fprog`) to an open socket using standard socket options:
```c
setsockopt(sock_fd, SOL_SOCKET, SO_ATTACH_FILTER, &bpf_program, sizeof(bpf_program));
```

### JVM Backend Use Case
* **Zero-Allocation Packet Pre-Filtering:** The Linux kernel executes the lightweight BPF program on every incoming frame before copying bytes into user-space socket buffers.
* **Custom Protocol Validation:** Drop malformed binary frames, rate-limit specific payload types, or reject invalid magic headers directly in kernel space.

### Comparison: Edge Proxies vs. In-Process Socket BPF

| Feature | Edge Proxy (e.g. NGINX, Envoy, ALB) | In-Process Socket BPF (`SO_ATTACH_FILTER`) |
| :--- | :--- | :--- |
| **Traffic Scope** | Ingress / North-South traffic | East-West microservice traffic & direct socket connections |
| **Protocol Support** | HTTP/gRPC, generic L4 TCP passthrough | Custom binary RPC protocols, specific frame headers |
| **Memory Allocation** | Allocated at proxy and forwarded to backend | Dropped in kernel; zero JVM heap or off-heap allocations |
| **Deployment Dependency** | External container / sidecar proxy | Self-contained inside JVM process via FFM API |

---

## 2. Unprivileged Landlock Network Access Control (Landlock ABI v4 / Kernel 6.7+)

### Mechanism
Landlock LSM provides path-based filesystem sandboxing and network port restrictions without elevated privileges. Once `prctl(PR_SET_NO_NEW_PRIVS)` is executed on a thread, Landlock rulesets can be applied using `landlock_create_ruleset` and `landlock_restrict_self`.

### JVM Backend Use Case
* **Port Binding Control (`LANDLOCK_ACCESS_NET_BIND_TCP`):** Restrict background threads or dynamic plugin contexts to binding only specific allowed TCP ports (e.g. port `8080`), preventing rogue threads from opening unauthorized backdoor listener sockets.
* **Egress Connection Restriction (`LANDLOCK_ACCESS_NET_CONNECT_TCP`):** Restrict outbound TCP connections to explicit targets (e.g. database ports), mitigating unauthorized network egress.

---

## 3. In-Memory Memory Sealing (`memfd_create` + `F_ADD_SEALS`)

### Mechanism
Unprivileged processes can create anonymous in-memory file descriptors via `memfd_create(2)` and apply immutability seals using `fcntl(2)` with `F_ADD_SEALS`:
```c
fcntl(mem_fd, F_ADD_SEALS, F_SEAL_WRITE | F_SEAL_SHRINK | F_SEAL_SEAL);
```

### JVM Backend Use Case
* **Sealed off-heap buffers:** `F_ADD_SEALS` makes *that* memfd mapping reject further writes/shrinks. It does not hide the mapping from same-process native ACE (reads still work; the attacker can use other writable mappings).
* **Zero-Disk IPC Caches:** Share immutable read-only memory pages between a host JVM process and worker subprocesses without writing physical files to disk.

---

## 4. User & Mount Namespaces (`CLONE_NEWUSER` + `CLONE_NEWNS`)

### Mechanism & Prerequisites
An unprivileged process can create a new User Namespace via `unshare(CLONE_NEWUSER)`. Inside the new namespace, the process's effective UID maps to root (`UID 0`) within the scope of that namespace.
* **Prerequisites & Caveats:** Kernel sysctls (`kernel.unprivileged_userns_clone=0` on Debian/Ubuntu/RHEL) or container seccomp profiles may block unprivileged user namespace creation. Additionally, creating thread-local namespaces in multi-threaded JVM processes is rejected by the Linux kernel (`EINVAL` on `unshare(CLONE_NEWUSER)` when multi-threaded). Namespace isolation must occur at process startup or via helper subprocesses.

### JVM Backend Use Case
* **Ephemeral In-Memory Filesystem Views:** Gaining user namespace capabilities in worker helper processes allows creating isolated Mount Namespaces (`CLONE_NEWNS`) to mount clean, temporary `tmpfs` RAM-disks per request, isolating file operations from the host filesystem.
* **Isolated Network Namespaces (`CLONE_NEWNET`):** Create isolated network stacks per execution context for running embedded integration tests without binding host network ports.

---

## 5. Syscall Emulation & Virtual File Systems (`SECCOMP_RET_USER_NOTIF`)

### Mechanism
Classic Seccomp allows unprivileged threads (configured with `PR_SET_NO_NEW_PRIVS`) to register filters with `SECCOMP_RET_USER_NOTIF`. Trapped system calls emit a notification descriptor (`SECCOMP_FILTER_FLAG_NEW_LISTENER`) handled in user space by an out-of-process supervisor daemon via `SECCOMP_IOCTL_NOTIF_RECV` and `SECCOMP_IOCTL_NOTIF_SEND`.

### JVM Backend Use Case
* **Virtual File System (VFS) Simulation:** Intercept calls to paths like `/etc/config` or dynamic secret locations, synthesizing file read/write responses directly from JVM memory without physical disk IO.
* **Syscall Interception & Emulation:** Mock OS hardware interfaces or transparently translate legacy syscall parameters for contained worker threads.

---

## 6. Process Memory Hardening (`prctl` Security Controls)

### Mechanism
Process-level security invariants applied unprivileged via `prctl(2)`:

* **`PR_SET_MDWE` (Memory Deny Write Execute):** Disallows creating executable memory pages (`PROT_EXEC`) or modifying existing writable pages into executable ones (`PROT_WRITE -> PROT_EXEC`).
* **`PR_SET_DUMPABLE(0)`:** Sets `SUID_DUMP_DISABLE`, preventing `ptrace` attachment and kernel core dumps for worker processes holding sensitive cryptographic keys or tokens in memory.
* **`PR_SET_PTRACER(0)`:** Clears any Yama exception and reverts to the system-wide Yama policy (`/proc/sys/kernel/yama/ptrace_scope`).

---

## Summary Matrix

| Feature | Linux Kernel API | Scope & Invariant | Prerequisites / Caveats |
| :--- | :--- | :--- | :--- |
| **In-Kernel Packet Filter** | `setsockopt(..., SO_ATTACH_FILTER)` | Drops invalid socket frames before JVM heap/off-heap allocation. | Unprivileged socket access |
| **Network Egress/Bind Control** | `landlock_add_rule(NET_PORT)` | Kernel-enforced TCP port binding and egress connect limits per thread. | Unprivileged (`PR_SET_NO_NEW_PRIVS`, Kernel 6.7+) |
| **Tamper-Proof Off-Heap Memory** | `memfd_create` + `F_ADD_SEALS` | Kernel-enforced write/shrink lock on specific memory file descriptor. | Specific mapping only; shared address space reads remain |
| **Ephemeral Filesystem Mounts** | `unshare(CLONE_NEWUSER \| CLONE_NEWNS)` | Ephemeral `tmpfs` RAM-disk views isolated in worker subprocesses. | `unprivileged_userns_clone=1`; process-wide/helper only |
| **Virtual File System Emulation** | `SECCOMP_RET_USER_NOTIF` | Synthesizes dynamic file contents via out-of-process supervisor. | Unprivileged (`PR_SET_NO_NEW_PRIVS`) |
| **Memory Page Hardening** | `prctl(PR_SET_MDWE)` | Prevents dynamic shellcode page generation across process. | Unprivileged |
| **Memory Dump Prevention** | `prctl(PR_SET_DUMPABLE, 0)` | Disables non-root ptrace attach and core dumps. | Whole process |

