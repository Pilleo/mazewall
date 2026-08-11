---
title: "Cgroup Thread Memory Isolation Roadmap Is Invalid for a Shared-Heap JVM"
severity: "HIGH"
status: "open"
priority: 2
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "docs/internals/designs/core/kernel-primitives-roadmap.md"
  - "docs/internals/backlog/security/issue-069-resource-containment-via-cgroups-v2.md"
effort: "medium"
autonomy: "supervised"
---

# 🔴 [Severity: HIGH]: Cgroup Thread Memory Isolation Roadmap Is Invalid for a Shared-Heap JVM

**Context:** The roadmap and issue 069 propose moving worker TIDs into a threaded cgroup, assigning a memory limit, and relying on OOM handling to kill only malicious worker threads while the parent JVM continues. Cgroup v2 distinguishes threaded controllers from domain controllers; memory ownership and accounting cannot create independent heaps inside one process. JVM allocations, native mappings, GC activity, and object ownership cross thread-pool boundaries, and an OOM action is not a safe per-thread recovery mechanism for a shared-address-space runtime. The proposed guarantee can cause whole-process disruption rather than contained failure.

**Needed:** Restrict thread-cgroup research to controllers whose threaded semantics are documented and useful, such as CPU scheduling where supported. Move hard memory/PID isolation to a subprocess or container with a separate heap and lifecycle. Correct or supersede issue 069, and add kernel-version/controller-delegation tests before exposing any resource-control API.
