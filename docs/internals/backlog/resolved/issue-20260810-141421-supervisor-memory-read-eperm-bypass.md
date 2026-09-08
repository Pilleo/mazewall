---
title: "Fail Closed on Supervisor Memory Read EPERM"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":platform"
  - ":enforcer"
target_files:
  - "platform/src/main/kotlin/io/mazewall/ffi/memory/TraceeMemoryReader.kt"
  - "enforcer/src/test/kotlin/io/mazewall/ffi/memory/SupervisorProcessMemoryReaderTest.kt"
effort: "small"
autonomy: "autonomous"
paperclip_issue_id: 16728651-b47d-4db0-b1d0-706e93fa6148
paperclip_identifier: MAZ-468
---

# 🔴 [Severity: HIGH]: Fail Closed on Supervisor Memory Read EPERM

**Context:** `TraceeMemoryReader` returned a synthetic NUL-terminated path when `process_vm_readv` failed with `EPERM`. The profiler needs that sentinel for discovery diagnostics, but the supervisor also delegated to the shared reader and could submit the synthetic path for policy evaluation even though the actual tracee path was never inspected.

**Needed:** Preserve the sentinel only for explicitly diagnostic profiler reads. Enforcement reads must throw on `EPERM`, allowing `SupervisorProcessMemoryReader` to translate the failure into a containment violation. Cover the enforcement behavior with a fault-injected regression test.
