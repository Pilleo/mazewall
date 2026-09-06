---
name: loop_driven_development
description: >
  Closed-loop Maker → Checker → Triager development for mazewall features and
  bugfixes. Use when implementing a backlog issue, running TDD, or verifying
  a change. Trigger on: implement, fix the issue, TDD, LDD, loop-driven,
  maker checker, write the code, Jules task, Needed steps.
---

# Skill: Loop-Driven Development (LDD) for mazewall

This skill instructs autonomous agents on how to execute closed-loop development loops (Maker -> Checker -> Triager) when implementing features or bugfixes in `mazewall`. Jules: the backlog markdown **is** the spec — execute **Needed**, honor **Investigation** / **Side effects**, then this loop.

---

## 🔄 The LDD Cycle

```
    +---------------------------------------------------+
    |                   Maker Agent                     |
    |      (Generates code + test cases from Spec)      |
    +---------------------------------------------------+
                              |
                              v
    +---------------------------------------------------+
    |                  Checker Agent                    |
    |      (Runs the cheapest relevant verification)    |
    +---------------------------------------------------+
                              |
                 +------------+------------+
                 |                         |
                 v (Success)               v (Failure)
       [Complete & Audit]        +-----------------------------+
                                 |        Triager Agent        |
                                 |  (Parses logs/deadlocks &   |
                                 |   updates design/tasks)     |
                                 +-----------------------------+
                                               |
                                               v (Fix Loop)
```

---

## 🛠️ Step-by-Step Execution Guide

### Step 1: Maker (Implementation Phase)
*   **Action**: Generate code changes and companion test cases.
*   **Rules**:
    *   Do not modify files without an accompanying test that reproduces the expected behavior.
    *   Verify FFM layouts align with target sizes.

### Step 2: Checker (Verification Phase)
*   **Action**: Start with the cheapest verification that covers the work package.
*   **Commands**:
    *   Inner loop: `./gradlew :<module>:compileKotlin` and `./gradlew :<module>:test --tests <TestClass>`.
    *   Module gate: `./gradlew :<module>:test` before review.
    *   Kernel behavior: `./gradlew integrationTest` or `./scripts/run_tests.sh` only when the backlog item declares `needs_kernel: true` or the change installs seccomp, Landlock, or USER_NOTIF behavior.
    *   Merge gate: `./gradlew build` once after the focused checks pass.
*   **Coordination**: Maker, Checker, and Triager are separate agents only when the runner actually spawns them. A single agent follows the same cheap-first sequence itself.

### Step 3: Triager (Diagnostics Phase)
*   **Action**: If verification tasks fail, open and analyze the generated `build/triage_report.json`, `hs_err` output, and kernel diagnostics when applicable. Do not run the OCI suite merely to obtain diagnostics for a host-unit failure.
*   **Common Failures in mazewall**:
    *   **`EPERM` / `EACCES`**: The Seccomp filter or Landlock ruleset blocked a required JVM system call (e.g., GC thread coordination).
        *   *Action*: Correlate the blocked syscall number found in `dmesg_audit` with `Syscall.kt` to identify which rule needs to be added.
    *   **JVM Deadlock / Freeze**: A coordination system call (like `futex` or `sched_yield`) was blocked, stalling the JVM safepoint loop.
        *   *Action*: Review the thread dump in `jvm_thread_dump` to identify blocked JVM runtime threads.
