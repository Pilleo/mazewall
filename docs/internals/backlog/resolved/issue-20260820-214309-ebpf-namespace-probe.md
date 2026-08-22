---
title: "Do not use container PID 1 to identify the initial user namespace"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/EbpfLoad.kt"
effort: "medium"
autonomy: "autonomous"
related_pr: 512
related_thread: PRRC_kwDOScnnEM7kBdU
---

# 🟠 [Severity: MEDIUM]: Do not use container PID 1 to identify the initial user namespace

**Context:** Inside a rootless container, `/proc/1/ns/user` normally refers to the container's PID 1, which can share the caller's non-initial user namespace. `Files.isSameFile` then returns true and bypasses the `uid_map` fallback, so namespaced capability bits can make `probe()` report `EbpfLoad.Available` even though the tracing eBPF capability requirement in the initial host namespace is not met.

**Problem:**
- Container PID 1 may share non-initial user namespace with caller
- Files.isSameFile returns true for container PID 1
- uid_map fallback is bypassed
- Namespaced capability bits trigger false Available result
- Host namespace eBPF requirement not actually met

**Impact:**
- False positive on eBPF capability probe
- Profiler attempts eBPF when it shouldn't
- Falls back or fails in container environment

**Needed:**
1. Determine initial-namespace membership from host-provided evidence
2. Compare against actual host PID 1, not container PID 1
3. Use alternative method to detect initial user namespace

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3825587188
