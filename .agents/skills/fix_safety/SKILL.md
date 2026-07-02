---
name: fix_safety
description: >
  Guidelines and guardrails for fixing bugs, test failures, and issues without introducing dirty hacks, warmups, or silent bypass scenarios.
---

# Skill: Bug-Fixing Safety & Robustness

## Purpose

When coding agents fix bugs or resolve failing tests, they often introduce "quick fixes" (anti-patterns) like:
- **Fragile warm-up loops** or `sleep()` statements to mask lazy classloading or JIT execution races.
- **Silent bypasses** (e.g. catching exceptions silently, downgrading security filters, or adding conditional bypasses in core logic).
- **Hardcoded test specific values** (e.g., hardcoding paths or process IDs to satisfy a single test constraint).

This skill enforces a rigorous framework to ensure all fixes address the **root cause** and respect the repository's strict security invariants.

---

## 🚫 Avoid These Fixing Anti-patterns

| Anti-pattern | Why it is Rejected | What to do instead |
|---|---|---|
| **Warmup/Pre-heating loops** | Fragile. JIT deoptimization or GC garbage sweeps can trigger the same classloading/native mapping path again, causing non-deterministic crashes later. | Add the underlying system calls or requirements to the JVM coordination/critical bypass layer (e.g., `jvmCriticalNrs`). |
| **Silent exception swallowing** | Masks vulnerabilities or errors, bypassing the "Fail Closed" design invariant of the sandboxing library. | Propagate security/access failures upwards or abort immediately. Never catch `EPERM` or `EACCES` silently. |
| **Test-specific mock overrides in core** | Pollutes core library logic with test artifacts and breaks execution boundaries. | Decouple native dependencies using the `NativeEngine` trait decoupling pattern. Mock via `LinuxNative.setEngine(mockEngine)`. |
| **Race-dependent delays** | Adding `Thread.sleep()` to fix test timing issues usually makes tests flaky in CI/cold environments. | Use deterministic synchronization primitives (`CountDownLatch`, `Semaphore`, or condition variables). |

---

## 🛡️ Correct Fixing Protocol

1. **Perform Root-Cause Analysis (RCA):**
   - Trace the exact stack trace (read crash logs like `hs_err_pid*.log` or test logs).
   - Determine if the blocked syscall is a core JVM coordination mechanism (`futex`, `mmap`, `mprotect`, `madvise`).
2. **Refactor Core Invariants:**
   - If a blocked syscall is essential to the JVM runtime (classloading, garbage collection, thread state), add it to `getJvmCriticalNrs(arch)` in [BpfFilter.kt](file:///home/leanid/Documents/code/java/jseccomp/enforcer/src/main/kotlin/io/mazewall/BpfFilter.kt).
   - If a test has specific setup/execution requirements, mock them at the native engine interface or rewrite the test assertions.
3. **Verify the Fix Deterministically:**
   - Verify that the test passes **freshly** without any caching (`--no-build-cache` and `--no-configuration-cache`).
   - Run the full project verification task (`./gradlew check`) to confirm no regressions.
