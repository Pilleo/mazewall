---
title: "Use explicit JVM runtime profiles in worker-completion fixtures"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules: [enforcer]
target_files:
  - "enforcer/src/test/kotlin/io/mazewall/ffi/memory/ContainmentDesignSpec.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/SandboxDispatcherCacheTest.kt"
target_symbols:
  - "ContainmentDesignSpec"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Use explicit JVM runtime profiles in worker-completion fixtures

**Context:**
Remaining fatal evidence includes FutureTask.set and VarHandleGuards on generic pool-2-thread-1 under executable mmap denial; the callable is no longer on that fatal stack, so assigning it to SandboxDispatcherCacheTest is unproven. Located OpenJDK hs_err_pid199455.log is the already-fixed applyBpfFilter385 path, not a second FutureTask case. Current JVM-liveness fixture selects strict NATIVE_IMAGE while promising prewarmed JVM completion; an added actual executable-map probe fails that configuration in run-c38a9fe850f4. The runtime contract requires explicit HOTSPOT_JIT for JVM code generation, while native-image denial assertions belong to dedicated protection probes.

**Needed:**
1. Use the explicit HotSpot-compatible profile for actual JVM completion tests, verify executable-map acquisition and checked close, retire cached dispatcher pools after each test, and preserve all real strict-profile executable-memory denial assertions and unresolved original-worker attribution.

**Resolution:** Actual JVM completion fixtures declare HOTSPOT_JIT and the liveness
fixture verifies real executable-map acquisition/close before future completion.
Cache pools retire after each test. Full GraalVM native-enabled build passes,
including unchanged strict-protection probes and public native acceptance; see
[evidence](../../../plans/2026-10-17-jvm-worker-profile-evidence.md).
No production/runtime admission or BPF behavior changes. The original FutureTask
caller's unproven provenance stays under the broader parent investigation.

---
<!-- id: issue-20261017-082745-923-use-explicit-jvm-runtime-profiles-in-worker-comple  file: issue-20261017-082745-923-use-explicit-jvm-runtime-profiles-in-worker-comple.md -->
