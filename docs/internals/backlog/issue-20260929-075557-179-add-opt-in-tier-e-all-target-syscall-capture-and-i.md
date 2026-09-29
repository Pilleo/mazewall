---
title: "Add opt-in Tier E all-target syscall capture and independent stop barrier"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["profiler"]
target_files:
  - "config/detekt/profiler-baseline.xml"
  - "config/detekt/native-helper-exceptions.md"
  - "docs/internals/backlog/README.md"
  - "profiler-native/CMakeLists.txt"
  - "profiler-native/tests/TierEAllTargetWorkload.java"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierEOptions.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierEProfilerSession.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEBpfProgramBuilder.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEbpfEngine.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/ringbuf/SyscallInvocationEvent.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierEKotlinDaemon.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/TierEBpfProgramBuilderTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/TierEPreGateBytecodeExecutionTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/attribution/TierEProfilerSessionLifecycleTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/attribution/TierEOptionsTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/daemon/TierETargetRegistrationTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/BpfProgLoadLayoutTest.kt"
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierEEndToEndIntegrationTest.kt"
  - "docs/internals/designs/profiler/tier-e-design.md"
  - "docs/internals/designs/profiler/tier-e-split-privilege-deployment.md"
target_symbols:
  - "TierEProfilerSession"
open_questions: false
---

# 🟡 [Severity: HIGH]: Add opt-in Tier E all-target syscall capture and independent stop barrier

**Context:**
Kubescape PoC needs individual target sys_enter records even without active Java attribution; default mode and no-loss shutdown must stay fail-closed.

**Needed:**
1. Add red tests for inactive, detached, stale-generation and unavailable task-storage target syscalls emitted only in a distinct opt-in all-target mode; wrong TGID or disabled capture emits nothing. Keep full-stream, dictionary, v1 marker and direct raw tracepoint constraints.
2. Add a capture gate separate from marker attachment, admitted in BPF only **after** the target handler increments the in-flight counter. Start disabled, enable only after collector start, then disable, wait for in-flight handlers, drain, read counters and close at terminalization. Preserve legacy session-map layout when mode is off; verify the opt-in map value offsets and confined FFM arena lifecycle.
3. Check raw syscall argument-read helper return values. Preserve six raw argument slots and flag unavailable reads; never present a failed read as an apparently valid argument. Retain individual repetitions and explicit kernel ring-reservation/loss accounting.
4. Wire a registered-target-only daemon opt-in; leave the existing default path and Tier E policy eligibility unchanged. Add bounded privileged kernel tests for post-disable exclusion and no post-drain producer; record source/kernel/image provenance for the new mode.

---
<!-- id: issue-20260929-075557-179-add-opt-in-tier-e-all-target-syscall-capture-and-i  file: issue-20260929-075557-179-add-opt-in-tier-e-all-target-syscall-capture-and-i.md -->
