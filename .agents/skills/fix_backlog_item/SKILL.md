---
name: fix_backlog_item
description: >
  A structured, TDD-focused protocol and safety checklist for fixing backlog items
  and resolving bug/test failures safely without introducing dirty hacks, warmups, or silent bypasses.
---

# Skill: Fix Backlog Item & Safety Protocol

This skill provides a structured, TDD-focused protocol and safety checklist for resolving issues registered in the decentralized backlog directory (`docs/internals/backlog/{category}/`).

---

## 🚫 Bug-Fixing Safety & Robustness Guardrails

When fixing bugs or resolving failing tests, you must avoid introducing fragile "quick fixes" (anti-patterns) that compromise safety or maintainability.

### ⚠️ Prohibited Anti-patterns
*   **Warmup/Pre-heating loops:** Do not use loops (e.g. dummy `mmap` calls) or `sleep()` to mask lazy classloading or JIT execution races. JIT deoptimization or GC sweeps can trigger the same path later, causing non-deterministic crashes.
*   **Silent exception swallowing:** Never swallow exceptions or downgrade security configurations to make tests pass. Swallowing `EPERM` or `EACCES` violates the "Fail Closed" design invariant.
*   **Test-specific mock overrides in core:** Do not pollute main source logic with conditional checks for test environments. Use the decoupled `NativeEngine` interface and register mock engines via `LinuxNative.setEngine(mockEngine)` in tests.
*   **Race-dependent delays:** Avoid `Thread.sleep()` to fix test timing issues. Use deterministic synchronization primitives (`CountDownLatch`, `Semaphore`, or condition variables).

---

## 🛡️ Resolution Protocol

### 1. Research & Analysis
*   **Locate the Backlog Entry:** Find the target file in `docs/internals/backlog/` (e.g. `docs/internals/backlog/security/issue-XXX-name.md`, or under `performance/`, `testing/`, `code_health/`).
*   **Locate Code Targets:** Identify the target files and symbols. Use `grep_search` to find all relevant call sites.
*   **Verify State:** Confirm if the issue is still present in the current codebase.

### 2. TDD Reproduction (Mandatory)
*   **Identify Test Target:** Locate the corresponding test class or create a new test case (e.g. `ReproductionTest.kt`).
*   **Write Failing Test:** Create a minimal, self-contained test case that triggers the documented failure or behavior.
*   **Verify Failure:** Run the test to confirm it fails as expected. **Do not proceed to implementation until the failure is empirically reproduced.**

### 3. Implementation (Root-Cause Fix)
*   **Surgical root-cause fix:** Apply the minimal code change required to resolve the issue while adhering to all project safety invariants (see root `AGENTS.md`).
*   **JVM Coordination Safeguards:** If the crash is due to a blocked JVM coordination system call (like `mmap` or `mprotect` during classloading/GC), add it to `getJvmCriticalNrs(arch)` in [BpfFilter.kt](file:///home/leanid/Documents/code/java/jseccomp/enforcer/src/main/kotlin/io/mazewall/BpfFilter.kt).
*   **Verify Success:** Run the reproduction test again; it must now pass.
*   **Regression Check:** Run the full project check (`./gradlew check`) to verify clean compilation, static analysis, and that no tests are broken.

### 4. Finalization & Logging
*   **Update Backlog File:** Set `status: "resolved"` in the issue file's YAML frontmatter (e.g. `docs/internals/backlog/security/issue-XXX-name.md`) and move it to.
*   **Update README:** Move the entry from "Open Issues" to the "Resolved Issues (Archive)" section in [backlog/README.md](file:///home/leanid/Documents/code/java/jseccomp/docs/internals/backlog/README.md) (updating the link path to point to `resolved/issue-XXX-name.md`).
*   **Move File to Resolved:** Move the resolved issue file from its category folder to `docs/internals/backlog/resolved/`.
*   **Clean Up:** Remove any temporary reproduction tests unless they provide long-term regression value.
