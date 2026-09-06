---
title: "Reduce Tier E per-boundary JVMTI stack capture cost"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler-native/src/agent.cpp"
target_symbols:
  - "InvocationScope"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
review_verdict: approved
has_side_effects: true

paperclip_issue_id: "de940b1f-84a8-4726-9c1c-5501ca454ba1"
paperclip_identifier: "MAZ-1129"
---

# 🟡 [Severity: MEDIUM]: Reduce Tier E per-boundary JVMTI stack capture cost

**Context:**

Tier E must prove the exact logical Java stack at every intercepted native boundary. On the
current JDK 25 Graal/container workload (1,000 file-I/O iterations; 5,014 captured boundaries),
the detached full-stream agent had a 333.7 ms median workload time versus 239.6 ms without the
agent: +94.2 ms, or about 18.8 µs per captured boundary. The existing unique-stack cache reduced
that to 280.2 ms (+40.6 ms, about 8.1 µs/boundary), showing that per-frame metadata resolution is
a material part of the cost but not the whole cost.

**Needed:**

1. Add a repeatable, warmed native-agent microbenchmark that separates `GetStackTrace`, raw
   stack-key validation, and logical-frame metadata resolution.
2. Reduce the mandatory exact-stack path only where the benchmark proves it is safe; do not
   replace it with an approximate stack, a timestamp join, or a Java shadow stack.
3. Retain class-unloading safety for every cache and verify equality of the emitted logical stack
   before and after any optimization.

## Investigation

- Attached Tier E added approximately 67 ms in full-stream mode and 47 ms in unique-edge mode on
  the same workload. This covers raw `sys_enter`, argument extraction, ring-buffer emission, and
  the Kotlin collector; it is the second-largest contributor.
- CPU sampling of full-stream capture found HotSpot frame walking and metadata materialization at
  the top: `vframe::sender` (7.13%), `jvmti_GetMethodName` (1.99%),
  `jvmti_GetClassSignature` (1.13%), `jvmti_Deallocate` (2.63%), plus `malloc`/`cfree` and string
  operations. Unique-mode cache hits remove the method/class-name and allocation-heavy work,
  while `vframe::sender` remains the largest sampled cost (6.40%).
- The raw syscall program also reads six register arguments and emits every unmarked syscall.
  Unique-edge mode suppresses only complete marked `(logical stack, syscall)` edges; it
  intentionally retains incomplete evidence.

## Important details
- Preserve exact logical-stack attribution and the current explicit failure states.
- New caches must be safe for concurrent platform and virtual threads and must not pin unloadable
  classes.
- USER_NOTIF protocol behavior is out of scope and must not change.

## Side effects
- Native-agent capture behavior, cache lifecycle, and Tier E regression tests.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the focused profiler/native
tests and a privileged Tier E differential smoke.
<!-- id: issue-20260906-221244  file: issue-20260906-221244-reduce-tier-e-per-boundary-jvmti-stack-capture-cost.md -->
