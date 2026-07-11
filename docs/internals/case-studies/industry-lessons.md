# Industry Lessons in Sandboxing

This document synthesizes architectural lessons from major industry sandboxing projects and explains the strategic trade-offs between different isolation models.

## 1. Why Google maintains 3 "Similar" Tools

It is a common question why Google (and the industry) supports multiple tools like **nsjail**, **minijail**, and **gVisor**. They solve the same high-level problem but at completely different layers of the "Security vs. Performance" spectrum:

| Tool | Model | Isolation Boundary | Performance | Primary Use Case |
|------|-------|--------------------|-------------|------------------|
| **minijail** | Jailing | Host Kernel (Seccomp/Namespaces) | Native | System services (ChromeOS/Android) that need direct hardware access. |
| **nsjail** | Containment | Host Kernel (Namespaces/Cgroups) | Native | Short-lived, untrusted tasks (CTFs, code execution engines). |
| **gVisor** | Virtualization | User-space Kernel (The Sentry) | Moderate | Multi-tenant Cloud (Google Cloud Run) where kernel-exploit protection is mandatory. |

### The "Compatibility vs. Security" Conflict:
- **minijail/nsjail** are fast but share the host kernel. If an attacker finds a zero-day exploit in the Linux kernel's `sys_ioctl`, they can escape.
- **gVisor** provides a "virtual kernel" in Go. An attacker might exploit the virtual kernel, but they are still trapped inside a Go process, with the real host kernel still one layer away. The cost is a 10-30% performance hit on syscall-heavy applications.

---

## 2. Key Architectural Lessons for `mazewall`

### A. nsjail: The "Namespace Mapping" Lesson
`nsjail` demonstrates that process-level isolation is only as good as its **User Namespace (USER_NS)** mapping.
- **Lesson:** By mapping the internal "root" user to an external "nobody" user, you prevent many "privilege escalation" bugs before they even reach the syscall filter.
- **Mazewall Application:** We should investigate if `ContainedExecutors` can automatically initialize a User Namespace to provide an extra layer of identity isolation.

### B. gVisor: The "Interception" Lesson
gVisor proves that BPF is not enough for complex logic.
- **Lesson:** Some syscalls (like complex networking or filesystem paths) are too dynamic for BPF jump tables. You *must* have a way to "trap and emulate" in user-space.
- **Mazewall Application:** This validates our use of **Seccomp `USER_NOTIF`** in the `profiler` module. Like gVisor, we use a specialized listener to handle the "hard" cases the kernel filter can't decide on.

### C. bubblewrap: The "Unprivileged" Lesson
`bubblewrap` won the "container-on-the-desktop" war because it doesn't require `sudo`.
- **Lesson:** Security tools that require root are rarely used by developers. 
- **Mazewall Application:** We must prioritize the "Unprivileged" mode. If `mazewall` can run using only standard user permissions (via `no_new_privs` and unprivileged USER_NS), it becomes a viable tool for CI/CD pipelines and restricted cloud environments.

### D. OpenBSD `pledge`/`unveil`: The "Cognitive Load" Lesson
OpenBSD found that developers write insecure code because security APIs are too hard.
- **Lesson:** `pledge()` and `unveil()` are successful because they use **Natural Language Strings** (e.g., `pledge("stdio rpath")`) instead of bitmasks and pointers.
- **Mazewall Application:** Our Kotlin DSL should mirror this. Developers should say `Policy.READ_ONLY("/tmp")` instead of manually configuring Landlock access masks.

### E. Minijail: The "Late-Jail" Lesson
Minijail is designed to stay out of the way until the very last second.
- **Lesson:** Applications need "Privileged Initialization" (loading secrets from `/etc`, binding to port 80). The jail should only "snap shut" once the app enters its main loop.
- **Mazewall Application:** We should promote the `wrap()` pattern over "at-startup" process-wide jailing, as it allows the JVM to finish its complex classloading and GC initialization before the restrictions are applied.
