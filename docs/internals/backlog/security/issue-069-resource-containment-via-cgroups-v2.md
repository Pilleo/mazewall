---
title: Resource Containment via Cgroups v2
severity: ENHANCEMENT
status: open
priority: 2
dependencies: []
target_files:
- enforcer/src/main/kotlin/io/mazewall/enforcer/ContainedExecutors.kt
target_modules:
- :enforcer
component: enforcer
effort: medium
---

# 🔵 [Severity: ENHANCEMENT]: Resource Containment via Cgroups v2

**Context:** `mazewall` currently focuses on capability and access containment (Syscalls and Filesystem) but lacks hard native resource limits (Memory, CPU) per thread or sandbox. This leaves the JVM vulnerable to native memory leaks (via FFM) or thread-spawning denial-of-service (fork-bomb) attacks within a contained thread pool.
**Needed:** CPU (threaded controller) only, if ever. **Do not** treat cgroup memory limits as per-thread isolation on a shared-heap JVM (see issue-20260808-025043). Hard memory/PID isolation is a subprocess or container.
