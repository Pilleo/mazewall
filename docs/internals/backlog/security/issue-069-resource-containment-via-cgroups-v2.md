---
title: "Resource Containment via Cgroups v2"
severity: "ENHANCEMENT"
status: "open"
---

# 🔵 [Severity: ENHANCEMENT]: Resource Containment via Cgroups v2

**Context:** `mazewall` currently focuses on capability and access containment (Syscalls and Filesystem) but lacks hard native resource limits (Memory, CPU) per thread or sandbox. This leaves the JVM vulnerable to native memory leaks (via FFM) or thread-spawning denial-of-service (fork-bomb) attacks within a contained thread pool.
**Needed:** Use FFM to interact with the `/sys/fs/cgroup` filesystem. When wrapping an untrusted workload, the library should dynamically create a transient cgroup v2 slice, move the worker thread's OS TID into that slice, and apply hard memory and CPU limits. This provides robust protection against resource-exhaustion DoS attacks from within sandboxed tasks.
