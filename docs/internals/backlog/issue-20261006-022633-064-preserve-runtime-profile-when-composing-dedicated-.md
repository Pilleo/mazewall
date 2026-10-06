---
title: "Preserve runtime profile when composing dedicated worker policies"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "profiler/src/main/kotlin/io/mazewall/profiler/api/ProfilingResult.kt"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Preserve runtime profile when composing dedicated worker policies

**Context:**
toDedicatedWorkerPolicies(HOTSPOT_JIT) compiles its profiled component from PURE_COMPUTE_UNSAFE without the selected runtime. Restrictive composition therefore produces allowMmapExec=false and can deny HotSpot JIT executable mappings despite an explicitly selected JIT profile.

**Needed:**
1. Apply the selected runtime before profile conversion; assert the combined HotSpot and Native Image contracts, retain process/network denials, and verify a disposable JVM workload.

---
<!-- id: issue-20261006-022633-064-preserve-runtime-profile-when-composing-dedicated-  file: issue-20261006-022633-064-preserve-runtime-profile-when-composing-dedicated-.md -->
**Execution evidence:**

- The new combined-policy regression failed on `HOTSPOT_JIT` before the correction.
- The profiled base is now configured for the selected runtime before conversion, preserving restrictive intersection rather than overriding it afterward.
- The combined HotSpot policy retains executable mappings; the Native Image policy retains its W^X restriction. Both still deny process execution and network creation.
- `./gradlew :profiler:test --tests '*PortalWorkerPolicyTest*'` and the full `./gradlew :profiler:test` passed.
- A full profile-backed process/JIT acceptance run remains required before declaring this path released.
