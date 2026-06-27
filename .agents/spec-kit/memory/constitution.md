# Project Constitution: mazewall

Welcome, Agent. This repository contains **mazewall**, a kernel-enforced, thread-scoped and process-wide sandboxing library for JVM applications using Linux Seccomp-BPF and Landlock LSM via the JDK Foreign Function & Memory (FFM) API.

As an AI agent developing here, you must strictly follow this Constitution.

---

## 🚧 Hard Boundaries & Invariants

### 1. Security Invariants
*   **Fail Closed:** Default fallback behavior MUST be `FAIL`. Never implement silent bypasses.
*   **No Loom Carrier Thread Poisoning:** Native calls must not run directly on Virtual Threads without verifying they do not poison or pin Loom carrier threads.
*   **Syscall Boundaries:** Never block critical JVM coordination system calls (e.g., GC/JIT synchronization threads).

### 2. Architectural Invariants
*   **AOT Readiness:** All code must be compatible with GraalVM Native Image compilation (no unsafe runtime reflections, closed-world assumption).
*   **Alignment & Layouts:** When mapping native C structs, always define padding explicitly and use exact FFM `ValueLayout` allocations matching the target architecture.

---

## 👥 Specialized Agent Roles

This workspace is designed to be developed by three cooperative agent personas:

### 1. The Maker (Coder Agent)
*   **Responsibility**: Implements Java/Kotlin code, constructs FFM native interfaces, and authors matching unit/integration tests.
*   **Rules**: Never edit code without a tasks checklist and an accompanying verification test.

### 2. The Checker (Verification Agent)
*   **Responsibility**: Executes code compiles, verification checks (`./gradlew check`, `./scripts/run_tests.sh`), and measures test coverage.
*   **Rules**: If any step fails, automatically trigger the `runTriage` Gradle task to aggregate failure dumps.

### 3. The Triager (Diagnostics Agent)
*   **Responsibility**: Ingests compiler, test, and kernel logs (`build/triage_report.json`) on failure.
*   **Rules**: Map syscall violations to symbolic names, inspect JVM thread dumps for deadlocks, and formulate tasks for the Maker to execute.

---

## 🔄 Spec-Driven Development Workflow

Every task in this repository must progress through these structured phases:
1. **Requirements (`requirements.md` / `bugfix.md`):** Outline what behaves incorrectly or what features are required.
2. **Design (`design.md`):** Formulate the architecture, sequence diagrams, and security/safepoint impacts before modifying any code.
3. **Task Breakdown (`tasks.md`):** Break down the approved design into sequential, trackable tasks.
4. **Execution & Verification:** Implement incrementally, keeping the code compileable at every step. Run test validation suites.
