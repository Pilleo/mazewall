---
title: "Kernel Roadmap Attributes Process and Ring Controls to Sandboxed Threads"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "docs/internals/designs/core/kernel-primitives-roadmap.md"
  - "docs/internals/unprivileged-bpf-jvm-opportunities.md"
effort: "medium"
autonomy: "autonomous"
---

# 🟡 [Severity: MEDIUM]: Kernel Roadmap Attributes Process and Ring Controls to Sandboxed Threads

**Context:** The roadmap presents `PR_SET_PTRACER, 0` as preventing every same-UID external attach, `userfaultfd` as a sandbox boundary, and a restricted `io_uring` as thread-scoped syscall containment. `PR_SET_PTRACER, 0` clears a Yama exception and returns to the configured ptrace policy; it is not equivalent to making the process nondumpable. `userfaultfd` is a paging mechanism with privilege/sysctl constraints, not an access-control boundary. `IORING_REGISTER_RESTRICTIONS` constrains one ring, not the thread or process; code that can create or reach another unrestricted ring remains outside it. The companion opportunities document also calls namespace and BPF features universally unprivileged despite kernel configuration, sysctl, LSM, and container-policy dependencies.

**Needed:** Replace the feature list with a primitive-by-primitive scope and prerequisite table. State which object is constrained, how an attacker could select another object or syscall path, and which outer policy must prevent bypass. Correct ptrace guidance to cover dumpability, credentials, Yama mode, LSMs, namespaces, and explicitly authorized profiler relationships.
