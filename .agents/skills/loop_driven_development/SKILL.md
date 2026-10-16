---
name: loop_driven_development
description: >
  Closed-loop Maker → Checker → Triager development for mazewall features and
  bugfixes. Use when implementing a backlog issue, running TDD, or verifying
  a change. Trigger on: implement, fix the issue, TDD, LDD, loop-driven,
  maker checker, write the code, Jules task, Needed steps.
---

# Skill: Loop-Driven Development (LDD) for mazewall

Use the backlog/work package as the task contract: execute **Needed** and honor **Investigation** / **Side effects**. Implementation, verification, and diagnosis are phases of this loop; use separate agents only when the runner actually delegates them.

---

## 🔄 The LDD Cycle

```
    +---------------------------------------------------+
    |                   Maker Agent                     |
    |   (Generates code + tests from work package)     |
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
    *   Reproduce bugs and verify non-trivial executable changes with meaningful tests. Documentation-only changes use reference/link checks, `git diff --check`, and backlog validation.
    *   Follow `.agents/CODE_QUALITY.md`: sealed states/outcomes, enums, exhaustive branches, strict domain types, pure decisions, and minimum visibility.
    *   Verify FFM layouts align with target sizes.

### Step 2: Checker (Verification Phase)
*   **Action**: Start with the cheapest verification that covers the work package.
*   **Commands**:
    *   Inner loop: `./gradlew :<module>:compileKotlin` and `./gradlew :<module>:test --tests <TestClass>`.
    *   Module tests: `./gradlew :<module>:test` when broader module evidence is needed.
    *   Kernel iteration: select the owning module's `integrationTest`/`integrationTestFreshJvm`, or use `./scripts/run_tests.sh` in the configured container environment.
    *   Completion gate: `./gradlew build` once after focused checks pass. It includes formatting, lint/static analysis, tests, and configured coverage thresholds. Equivalent test commands are alternatives, not cumulative gates.
    *   Orchestrator changes: `./gradlew build -PincludeOrchestrator=true`.
*   **Coordination**: Maker, Checker, and Triager are separate agents only when the runner actually spawns them. A single agent follows the same cheap-first sequence itself.

### Step 3: Triager (Diagnostics Phase)
*   **Action**: If verification tasks fail, open and analyze the generated `build/triage_report.json`, `hs_err` output, and kernel diagnostics when applicable. Do not run the OCI suite merely to obtain diagnostics for a host-unit failure.
*   **Common Failures in mazewall**:
    *   **`EPERM` / `EACCES`**: The Seccomp filter or Landlock ruleset blocked a required JVM system call (e.g., GC thread coordination).
        *   *Action*: Correlate the blocked syscall number found in `dmesg_audit` with `Syscall.kt` to identify which rule needs to be added.
    *   **JVM Deadlock / Freeze**: A coordination system call (like `futex` or `sched_yield`) was blocked, stalling the JVM safepoint loop.
        *   *Action*: Read the collector PID/scope in `jvm_thread_dump`: it is a best-effort current-JVM platform-thread snapshot only. Gradle's `runTriage` finalizer launches a separate collector JVM, so its stacks do not identify blocked threads in the failed workload JVM. Virtual threads, `Thread.print` monitor detail, and pinning evidence are absent. Thread/frame/character rendering caps bound output, not snapshot acquisition/allocation, safepoint progress, or a hard deadline. Never enumerate host processes, attach, or send diagnostic signals based on `java`/`gradle` pathname matches; pathnames are not target authority.
