# Core System Specification: mazewall

This document defines the high-level system specification, architectural components, and verification protocols for **mazewall**. It acts as the source of truth for all autonomous agent implementations.

---

## 1. System Objectives

`mazewall` provides secure, low-overhead, in-process compartmentalization for JVM applications running on Linux hosts. It bounds security-critical actions (filesystem access, process execution, socket operations) to contain execution blast-radii.

```
       +---------------------------------------------+
       |                 JVM Process                 |
       |  +---------------------------------------+  |
       |  |            Reasoning Thread           |  |
       |  |  [Syscall Blocked by Seccomp/Landlock] |  |
       |  +---------------------------------------+  |
       |                       |                     |
       |             FFM API  v                      |
       +----------------------|----------------------+
                              | Kernel Boundary
       +----------------------v----------------------+
       |                 Linux Kernel                |
       |        [Seccomp-BPF]      [Landlock LSM]    |
       +---------------------------------------------+
```

---

## 2. Components & Scope

### A. The Enforcer Module (`:enforcer`)
*   **Purpose**: Programmatic installation of thread-scoped and process-wide sandboxes.
*   **Core Systems**:
    *   **`BpfFilter`**: Linear scan BPF filters compiled at runtime and loaded via `seccomp(2)`.
    *   **`Landlock`**: Path-based LSM restrictions loaded dynamically.
    *   **`ContainedExecutors`**: Spawns carrier threads with pre-seeded, locked down seccomp boundaries.

### B. The Profiler Module (`:profiler`)
*   **Purpose**: Unprivileged system call profiling and behavioral analysis.
*   **Core Systems**:
    *   **`USER_NOTIF` ACK Loop**: Captures blocked system calls asynchronously without crashing the JVM, allowing developers to generate a Bill of Behavior (BoB).

---

## 3. Core Verification Protocol

To verify that the system is functioning correctly and has not regressed, any agent modifying the codebase must run the following automated suite:

1.  **Unit Tests**: `./gradlew test` (verifies compiler settings, structures, layout offsets).
2.  **Integration Tests**: `./scripts/run_tests.sh` (executes the tests inside an OCI container to test Landlock and Seccomp interactions with the host kernel).
3.  **Lints**: `./scripts/lint.sh` (runs Detekt and ktlint).
