# Skill: Loop-Driven Development (LDD) for mazewall

This skill instructs autonomous agents on how to execute closed-loop development loops (Maker -> Checker -> Triager) when implementing features or bugfixes in `mazewall`.

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
    |      (Executes ./scripts/run_tests.sh in OCI)     |
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
*   **Action**: Run the verification tasks natively.
*   **Commands**:
    *   `./gradlew check` or `./gradlew test` (Executes code checks and test runs. If any fail, Gradle automatically runs the `runTriage` task to compile `build/triage_report.json`).
    *   `./scripts/run_tests.sh` (Crucial for verifying actual Seccomp-BPF and Landlock LSM enforcement).
    *   `./scripts/lint.sh` (Static analysis and style checks).

### Step 3: Triager (Diagnostics Phase)
*   **Action**: If verification tasks fail, open and analyze the generated `build/triage_report.json`.
*   **Common Failures in mazewall**:
    *   **`EPERM` / `EACCES`**: The Seccomp filter or Landlock ruleset blocked a required JVM system call (e.g., GC thread coordination).
        *   *Action*: Correlate the blocked syscall number found in `dmesg_audit` with `Syscall.kt` to identify which rule needs to be added.
    *   **JVM Deadlock / Freeze**: A coordination system call (like `futex` or `sched_yield`) was blocked, stalling the JVM safepoint loop.
        *   *Action*: Review the thread dump in `jvm_thread_dump` to identify blocked JVM runtime threads.

